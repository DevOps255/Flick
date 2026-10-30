package com.flick.app.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Starts the NameDrop wave. Any screen can call [play]; the wave crosses the whole app.
 *
 * It is played at two moments: when the file is selected and the other phone is ready
 * (both phones are in sync), and again when the send gesture is made. The receiving
 * phone plays it when the file starts arriving.
 */
@Stable
class NameDropWave {
    internal var request by mutableIntStateOf(0)
    internal var pulses by mutableIntStateOf(1)
    internal var active by mutableStateOf(false)

    /** Plays [pulses] waves, one second each. */
    fun play(pulses: Int = 1) {
        this.pulses = pulses
        request++
    }
}

val LocalNameDrop = compositionLocalOf { NameDropWave() }

/**
 * Wraps the app and applies the wave to everything inside while it plays.
 * From Android 13 the real shader runs. On older versions a simple ring
 * leaves the top edge instead, because AGSL does not exist there.
 */
@Composable
fun NameDropHost(wave: NameDropWave, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val time = remember { Animatable(0f) }
    LaunchedEffect(wave.request) {
        if (wave.request == 0) return@LaunchedEffect
        wave.active = true
        time.snapTo(0f)
        time.animateTo(wave.pulses.toFloat(), tween(durationMillis = wave.pulses * 1000, easing = LinearEasing))
        wave.active = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderHost(wave, time, modifier, content)
    } else {
        RingHost(wave, time, modifier, content)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderHost(wave: NameDropWave, time: Animatable<Float, *>, modifier: Modifier, content: @Composable () -> Unit) {
    val shader = remember { RuntimeShader(NAME_DROP_AGSL) }
    Box(
        modifier.graphicsLayer {
            // The effect is removed when idle so the screen is drawn normally the rest of the time.
            renderEffect = if (wave.active) {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("time", time.value)
                shader.setFloatUniform("waveScale", WAVE_SCALE)
                shader.setFloatUniform("sharpness", WAVE_SHARPNESS)
                shader.setFloatUniform("spread", WAVE_SPREAD)
                RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
            } else {
                null
            }
        },
    ) { content() }
}

@Composable
private fun RingHost(wave: NameDropWave, time: Animatable<Float, *>, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        if (wave.active) {
            Canvas(Modifier.fillMaxSize()) {
                val front = (time.value - kotlin.math.floor(time.value)) * 0.5f
                drawCircle(
                    Color.White.copy(alpha = 0.6f * (1f - front * 2f)),
                    radius = size.height * front,
                    center = Offset(size.width / 2, -size.height * 0.05f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

// Defaults of the original app.
private const val WAVE_SCALE = 10f
private const val WAVE_SHARPNESS = 0.8f
private const val WAVE_SPREAD = 0.1f
