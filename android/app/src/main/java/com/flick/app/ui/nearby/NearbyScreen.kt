package com.flick.app.ui.nearby

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.flick.app.core.Peer
import com.flick.app.ui.components.Avatar
import com.flick.app.ui.components.FlickButton
import com.flick.app.ui.effects.LocalNameDrop
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Motion
import com.flick.app.ui.theme.Teal
import com.flick.app.ui.theme.pressable
import com.flick.app.ui.theme.rememberReduceMotion
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Finds nearby phones and sends the picked files to one of them.
 *
 * A phone shows a spinner ring while its certificate is being checked and a
 * check badge once it is ready, which is the moment the user can throw the file.
 */
@Composable
fun NearbyScreen(
    onOpenTransfer: (peerId: String) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var chosenId by remember { mutableStateOf<String?>(null) }
    val throwRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val latest = rememberUpdatedState(state)
    val latestChosen = rememberUpdatedState(chosenId)

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.setMicGranted(it) }
    LaunchedEffect(Unit) {
        viewModel.setMicGranted(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.onFilesPicked(uris)
    }

    // A Flick of the phone throws the file, like swiping the card.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.flicks.collect { throwRequests.tryEmit(Unit) }
        }
    }

    // A light tick each time another phone becomes ready.
    var knownReady by remember { mutableIntStateOf(0) }
    val readyCount = state.peers.count { it.ready }
    LaunchedEffect(readyCount) {
        if (readyCount > knownReady) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        knownReady = readyCount
    }

    val target = state.target(chosenId)

    // File picked and the other phone ready: the wave confirms both phones are in sync.
    val wave = LocalNameDrop.current
    val inSync = state.files.isNotEmpty() && target != null
    LaunchedEffect(inSync) {
        if (inSync) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            wave.play()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("flick nearby", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
        }
        Radar(
            peers = state.peers,
            active = state.enabled && state.micGranted,
            targetId = target?.id,
            onPeerClick = { peer ->
                chosenId = peer.id
                if (state.files.isEmpty()) picker.launch(arrayOf("*/*")) else throwRequests.tryEmit(Unit)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            when {
                !state.micGranted -> MicrophoneNeeded { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                else -> NearbyList(state.peers, state.enabled, onAddFriend = viewModel::addFriend, onPeerClick = {
                    chosenId = it.id
                    if (state.files.isEmpty()) picker.launch(arrayOf("*/*")) else throwRequests.tryEmit(Unit)
                })
            }
            Spacer(Modifier.weight(1f))
            FileTray(
                files = state.files,
                canSend = target != null,
                throwRequests = throwRequests,
                onThrowStart = { wave.play() },
                onThrow = { latest.value.target(latestChosen.value)?.let { onOpenTransfer(it.id) } },
                onPickFiles = { picker.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun MicrophoneNeeded(onAllow: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Flick finds nearby phones by sound", style = MaterialTheme.typography.titleMedium)
        Text(
            "It uses the microphone and speaker for a short inaudible signal. Nothing is recorded or stored.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlickButton("Allow microphone", Icons.Filled.Check, onAllow, Indigo, Modifier.fillMaxWidth())
    }
}

@Composable
private fun NearbyList(peers: List<Peer>, enabled: Boolean, onAddFriend: (Peer) -> Unit, onPeerClick: (Peer) -> Unit) {
    Text("Nearby friends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
    if (peers.isEmpty()) {
        Text(
            if (enabled) "Looking for phones. Open Flick on the other phone and hold them close." else "Turn on Nearby to find phones.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(peers, key = { it.id }) { peer -> PeerCard(peer, onClick = { onPeerClick(peer) }, onAddFriend = { onAddFriend(peer) }) }
    }
}

/**
 * Concentric rings with one avatar per phone. A closer phone sits nearer the
 * centre. The angle comes from the id so a phone never jumps around.
 */
@Composable
private fun Radar(peers: List<Peer>, active: Boolean, targetId: String?, onPeerClick: (Peer) -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val ringColor = MaterialTheme.colorScheme.outline
    val pulse = rememberInfiniteTransition(label = "radarPulse")
    val pulseProgress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = Motion.EaseOut), RepeatMode.Restart),
        label = "pulseProgress",
    )

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val box = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            listOf(0.18f, 0.34f, 0.5f).forEach { fraction ->
                drawCircle(ringColor, radius = size.width * fraction, center = c, style = Stroke(width = 1.5.dp.toPx()))
            }
            if (active && !reduceMotion) {
                val radius = size.width * (0.1f + 0.4f * pulseProgress)
                drawCircle(Indigo.copy(alpha = 0.3f * (1f - pulseProgress)), radius = radius, center = c, style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(Color.White, radius = size.width * 0.1f, center = c)
            drawCircle(Indigo, radius = size.width * 0.035f, center = c, style = Stroke(width = 3.dp.toPx()))
        }

        peers.forEach { peer ->
            key(peer.id) {
                val enter = remember { MutableTransitionState(false).apply { targetState = true } }
                val angle = Math.toRadians(((peer.id.hashCode() and 0x7fffffff) % 360).toDouble())
                val distance = 0.5f - peer.proximity.coerceIn(0f, 1f) * 0.32f
                val x by animateDpAsState(box * (0.5f + distance * cos(angle).toFloat()) - AVATAR / 2, Motion.settle(), label = "peerX")
                val y by animateDpAsState(box * (0.5f + distance * sin(angle).toFloat()) - AVATAR / 2, Motion.settle(), label = "peerY")
                AnimatedVisibility(
                    visibleState = enter,
                    enter = fadeIn(Motion.enter(200)) + scaleIn(Motion.enter(200), initialScale = 0.9f),
                    modifier = Modifier.offset(x, y),
                ) {
                    RadarPeer(peer, isTarget = peer.id == targetId, onClick = { onPeerClick(peer) })
                }
            }
        }
    }
}

private val AVATAR = 44.dp

@Composable
private fun RadarPeer(peer: Peer, isTarget: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(AVATAR), contentAlignment = Alignment.Center) {
        if (!peer.ready) {
            CircularProgressIndicator(Modifier.size(AVATAR + 10.dp), color = Indigo, strokeWidth = 2.dp)
        } else if (isTarget) {
            Box(Modifier.size(AVATAR + 8.dp).border(2.dp, Indigo, CircleShape))
        }
        Avatar(peer.name, size = AVATAR, modifier = Modifier.pressable(onClick))
        AnimatedVisibility(
            visible = peer.ready,
            enter = fadeIn(Motion.enter(200)) + scaleIn(Motion.enter(200), initialScale = 0.6f),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Box(Modifier.size(18.dp).background(Teal, CircleShape).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, onClick: () -> Unit, onAddFriend: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .pressable(onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(peer.name, size = 56.dp)
        Spacer(Modifier.height(8.dp))
        Text(peer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            if (peer.ready) "Ready" else peer.note ?: "Connecting",
            style = MaterialTheme.typography.labelMedium,
            color = if (peer.ready) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Spacer(Modifier.height(10.dp))
        val label = if (peer.isFriend) "Friends" else "+ Add friend"
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (peer.isFriend) Indigo else Color.White,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (peer.isFriend) MaterialTheme.colorScheme.background else Indigo)
                .let { if (peer.isFriend) it else it.pressable(onAddFriend) }
                .padding(vertical = 6.dp),
        )
    }
}
