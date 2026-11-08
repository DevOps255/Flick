package com.flick.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role

/**
 * Motion values shared by every screen.
 *
 * Entering elements use a strong ease-out so they react at once. Anything the
 * user can grab or redirect uses a critically damped spring, which keeps its
 * velocity when interrupted. Nothing in the UI should run longer than 300 ms.
 */
object Motion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    fun <T> enter(durationMs: Int = 200): FiniteAnimationSpec<T> = tween(durationMs, easing = EaseOut)

    fun <T> settle(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** True when the user turned system animations off. Decorative loops must stop then. */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Shrinks slightly while pressed, so the control feels like it heard the touch. */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.enter(120),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Clickable without a ripple, with the press scale instead. */
fun Modifier.pressable(onClick: () -> Unit, pressedScale: Float = 0.97f): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    pressScale(source, pressedScale).clickable(
        interactionSource = source,
        indication = null,
        role = Role.Button,
        onClick = onClick,
    )
}
