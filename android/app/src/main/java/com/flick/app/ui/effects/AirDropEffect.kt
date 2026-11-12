package com.flick.app.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.app.ui.theme.Accent
import kotlin.math.pow

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

    /** When the last run ended, so a run is not started again right after another. */
    internal var endedAt = 0L

    /**
     * Starts a run. A call while a run is playing, or just after one, is ignored: the link state can
     * change several times in a second, and a run that restarts each time never reaches its end.
     */
    fun play(title: String, subtitle: String = "") {
        if (active || SystemClock.elapsedRealtime() - endedAt < COOLDOWN_MS) return
        this.title = title
        this.subtitle = subtitle
        active = true
        request++
    }
}

private const val COOLDOWN_MS = 1_500L

val LocalAirDrop = compositionLocalOf { AirDropEffect() }

/** The glow rises. */
internal const val RISE_MS = 1_400

/** The glow gathers at the notch and keeps getting brighter. */
internal const val ACCUMULATE_MS = 600

/** The wave strikes down from the notch and wipes the effect away behind it. */
internal const val STRIKE_MS = 450

internal const val TOTAL_MS = RISE_MS + ACCUMULATE_MS + STRIKE_MS

/** The four numbers the shader needs at one instant. See `AirDropShader.kt`. */
internal data class AirDropFrame(val rise: Float, val energy: Float, val accumulate: Float, val wave: Float)

private fun smoothStep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The timing of a run, for a clock that goes from 0 to 1. Everything only grows. The wave speeds up as it
 * falls, so it arrives with weight, and when it is gone nothing is left to fade.
 */
internal fun airDropFrame(clock: Float): AirDropFrame {
    val ms = clock * TOTAL_MS
    val strike = ((ms - (RISE_MS + ACCUMULATE_MS)) / STRIKE_MS).coerceIn(0f, 1f)
    val climb = (ms / RISE_MS).coerceIn(0f, 1f)
    return AirDropFrame(
        // The glow moves at a steady pace and slows only when it reaches the notch, so the climb is easy to follow.
        rise = 1f - (1f - climb).pow(1.6f),
        energy = smoothStep(0f, (RISE_MS + ACCUMULATE_MS).toFloat(), ms).pow(0.75f),
        accumulate = smoothStep(RISE_MS * 0.6f, (RISE_MS + ACCUMULATE_MS).toFloat(), ms),
        wave = strike.pow(2.2f),
    )
}

/** The animated clock of one run. */
private class Run {
    val clock = Animatable(0f)
}

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
    val run = remember { Run() }
    val context = LocalContext.current
    LaunchedEffect(effect.request) {
        if (effect.request == 0) return@LaunchedEffect
        vibrate(context, airDropHaptics())
        run.clock.snapTo(0f)
        run.clock.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        effect.active = false
        effect.endedAt = SystemClock.elapsedRealtime()
    }
    Box(modifier) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ShaderLayer(effect, run, content)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> BlurLayer(effect, run, content)
            else -> {
                content()
                if (effect.active) Spotlight(effect, run)
            }
        }
        foreground()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderLayer(effect: AirDropEffect, run: Run, content: @Composable () -> Unit) {
    val shader = remember { RuntimeShader(AIRDROP_AGSL) }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // The effect is removed when idle, so the screen is drawn normally the rest of the time.
            if (effect.active) {
                shader.setFloatUniform("size", size.width, size.height)
                val frame = airDropFrame(run.clock.value)
                shader.setFloatUniform("rise", frame.rise)
                shader.setFloatUniform("energy", frame.energy)
                shader.setFloatUniform("accumulate", frame.accumulate)
                shader.setFloatUniform("wave", frame.wave)
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
            } else {
                renderEffect = null
            }
        },
    ) {
        content()
        if (effect.active) Spotlight(effect, run)
    }
}

@Composable
private fun BlurLayer(effect: AirDropEffect, run: Run, content: @Composable () -> Unit) {
    val frame = airDropFrame(run.clock.value)
    val strength = if (effect.active) frame.energy * (1f - frame.wave) else 0f
    Box(Modifier.fillMaxSize().blur((18 * strength).dp)) {
        content()
        if (effect.active) Spotlight(effect, run)
    }
}

/** The file card. It grows in at the start, is crossed by the wave with everything else, then fades out. */
@Composable
private fun BoxScope.Spotlight(effect: AirDropEffect, run: Run) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = 56.dp)
            .graphicsLayer {
                val enter = (run.clock.value / 0.08f).coerceIn(0f, 1f)
                val eased = 1f - (1f - enter) * (1f - enter) * (1f - enter)
                scaleX = 0.88f + 0.12f * eased
                scaleY = 0.88f + 0.12f * eased
                val exit = ((1f - run.clock.value) / 0.14f).coerceIn(0f, 1f)
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
