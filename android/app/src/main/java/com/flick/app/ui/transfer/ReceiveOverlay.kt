package com.flick.app.ui.transfer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.flick.app.data.IncomingTransfer
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.components.formatBytes
import com.flick.app.ui.effects.LocalNameDrop
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Motion
import com.flick.app.ui.theme.Teal
import com.flick.app.ui.theme.pressable
import com.flick.app.ui.theme.rememberReduceMotion
import java.io.File
import java.util.Locale

/**
 * What the receiving phone shows while a file arrives.
 *
 * The card drops in from the top edge, the direction the sender threw it from.
 * The file icon fills from the bottom with the bytes that really arrived, so the
 * screen never says "received" before the file is on disk.
 *
 * Waiting on a large file is made pleasant without bending the truth: every
 * update animates continuously instead of jumping, the speed is smoothed so it
 * does not flicker, the time left is shown, and a light tick marks each quarter.
 */
@Composable
fun ReceiveOverlay(incoming: IncomingTransfer, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val progress by animateFloatAsState(incoming.progress, tween(250, easing = LinearEasing), label = "fill")
    val enter by animateFloatAsState(1f, Motion.settle(), label = "enter")
    val done = incoming.finished && incoming.ok
    val wave = LocalNameDrop.current
    LaunchedEffect(incoming.sessionId) { wave.play() }

    // One light tick per quarter, and a firmer one when the file is complete.
    val quarter = (incoming.progress * 4).toInt().coerceAtMost(3)
    LaunchedEffect(quarter) { if (quarter > 0 && !incoming.finished) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(done) { if (done) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .graphicsLayer {
                    translationY = -(1f - enter) * 120.dp.toPx()
                    alpha = enter
                }
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FillingFile(progress = progress, done = done, shimmer = !reduceMotion && !incoming.finished)
            Spacer(Modifier.height(20.dp))
            Text(headline(incoming), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                incoming.fileNames.firstOrNull().orEmpty() + if (incoming.fileNames.size > 1) " and ${incoming.fileNames.size - 1} more" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            Text(stats(incoming), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            if (incoming.finished) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (done && incoming.savedPaths.size == 1) {
                        FlickButton("Open", Icons.Outlined.FileDownload, { openFile(context, incoming.savedPaths.first()) }, Teal, Modifier.weight(1f))
                    }
                    FlickButton("Done", Icons.Filled.Check, onDone, Indigo, Modifier.weight(1f))
                }
            }
        }
    }
}

/** A file icon that fills from the bottom as bytes arrive. */
@Composable
private fun FillingFile(progress: Float, done: Boolean, shimmer: Boolean) {
    val sweep = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val pop by animateFloatAsState(if (done) 1f else 0.85f, Motion.enter(200), label = "pop")
    Box(Modifier.size(104.dp).graphicsLayer { scaleX = if (done) pop else 1f; scaleY = if (done) pop else 1f }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = CornerRadius(28.dp.toPx())
            val shape = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, radius)) }
            drawPath(shape, Color(0xFFF1F2FB))
            clipPath(shape) {
                val top = size.height * (1f - progress)
                drawRect(Teal, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, size.height - top))
                if (shimmer && progress in 0.02f..0.98f) {
                    // Decorative only: a soft light crossing the filled part. It says nothing about speed.
                    val x = size.width * (sweep.value * 1.6f - 0.3f)
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), startX = x - 30.dp.toPx(), endX = x + 30.dp.toPx()),
                        topLeft = Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height - top),
                    )
                }
            }
        }
        Icon(
            if (done) Icons.Filled.Check else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (progress > 0.55f || done) Color.White else Indigo,
            modifier = Modifier.size(40.dp),
        )
    }
}

private fun headline(i: IncomingTransfer) = when {
    i.finished && !i.ok -> "The transfer was interrupted"
    i.finished -> "Received from ${i.sender}"
    else -> "${i.sender} is sending you ${if (i.fileNames.size == 1) "a file" else "${i.fileNames.size} files"}"
}

private fun stats(i: IncomingTransfer): String {
    if (i.finished) return if (i.ok) formatBytes(i.totalBytes) + " saved" else "Nothing was lost on your phone, try again."
    val left = i.secondsLeft?.let { if (it < 1) "almost done" else "about $it s left" } ?: "connecting"
    val speed = if (i.bytesPerSecond > 0) " · " + String.format(Locale.US, "%.1f MB/s", i.bytesPerSecond / 1e6) else ""
    return "${formatBytes(i.doneBytes)} of ${formatBytes(i.totalBytes)}$speed · $left"
}

internal fun openFile(context: Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app can open this type. The file is still saved.
    }
}
