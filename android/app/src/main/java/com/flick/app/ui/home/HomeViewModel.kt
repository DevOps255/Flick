package com.flick.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flick.app.core.FileCatalog
import com.flick.app.core.Profile
import com.flick.app.core.StorageSpace
import com.flick.app.data.ProfileStore
import com.flick.app.data.ReceivedFiles
import com.flick.app.data.db.FileRecordDao
import com.flick.app.data.db.FileRecordEntity
import com.flick.app.data.db.FriendDao
import com.flick.app.data.db.FriendEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val profile: Profile? = null,
    val friends: List<FriendEntity> = emptyList(),
    val recentFiles: List<FileRecordEntity> = emptyList(),
    val storage: StorageSpace? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileStore: ProfileStore,
    friendDao: FriendDao,
    private val fileRecordDao: FileRecordDao,
    private val received: ReceivedFiles,
    private val catalog: FileCatalog,
) : ViewModel() {

    private val storage = MutableStateFlow<StorageSpace?>(null)

    val state: StateFlow<HomeUiState> = combine(
        profileStore.profile,
        friendDao.observeAll(),
        fileRecordDao.recent(RECENT_LIMIT),
        storage,
    ) { profile, friends, files, space -> HomeUiState(profile, friends, files, space) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { profileStore.ensureCreated() }
        refreshStorage()
    }

    /** Reads the free space again. Called each time the home screen appears. */
    fun refreshStorage() {
        storage.value = catalog.storage()
    }

    /**
     * Takes a file off the list. With [deleteFile], a received file is also deleted from the phone.
     * A sent file is never deleted: it is the user's original.
     */
    fun remove(record: FileRecordEntity, deleteFile: Boolean) {
        viewModelScope.launch {
            if (deleteFile && record.received) {
                val uri = Uri.parse(record.uri)
                if (uri.scheme == "file") uri.path?.let { File(it).delete() } else received.delete(uri)
            }
            fileRecordDao.delete(record.id)
            refreshStorage()
        }
    }

    private companion object {
        const val RECENT_LIMIT = 30
    }
}
