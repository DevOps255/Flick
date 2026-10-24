package com.flick.app.ui.nearby

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.flick.app.core.FileItem
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.components.formatBytes
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Motion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The files waiting to be sent, as a card the user throws up toward the radar.
 *
 * Dragging follows the finger. Letting go past the threshold, or fast enough,
 * throws the card: it keeps the speed of the finger, shrinks and fades as it
 * flies. Letting go early springs it back with the same momentum. A Flick of
 * the phone or a tap on a phone does the same throw without a finger.
 *
 * When no phone is ready the card resists the drag, so it is clear nothing
 * will be sent yet.
 */
@Composable
fun FileTray(
    files: List<FileItem>,
    canSend: Boolean,
    throwRequests: Flow<Unit>,
    onThrowStart: () -> Unit,
    onThrow: () -> Unit,
    onPickFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) {
        FlickButton("Choose a file to send", Icons.AutoMirrored.Outlined.Send, onPickFiles, Indigo, modifier.fillMaxWidth().padding(horizontal = 20.dp))
        return
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val travel = with(LocalDensity.current) { 420.dp.toPx() }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val offsetY = remember { Animatable(0f) }
    val canSendNow = rememberUpdatedState(canSend)
    val throwNow = rememberUpdatedState(onThrow)
    val onThrowStartNow = rememberUpdatedState(onThrowStart)

    // The file stands out: it grows in when picked, and gets a ring once a phone is ready.
    val entrance = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, Motion.settle()) }
    val glow by animateFloatAsState(if (canSend) 1f else 0f, Motion.enter(300), label = "glow")

    suspend fun throwUp(velocity: Float) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onThrowStartNow.value()
        offsetY.animateTo(-travel, tween(260, easing = Motion.EaseOut), initialVelocity = velocity)
        throwNow.value()
        offsetY.snapTo(0f)
    }

    LaunchedEffect(throwRequests) {
        throwRequests.collect { if (canSendNow.value) throwUp(0f) }
    }

    val drag = rememberDraggableState { delta ->
        scope.launch {
            val resistance = if (canSendNow.value) 1f else 0.25f
            offsetY.snapTo((offsetY.value + delta * resistance).coerceAtMost(0f))
        }
    }
    val title = if (files.size == 1) files.first().name else "${files.size} files"
    val size = formatBytes(files.sumOf { it.sizeBytes })

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (canSend) "Swipe up, or flick your phone" else "Hold the phones closer together",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val p = (-offsetY.value / travel).coerceIn(0f, 1f)
                    translationY = offsetY.value
                    scaleX = entrance.value * (1f - 0.45f * p)
                    scaleY = entrance.value * (1f - 0.45f * p)
                    alpha = 1f - 0.8f * p
                }
                .draggable(
                    state = drag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        scope.launch {
                            val enough = offsetY.value < -threshold || velocity < -FLING_PX_PER_S
                            if (canSendNow.value && enough) {
                                throwUp(velocity)
                            } else {
                                offsetY.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f), initialVelocity = velocity)
                            }
                        }
                    },
                )
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(2.dp, Indigo.copy(alpha = glow), RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, null, tint = Indigo, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(size, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private const val FLING_PX_PER_S = 1500f
