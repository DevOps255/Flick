package com.flick.app.ui.transfer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flick.app.core.FileItem
import com.flick.app.core.TransferProgress
import com.flick.app.core.TransferStatus
import com.flick.app.ui.components.Avatar
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.components.formatBytes
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Teal
import java.util.Locale

@Composable
fun TransferScreen(onClose: () -> Unit, viewModel: TransferViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress = state.progress

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("File transfer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        when {
            state.error != null -> Text(
                state.error.orEmpty(),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            progress != null -> TransferBody(progress, onClose)
        }
    }
}

@Composable
private fun ColumnScope.TransferBody(p: TransferProgress, onClose: () -> Unit) {
    val ringProgress by animateFloatAsState(p.progress, tween(250, easing = LinearEasing), label = "ring")
    val finished = p.files.count { it.progress >= 1f }
    val done = p.status == TransferStatus.Completed
    val caption = when (p.status) {
        TransferStatus.Preparing -> "Connecting to"
        TransferStatus.InProgress -> "Sending file to"
        TransferStatus.Completed -> "Sent to"
        TransferStatus.Failed -> "Failed to reach"
    }

    LazyColumn(contentPadding = PaddingValues(20.dp), modifier = Modifier.weight(1f)) {
        item {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Ring(ringProgress, Modifier.size(220.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar("You", size = 52.dp)
                        Box(
                            Modifier.padding(horizontal = 6.dp).size(36.dp).background(Teal, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (done) Icons.Filled.Check else Icons.Outlined.Description, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                        Avatar(p.peer.name, size = 52.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(caption, style = MaterialTheme.typography.labelMedium)
                    Text(p.peer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("Items", "$finished of ${p.files.size}")
                Stat("Size", formatBytes(p.totalBytes))
                Stat("Speed", String.format(Locale.US, "%.1f MB/s", p.bytesPerSecond / 1e6))
            }
        }
        item { Text("File details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)) }
        items(p.files, key = { it.uri }) { FileRow(it) }
    }
    if (done) {
        FlickButton("Done", Icons.Filled.Check, onClose, Indigo, Modifier.fillMaxWidth().padding(20.dp))
    }
}

/** Progress ring. Constant motion, so it uses linear easing on purpose. */
@Composable
private fun Ring(progress: Float, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(Teal, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FileRow(file: FileItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Description, null, tint = Indigo, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${formatBytes(file.transferredBytes)} / ${formatBytes(file.sizeBytes)}", style = MaterialTheme.typography.labelMedium)
        }
        CircularProgressIndicator(progress = { file.progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Indigo)
    }
}
