package com.flick.app.ui.send

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flick.app.core.DeviceFile
import com.flick.app.core.FileCatalog
import com.flick.app.core.FileCategory
import com.flick.app.core.FileSort
import com.flick.app.data.FileListing
import com.flick.app.data.SendSelection
import com.flick.app.ui.Permissions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user must allow before a category can be listed. */
enum class Missing { Nothing, AllFilesAccess }

data class SendUiState(
    /** The open category, or null on the first page with the six cards. */
    val category: FileCategory? = null,
    val query: String = "",
    val sort: FileSort = FileSort(),
    val grid: Boolean = true,
    val loading: Boolean = false,
    val missing: Missing = Missing.Nothing,
    /** The files to show: the open category, or every category while searching from the first page. */
    val files: List<DeviceFile> = emptyList(),
    val selected: Map<String, DeviceFile> = emptyMap(),
) {
    val selectedBytes: Long get() = selected.values.sumOf { it.sizeBytes }
    val showsCards: Boolean get() = category == null && query.isBlank()
}

/**
 * Lists the files of one category, searches and orders them, and holds what the user ticked.
 * The ticked files stay selected when the user moves from one category to another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SendViewModel @Inject constructor(
    private val catalog: FileCatalog,
    private val selection: SendSelection,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private class Raw(
        val category: FileCategory? = null,
        val query: String = "",
        val sort: FileSort = FileSort(),
        val grid: Boolean = true,
        val loading: Boolean = false,
        val loaded: Map<FileCategory, List<DeviceFile>> = emptyMap(),
        val selected: Map<String, DeviceFile> = emptyMap(),
        /** Bumped when a permission may have changed, so the missing permission is computed again. */
        val permissionTick: Int = 0,
    ) {
        fun copy(
            category: FileCategory? = this.category,
            query: String = this.query,
            sort: FileSort = this.sort,
            grid: Boolean = this.grid,
            loading: Boolean = this.loading,
            loaded: Map<FileCategory, List<DeviceFile>> = this.loaded,
            selected: Map<String, DeviceFile> = this.selected,
            permissionTick: Int = this.permissionTick,
        ) = Raw(category, query, sort, grid, loading, loaded, selected, permissionTick)
    }

    private val raw = MutableStateFlow(Raw())

    val state: StateFlow<SendUiState> = raw
        .mapLatest { it.toState() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendUiState())

    fun open(category: FileCategory?) {
        raw.update { it.copy(category = category, query = "") }
        if (category != null) load(category)
    }

    fun setQuery(query: String) {
        raw.update { it.copy(query = query) }
        // Searching from the first page looks in every category, so all of them are listed once.
        if (query.isNotBlank() && raw.value.category == null) FileCategory.entries.forEach(::load)
    }

    fun setSort(sort: FileSort) = raw.update { it.copy(sort = sort) }

    fun toggleGrid() = raw.update { it.copy(grid = !it.grid) }

    fun toggle(file: DeviceFile) = raw.update {
        it.copy(selected = if (file.uri in it.selected) it.selected - file.uri else it.selected + (file.uri to file))
    }

    fun clearSelection() = raw.update { it.copy(selected = emptyMap()) }

    /** Called when the user comes back from a permission prompt or from the settings page. */
    fun refresh() {
        raw.update { it.copy(loaded = emptyMap(), permissionTick = it.permissionTick + 1) }
        raw.value.category?.let(::load)
    }

    /**
     * Deletes the ticked files from the phone. Files found by path are deleted here. Files from the media
     * store need the user's approval in a system dialog on Android 11 and later: the returned sender opens it,
     * and [finishDelete] runs once the user agrees. Returns null when nothing is left to approve.
     */
    fun deleteSelected(): IntentSender? {
        val uris = raw.value.selected.values.map { Uri.parse(it.uri) }
        val (paths, media) = uris.partition { it.scheme == "file" }
        paths.forEach { uri -> uri.path?.let { File(it).delete() } }
        if (media.isEmpty()) {
            finishDelete()
            return null
        }
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return MediaStore.createDeleteRequest(resolver, media).intentSender
        media.forEach { runCatching { resolver.delete(it, null, null) } }
        finishDelete()
        return null
    }

    /** Forgets the ticked files and lists again, once they are gone. */
    fun finishDelete() {
        raw.update { it.copy(selected = emptyMap()) }
        refresh()
    }

    /** Hands the ticked files to the nearby screen, which sends them. */
    fun confirm() = selection.select(raw.value.selected.values.map { it.toItem() })

    private fun load(category: FileCategory) {
        if (category in raw.value.loaded) return
        viewModelScope.launch {
            raw.update { it.copy(loading = true) }
            val files = catalog.files(category)
            raw.update { it.copy(loading = false, loaded = it.loaded + (category to files)) }
        }
    }

    private fun Raw.toState(): SendUiState {
        val pool = when {
            category != null -> loaded[category].orEmpty()
            query.isNotBlank() -> loaded.values.flatten()
            else -> emptyList()
        }
        return SendUiState(
            category = category,
            query = query,
            sort = sort,
            grid = grid,
            loading = loading,
            missing = category?.let(::missingFor) ?: Missing.Nothing,
            // The same file can sit in two categories, for example a PDF in Downloads and in Documents.
            files = FileListing.apply(pool.distinctBy { it.uri }, query, sort),
            selected = selected,
        )
    }

    /**
     * One permission opens everything: access to all files. Photos, videos and music also work with the
     * lighter media permissions, so a phone that already has them is not asked again. Apps need none.
     */
    private fun missingFor(category: FileCategory): Missing = when (category) {
        FileCategory.Images, FileCategory.Videos, FileCategory.Audio ->
            if (Permissions.hasMedia(context)) Missing.Nothing else Missing.AllFilesAccess
        FileCategory.Documents, FileCategory.Downloads ->
            if (Permissions.hasAllFiles(context)) Missing.Nothing else Missing.AllFilesAccess
        FileCategory.Apps -> Missing.Nothing
    }
}
