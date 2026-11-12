package com.flick.app.ui.effects

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** A vibration as the system wants it: alternating off and on durations in milliseconds, and a strength from 0 to 255 for each. */
internal class HapticPattern(val timings: LongArray, val amplitudes: IntArray)

/** How many taps the glow makes while it climbs, and while it gathers. */
private const val RISE_TAPS = 16
private const val GATHER_TAPS = 12

/**
 * The vibration of one run, matched to the effect. While the glow climbs, short taps grow stronger,
 * like something filling up. While it gathers at the notch the taps come closer and reach full strength.
 * When the wave strikes there is one long, full strength buzz.
 */
internal fun airDropHaptics(): HapticPattern {
    val timings = ArrayList<Long>()
    val amplitudes = ArrayList<Int>()

    fun tap(offMs: Long, onMs: Long, strength: Int) {
        timings += offMs
        amplitudes += 0
        timings += onMs
        amplitudes += strength
    }

    for (i in 0 until RISE_TAPS) {
        val share = i / (RISE_TAPS - 1f)
        val period = RISE_MS / RISE_TAPS
        tap(offMs = (period - 24).toLong(), onMs = 24, strength = (60 + 150 * share).toInt())
    }
    for (i in 0 until GATHER_TAPS) {
        val share = i / (GATHER_TAPS - 1f)
        val period = ACCUMULATE_MS / GATHER_TAPS
        tap(offMs = (period - 34).toLong(), onMs = 34, strength = (210 + 45 * share).toInt())
    }
    tap(offMs = 0, onMs = 150, strength = 255)
    return HapticPattern(timings.toLongArray(), amplitudes.toIntArray())
}

/** Plays [pattern]. A phone without a vibration motor, or without permission to use it, is skipped. */
internal fun vibrate(context: Context, pattern: HapticPattern) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return
    if (!vibrator.hasVibrator()) return
    val effect = if (vibrator.hasAmplitudeControl()) {
        VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
    } else {
        VibrationEffect.createWaveform(pattern.timings, -1)
    }
    runCatching { vibrator.vibrate(effect) }
}
