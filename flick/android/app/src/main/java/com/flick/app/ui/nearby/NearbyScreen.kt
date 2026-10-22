package com.flick.app.ui.nearby

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flick.app.core.Peer
import com.flick.app.ui.components.Avatar
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Motion
import com.flick.app.ui.theme.pressable
import com.flick.app.ui.theme.rememberReduceMotion
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NearbyScreen(
    onOpenTransfer: (peerId: String) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingPeerId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val peerId = pendingPeerId
        pendingPeerId = null
        if (uris.isNotEmpty() && peerId != null) {
            viewModel.onFilesPicked(uris)
            onOpenTransfer(peerId)
        }
    }
    val onPeerClick: (Peer) -> Unit = { peer ->
        if (state.hasSelection) {
            onOpenTransfer(peer.id)
        } else {
            pendingPeerId = peer.id
            picker.launch(arrayOf("*/*"))
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("flick nearby", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
        }
        Radar(
            peers = state.peers,
            active = state.enabled,
            onPeerClick = onPeerClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 24.dp),
        ) {
            Text("Nearby friends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
            if (state.peers.isEmpty()) {
                Text(
                    if (state.enabled) "Looking for devices. Hold the phones close together." else "Turn on Nearby to find devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.peers, key = { it.id }) { peer ->
                        PeerCard(peer, onClick = { onPeerClick(peer) }, onAddFriend = { viewModel.addFriend(peer) })
                    }
                }
            }
        }
    }
}

/**
 * Concentric rings with one avatar per peer. A closer peer sits nearer the
 * centre. The angle comes from the peer id so a device never jumps around.
 */
@Composable
private fun Radar(peers: List<Peer>, active: Boolean, onPeerClick: (Peer) -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val ringColor = MaterialTheme.colorScheme.outline
    val pulseColor = Indigo
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
                drawCircle(pulseColor.copy(alpha = 0.3f * (1f - pulseProgress)), radius = radius, center = c, style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(Color.White, radius = size.width * 0.1f, center = c)
            drawCircle(pulseColor, radius = size.width * 0.035f, center = c, style = Stroke(width = 3.dp.toPx()))
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
                    Avatar(peer.name, size = AVATAR, modifier = Modifier.pressable({ onPeerClick(peer) }))
                }
            }
        }
    }
}

private val AVATAR = 44.dp

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
        Text(peer.flickId, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        val label = if (peer.isFriend) "Friends" else "+ Add friend"
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (peer.isFriend) Indigo else Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (peer.isFriend) MaterialTheme.colorScheme.background else Indigo)
                .let { if (peer.isFriend) it else it.pressable(onAddFriend) }
                .padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
