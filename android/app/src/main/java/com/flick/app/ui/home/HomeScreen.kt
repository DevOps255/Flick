package com.flick.app.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import com.flick.app.ui.theme.Lavender
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.flick.app.R
import com.flick.app.core.StorageSpace
import com.flick.app.data.db.FileRecordEntity
import com.flick.app.data.db.FriendEntity
import com.flick.app.ui.components.Avatar
import com.flick.app.ui.components.FileThumb
import com.flick.app.ui.components.IsoIcon
import com.flick.app.ui.components.formatBytes
import com.flick.app.ui.openFile
import com.flick.app.ui.theme.Accent
import com.flick.app.ui.theme.Coral
import com.flick.app.ui.theme.Cream
import com.flick.app.ui.theme.Ink
import com.flick.app.ui.theme.Outline
import com.flick.app.ui.theme.Sun
import com.flick.app.ui.theme.pressable

/**
 * The colour behind the top of the home screen. To make it cream like the rest of the app, replace the
 * whole line below with: private val HeaderBackground: Brush = SolidColor(Lavender)
 * (import androidx.compose.ui.graphics.SolidColor and com.flick.app.ui.theme.Lavender).
 */
private val HeaderBackground: Brush = SolidColor(Lavender)

/**
 * The first screen: the name, two big buttons, the free space and the files that came or went recently.
 * Send opens the file picker page. Receive opens the nearby phones.
 */
@Composable
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit, onProfile: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    Column(Modifier.fillMaxSize().background(HeaderBackground)) {
        Header(
            photoPath = state.profile?.photoPath,
            name = state.profile?.name.orEmpty(),
            onSend = onSend,
            onReceive = onReceive,
            onProfile = onProfile,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        ) {
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
                state.storage?.let { item { StorageCard(it) } }
                item { Spacer(Modifier.height(24.dp)) }
                item { SectionTitle("Recent files") }
                if (state.recentFiles.isEmpty()) {
                    item { EmptyHint("Files you send or receive show up here.") }
                } else {
                    items(state.recentFiles, key = { it.id }) { RecentFileRow(it, onRemove = viewModel::remove) }
                }
                if (state.friends.isNotEmpty()) {
                    item { Spacer(Modifier.height(16.dp)) }
                    item { SectionTitle("Favourite friends") }
                    item { FriendsRow(state.friends) }
                }
            }
        }
    }
}

@Composable
private fun Header(photoPath: String?, name: String, onSend: () -> Unit, onReceive: () -> Unit, onProfile: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The F is the logo. The rest of the word is text.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_flick_logo), contentDescription = "F", modifier = Modifier.height(40.dp))
                Text("lick", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(start = 2.dp))
            }
            Box(Modifier.pressable(onProfile)) { Avatar(name.ifBlank { "You" }, size = 44.dp, photoPath = photoPath) }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionCard("Send", Accent, "icon_send", Icons.AutoMirrored.Outlined.Send, onSend, Modifier.weight(1f))
            ActionCard("Receive", Coral, "icon_receive", Icons.Outlined.FileDownload, onReceive, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionCard(label: String, color: Color, iconName: String, fallback: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.height(148.dp).background(color, RoundedCornerShape(28.dp)).pressable(onClick).padding(18.dp)) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart))
        IsoIcon(iconName, fallback, Modifier.size(84.dp).align(Alignment.BottomEnd), tint = Color.White.copy(alpha = 0.9f))
    }
}

/** Used and total space, with a ring. The numbers come from the phone's main storage. */
@Composable
private fun StorageCard(space: StorageSpace) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).border(1.dp, Outline, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${formatBytes(space.usedBytes)} / ${formatBytes(space.totalBytes)}", style = MaterialTheme.typography.titleMedium)
            Text("${formatBytes(space.freeBytes)} free", style = MaterialTheme.typography.labelMedium)
        }
        Canvas(Modifier.size(56.dp)) {
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arc = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(Outline, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = stroke)
            drawArc(Accent, -90f, 360f * space.usedShare, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = stroke)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FriendsRow(friends: List<FriendEntity>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(friends, key = { it.flickId }) { friend ->
            Column(
                Modifier
                    .width(94.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(friend.name, size = 52.dp)
                Spacer(Modifier.height(8.dp))
                Text(friend.name.substringBefore(" "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** One sent or received file. Tapping opens it. The menu opens it, removes it from the list, or deletes it. */
@Composable
private fun RecentFileRow(item: FileRecordEntity, onRemove: (FileRecordEntity, Boolean) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val whenText = DateUtils.getRelativeTimeSpanString(item.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val who = if (item.received) "From ${item.peerName}" else "To ${item.peerName}"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .pressable({ openFile(context, item.uri, item.mime) })
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(item.uri, item.mime, null, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$who · ${formatBytes(item.sizeBytes)} · $whenText", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; openFile(context, item.uri, item.mime) })
                DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; onRemove(item, false) })
                if (item.received) {
                    DropdownMenuItem(text = { Text("Delete file from phone") }, onClick = { menu = false; onRemove(item, true) })
                }
            }
        }
    }
}
