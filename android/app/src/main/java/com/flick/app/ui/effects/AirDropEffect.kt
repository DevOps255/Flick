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
import com.flick.app.ui.theme.Accent
import kotlin.math.PI
import kotlin.math.sin

/**
 * Starts the AirDrop effect. Any screen can call [play].
 *
 * It plays when the link to the paired phone is really up, not when a gesture is merely detected,
 * and again when the file is sent. The file card is drawn inside the effect, so the wave crosses it too.
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

/** The shader's clock is at rest at [START] and again at [END]. The wave exists between 0 and PI. */
private const val START = (-PI / 2).toFloat()
private const val END = (3 * PI / 2).toFloat()

/**
 * How long one full run lasts. In the reference video the shader clock advances at about 1.7 radians
 * per second, so the full sine cycle of 2 PI radians takes about 3.7 seconds: the wave appears
 * about a second in, strikes at about 1.85 seconds, then everything fades.
 */
internal const val DURATION_MS = 3_700

/**
 * The shader time for a clock value from 0 to 1. The clock is linear, as in the reference video:
 * no easing is added, so the shader's own sine decides how the wave builds up and strikes.
 */
internal fun shaderTime(clock: Float): Float = START + (END - START) * clock

/**
 * Draws [content] with the effect. The file card is part of the content while the effect plays, so
 * the wave crosses it. [foreground] is drawn above everything and is never touched by the effect:
 * dialogs, banners and the receiving card.
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
    val clock = remember { Animatable(0f) }
    LaunchedEffect(effect.request) {
        if (effect.request == 0) return@LaunchedEffect
        effect.active = true
        clock.snapTo(0f)
        clock.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        effect.active = false
    }
    Box(modifier) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ShaderLayer(effect, clock, content)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> BlurLayer(effect, clock, content)
            else -> {
                content()
                if (effect.active) Spotlight(effect, clock)
            }
        }
        foreground()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderLayer(effect: AirDropEffect, clock: Animatable<Float, *>, content: @Composable () -> Unit) {
    val shader = remember { RuntimeShader(AIRDROP_AGSL) }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // The effect is removed when idle, so the screen is drawn normally the rest of the time.
            if (effect.active) {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("time", shaderTime(clock.value))
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
            } else {
                renderEffect = null
            }
        },
    ) {
        content()
        if (effect.active) Spotlight(effect, clock)
    }
}

@Composable
private fun BlurLayer(effect: AirDropEffect, clock: Animatable<Float, *>, content: @Composable () -> Unit) {
    val strength = if (effect.active) (1f + sin(shaderTime(clock.value))) * 0.5f else 0f
    Box(Modifier.fillMaxSize().blur((18 * strength).dp)) {
        content()
        if (effect.active) Spotlight(effect, clock)
    }
}

/** The file card. It grows in at the start, is crossed by the wave with everything else, then fades out. */
@Composable
private fun BoxScope.Spotlight(effect: AirDropEffect, clock: Animatable<Float, *>) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = 56.dp)
            .graphicsLayer {
                val enter = (clock.value / 0.06f).coerceIn(0f, 1f)
                val exit = ((1f - clock.value) / 0.12f).coerceIn(0f, 1f)
                val eased = 1f - (1f - enter) * (1f - enter) * (1f - enter)
                scaleX = 0.88f + 0.12f * eased
                scaleY = 0.88f + 0.12f * eased
                alpha = minOf(enter, exit)
            }
            .shadow(20.dp, RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = Accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(8.dp))
        Text(effect.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
        if (effect.subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(effect.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
