package com.flick.app.ui.effects

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderTimeTest {
    private val half = (PI / 2).toFloat()

    @Test
    fun runGoesFromRestToThePeakAndNeverBack() {
        assertEquals(-half, shaderTime(0f), 1e-4f)
        assertEquals(half, shaderTime(1f), 1e-4f)
        var previous = shaderTime(0f)
        for (i in 1..200) {
            val now = shaderTime(i / 200f)
            assertTrue("clock went back at step $i", now >= previous)
            previous = now
        }
    }

    @Test
    fun theClockIsLinear() {
        assertEquals(0f, shaderTime(0.5f), 1e-4f)
        assertEquals(shaderTime(0.5f) - shaderTime(0.25f), shaderTime(0.75f) - shaderTime(0.5f), 1e-4f)
    }

    @Test
    fun theSpeedMatchesTheReferenceVideo() {
        // Measured on the video: the shader clock advances at about 1.7 radians per second.
        val radiansPerSecond = (PI / (RISE_MS / 1000.0)).toFloat()
        assertTrue("speed was $radiansPerSecond", radiansPerSecond in 1.5f..1.9f)
    }
}
