package com.flick.app.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.app.ui.theme.Indigo
import kotlin.math.PI
import kotlin.math.sin

/**
 * Starts the AirDrop effect. Any screen can call [play].
 *
 * It plays when the link to the paired phone is really up, not when a gesture is merely detected,
 * and again when the file is sent. While it plays, the background blurs and stretches and a wave
 * crosses the screen, and the file stands out sharp in the middle.
 */
@Stable
class AirDropEffect {
    internal var request by mutableIntStateOf(0)
    internal var active by mutableStateOf(false)

    /** Large text of the card in the middle, normally the file name. */
    var title by mutableStateOf("")
        private set

    /** Smaller text under it. */
    var subtitle by mutableStateOf("")
        private set

    fun play(title: String, subtitle: String = "") {
        this.title = title
        this.subtitle = subtitle
        request++
    }
}

val LocalAirDrop = compositionLocalOf { AirDropEffect() }

/** The shader's clock runs from where its wave is at rest to where it is at rest again: one full cycle. */
private const val START = (-PI / 2).toFloat()
private const val END = (3 * PI / 2).toFloat()

/** The Shadertoy original runs one cycle in 6.3 seconds. Three times faster feels like AirDrop. */
private const val DURATION_MS = 2_100

/**
 * Draws [content] with the effect, and the file card above it. [foreground] is drawn above both and is
 * never touched by the effect: dialogs, banners and the receiving card.
 *
 * From Android 13 the real shader runs. On Android 12 only the blur is available. Before that there is no effect.
 */
@Composable
fun AirDropHost(
    effect: AirDropEffect,
    modifier: Modifier = Modifier,
    foreground: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val time = remember { Animatable(START) }
    LaunchedEffect(effect.request) {
        if (effect.request == 0) return@LaunchedEffect
        effect.active = true
        time.snapTo(START)
        time.animateTo(END, tween(DURATION_MS, easing = LinearEasing))
        effect.active = false
    }
    Box(modifier) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ShaderLayer(effect, time, content)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> BlurLayer(effect, time, content)
            else -> content()
        }
        if (effect.active) Spotlight(effect, (time.value - START) / (END - START))
        foreground()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderLayer(effect: AirDropEffect, time: Animatable<Float, *>, content: @Composable () -> Unit) {
    val shader = remember { RuntimeShader(AIRDROP_AGSL) }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // The effect is removed when idle, so the screen is drawn normally the rest of the time.
            renderEffect = if (effect.active) {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("time", time.value)
                RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
            } else {
                null
            }
        },
    ) { content() }
}

@Composable
private fun BlurLayer(effect: AirDropEffect, time: Animatable<Float, *>, content: @Composable () -> Unit) {
    val strength = if (effect.active) (1f + sin(time.value)) * 0.5f else 0f
    Box(Modifier.fillMaxSize().blur((18 * strength).dp)) { content() }
}

/** The file card, sharp and lifted above the blurred screen. It grows in, holds, then fades. */
@Composable
private fun BoxScope.Spotlight(effect: AirDropEffect, progress: Float) {
    val enter = (progress / 0.18f).coerceIn(0f, 1f)
    val exit = ((1f - progress) / 0.18f).coerceIn(0f, 1f)
    val eased = 1f - (1f - enter) * (1f - enter) * (1f - enter)
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = 40.dp)
            .graphicsLayer {
                scaleX = 0.88f + 0.12f * eased
                scaleY = 0.88f + 0.12f * eased
                alpha = minOf(enter, exit)
            }
            .shadow(28.dp, RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = Indigo, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(effect.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
        if (effect.subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(effect.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
