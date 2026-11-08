package com.flick.app.ui.send

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.text.format.DateUtils
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.flick.app.ui.components.ScrollJump
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flick.app.core.DeviceFile
import com.flick.app.core.FileCategory
import com.flick.app.core.FileSort
import com.flick.app.core.SortKey
import com.flick.app.ui.components.FileThumb
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.components.IsoIcon
import com.flick.app.ui.components.formatBytes
import com.flick.app.ui.theme.Accent
import com.flick.app.ui.theme.Coral
import com.flick.app.ui.theme.Ink
import com.flick.app.ui.theme.Plum
import com.flick.app.ui.theme.Sun
import com.flick.app.ui.theme.Teal
import com.flick.app.ui.theme.pressable

@Composable
fun SendScreen(onBack: () -> Unit, onSend: () -> Unit, viewModel: SendViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val inside = state.category != null || state.query.isNotBlank()
    BackHandler(enabled = inside) { viewModel.open(null) }

    var typed by remember { mutableStateOf("") }
    LaunchedEffect(state.query) { if (state.query.isEmpty() && typed.isNotEmpty()) typed = "" }

    var confirmDelete by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) viewModel.finishDelete()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        TopBar(state, onBack = { if (inside) viewModel.open(null) else onBack() }, viewModel = viewModel)
        SearchField(typed) {
            typed = it
            viewModel.setQuery(it)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.showsCards -> Categories(onOpen = viewModel::open)
                state.missing == Missing.AllFilesAccess -> PermissionCard(
                        text = "Android asks you to allow access to all files before Flick can list your files. Allow it on the page that opens, then come back.",
                        onAllow = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                        },
                        onOpenInfo = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                )
                state.loading && state.files.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                state.files.isEmpty() -> Text(
                        if (state.query.isBlank()) "No files here." else "Nothing looks like \"${state.query}\".",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.grid -> FileGrid(state, viewModel::toggle)
                else -> FileList(state, viewModel::toggle)
            }
        }
        if (state.selected.isNotEmpty()) {
            val count = state.selected.size
            val deletable = state.selected.values.none { it.category == FileCategory.Apps }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
                FlickButton(
                        "Send $count ${if (count == 1) "file" else "files"} (${formatBytes(state.selectedBytes)})",
                        Icons.AutoMirrored.Outlined.Send,
                        { viewModel.confirm(); onSend() },
                        Accent,
                        Modifier.fillMaxWidth(),
                )
                Row(Modifier.align(Alignment.CenterHorizontally)) {
                    TextButton(onClick = viewModel::clearSelection) { Text("Clear selection") }
                    if (deletable) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (confirmDelete) {
                AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete $count ${if (count == 1) "file" else "files"}?") },
                        text = { Text("They are removed from your phone. This cannot be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                viewModel.deleteSelected()?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                )
            }
        }
    }
}


@Composable
private fun TopBar(state: SendUiState, onBack: () -> Unit, viewModel: SendViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(state.category?.let { style(it).label } ?: "Send", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (!state.showsCards) {
            IconButton(onClick = viewModel::toggleGrid) {
                // The icon shows the mode the tap switches to.
                Icon(if (state.grid) Icons.Outlined.Menu else Icons.Outlined.GridView, contentDescription = if (state.grid) "Show as list" else "Show as grid")
            }
            SortMenu(state.sort, viewModel::setSort)
        }
    }
}

@Composable
private fun SortMenu(current: FileSort, onPick: (FileSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.SwapVert, contentDescription = "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SORTS.forEach { (label, sort) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (sort == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onPick(sort); open = false },
                )
            }
        }
    }
}

private val SORTS = listOf(
    "Newest first" to FileSort(SortKey.Date, descending = true),
    "Oldest first" to FileSort(SortKey.Date, descending = false),
    "Name A to Z" to FileSort(SortKey.Name, descending = false),
    "Name Z to A" to FileSort(SortKey.Name, descending = true),
    "Largest first" to FileSort(SortKey.Size, descending = true),
    "Smallest first" to FileSort(SortKey.Size, descending = false),
)

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search files") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}

