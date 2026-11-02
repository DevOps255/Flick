package com.flick.app.ui.home

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flick.app.data.db.FriendEntity
import com.flick.app.data.db.TransferEntity
import com.flick.app.ui.components.Avatar
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Mint
import com.flick.app.ui.theme.Teal
import com.flick.app.ui.theme.pressable

@Composable
fun HomeScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.onFilesPicked(uris)
            onSend()
        }
    }

    Column(Modifier.fillMaxSize().background(Mint)) {
        Header(
            name = state.profile?.name.orEmpty(),
            flickId = state.profile?.flickId.orEmpty(),
            onSend = { picker.launch(arrayOf("*/*")) },
            onReceive = onReceive,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        ) {
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
                item { SectionTitle("Favourite friends") }
                item { FriendsRow(state.friends) }
                item { Spacer(Modifier.height(24.dp)) }
                item { SectionTitle("Latest activities") }
                if (state.activity.isEmpty()) {
                    item { EmptyHint("Transfers you send or receive show up here.") }
                } else {
                    items(state.activity, key = { it.id }) { ActivityRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Header(name: String, flickId: String, onSend: () -> Unit, onReceive: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("flick", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Notifications, contentDescription = "Notifications", tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name.ifBlank { "You" }, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(flickId, style = MaterialTheme.typography.labelMedium)
                }
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.CropFree, contentDescription = "Scan code", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlickButton("Send File", Icons.AutoMirrored.Outlined.Send, onSend, Indigo, Modifier.weight(1f))
                FlickButton("Receive File", Icons.Outlined.FileDownload, onReceive, Teal, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = Indigo) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Flick to PC", style = MaterialTheme.typography.bodyLarge)
                Text("Move a file to a computer by dragging it off the edge", style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (friends.isEmpty()) {
        EmptyHint("Add someone from Nearby and they will appear here.")
        return
    }
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

@Composable
private fun ActivityRow(item: TransferEntity) {
    val verb = if (item.direction == "received") "Received from" else "Sent to"
    val whenText = DateUtils.getRelativeTimeSpanString(item.finishedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .pressable(onClick = {})
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.peerName, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$verb ${item.peerName}", style = MaterialTheme.typography.bodyLarge)
            Text("${item.fileCount} files · $whenText", style = MaterialTheme.typography.labelMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
