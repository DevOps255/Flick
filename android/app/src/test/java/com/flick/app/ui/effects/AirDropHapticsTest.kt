package com.flick.app.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDropHapticsTest {
    private val pattern = airDropHaptics()

    @Test
    fun everyDurationHasItsStrength() {
        assertEquals(pattern.timings.size, pattern.amplitudes.size)
        assertTrue(pattern.amplitudes.all { it in 0..255 })
    }

    @Test
    fun theVibrationLastsAboutAsLongAsTheEffect() {
        val total = pattern.timings.sum()
        assertTrue("lasted $total ms", total in (TOTAL_MS - 400L)..(TOTAL_MS + 200L))
    }

    @Test
    fun theTapsGrowStrongerAsTheGlowClimbs() {
        val taps = pattern.amplitudes.filter { it > 0 }
        assertTrue("first tap too strong: ${taps.first()}", taps.first() < 100)
        assertEquals(taps.sorted(), taps)
    }

    @Test
    fun theStrikeIsFullStrength() {
        assertEquals(255, pattern.amplitudes.last())
    }
}