// ---------------------------------------------------------------- first page

private class CategoryStyle(val label: String, val color: Color, val onColor: Color, val icon: String, val fallback: ImageVector)

private fun style(category: FileCategory) = when (category) {
    FileCategory.Documents -> CategoryStyle("Documents", Coral, Color.White, "icon_category_documents", Icons.Outlined.Description)
    FileCategory.Downloads -> CategoryStyle("Downloads", Accent, Color.White, "icon_category_downloads", Icons.Outlined.FileDownload)
    FileCategory.Videos -> CategoryStyle("Videos", Sun, Ink, "icon_category_videos", Icons.Outlined.Movie)
    FileCategory.Audio -> CategoryStyle("Audio", Color(0xFF8C6BC8), Color.White, "icon_category_audio", Icons.Outlined.MusicNote)
    FileCategory.Apps -> CategoryStyle("Apps", Teal, Color.White, "icon_category_apps", Icons.Outlined.Apps)
    FileCategory.Images -> CategoryStyle("Images", Plum, Color.White, "icon_category_images", Icons.Outlined.Image)
}

@Composable
private fun Categories(onOpen: (FileCategory) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FileCategory.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { CategoryCard(it, { onOpen(it) }, Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CategoryCard(category: FileCategory, onClick: () -> Unit, modifier: Modifier) {
    val look = style(category)
    Box(modifier.height(132.dp).background(look.color, RoundedCornerShape(24.dp)).pressable(onClick).padding(16.dp)) {
        Text(look.label, color = look.onColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopStart))
        IsoIcon(look.icon, look.fallback, Modifier.size(72.dp).align(Alignment.BottomEnd), tint = look.onColor.copy(alpha = 0.9f))
    }
}

@Composable
private fun PermissionCard(text: String, onAllow: () -> Unit, onOpenInfo: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permission needed", style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlickButton("Allow", Icons.Filled.Check, onAllow, Accent, Modifier.fillMaxWidth())
        TextButton(onClick = onOpenInfo) { Text("The switch is grey? Open the app info") }
    }
}

// ---------------------------------------------------------------- files

@Composable
private fun FileGrid(state: SendUiState, onToggle: (DeviceFile) -> Unit) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.files, key = { it.uri }) { file ->
                val picked = file.uri in state.selected
                Column(Modifier.pressable({ onToggle(file) })) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        FileThumb(file.uri, file.mime, file.packageName, Modifier.fillMaxSize(), size = null)
                        if (picked) Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)))
                        Tick(picked, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Text(file.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        ScrollJump(
            canGoUp = gridState.canScrollBackward,
            canGoDown = gridState.canScrollForward,
            onUp = { scope.launch { gridState.animateScrollToItem(0) } },
            onDown = { scope.launch { gridState.animateScrollToItem(gridState.layoutInfo.totalItemsCount - 1) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun FileList(state: SendUiState, onToggle: (DeviceFile) -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
            items(state.files, key = { it.uri }) { file ->
                val picked = file.uri in state.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .pressable({ onToggle(file) })
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileThumb(file.uri, file.mime, file.packageName, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val date = DateUtils.formatDateTime(context, file.modifiedMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
                        Text("${formatBytes(file.sizeBytes)} · $date", style = MaterialTheme.typography.labelMedium)
                    }
                    Tick(picked)
                }
            }
        }
        ScrollJump(
            canGoUp = listState.canScrollBackward,
            canGoDown = listState.canScrollForward,
            onUp = { scope.launch { listState.animateScrollToItem(0) } },
            onDown = { scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/** The round mark that shows whether a file is ticked. */
@Composable
private fun Tick(picked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).background(if (picked) Accent else Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}
