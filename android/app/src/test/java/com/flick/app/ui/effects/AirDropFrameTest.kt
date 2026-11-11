package com.flick.app.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDropFrameTest {
    private val strikeStart = (RISE_MS + ACCUMULATE_MS).toFloat() / TOTAL_MS

    @Test
    fun startsCleanAndEndsWithTheWaveGone() {
        val first = airDropFrame(0f)
        assertEquals(0f, first.rise, 1e-4f)
        assertEquals(0f, first.energy, 1e-4f)
        assertEquals(0f, first.wave, 1e-4f)
        val last = airDropFrame(1f)
        assertEquals(1f, last.rise, 1e-4f)
        assertEquals(1f, last.energy, 1e-4f)
        assertEquals(1f, last.wave, 1e-4f)
    }

    @Test
    fun nothingEverMovesBack() {
        var before = airDropFrame(0f)
        for (i in 1..400) {
            val now = airDropFrame(i / 400f)
            assertTrue("rise went back at $i", now.rise >= before.rise)
            assertTrue("energy went back at $i", now.energy >= before.energy)
            assertTrue("accumulate went back at $i", now.accumulate >= before.accumulate)
            assertTrue("wave went back at $i", now.wave >= before.wave)
            before = now
        }
    }

    @Test
    fun theWaveWaitsForTheGlowToGatherAtTheNotch() {
        assertEquals(0f, airDropFrame(strikeStart - 0.01f).wave, 1e-4f)
        // The glow is fully up and the notch fully lit when the wave starts.
        assertEquals(1f, airDropFrame(strikeStart).rise, 1e-4f)
        assertEquals(1f, airDropFrame(strikeStart).accumulate, 1e-4f)
    }

    @Test
    fun theWaveSpeedsUpAsItFalls() {
        val halfWay = airDropFrame(strikeStart + (1f - strikeStart) / 2f).wave
        assertTrue("wave was $halfWay at half the strike time", halfWay < 0.3f)
    }
}
