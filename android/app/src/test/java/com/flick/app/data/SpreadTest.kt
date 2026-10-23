package com.flick.app.data

import com.flick.app.core.FileItem
import com.flick.app.data.simulated.spread
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadTest {
    private val files = listOf(FileItem("a", "a.jpg", 100), FileItem("b", "b.zip", 300))

    @Test
    fun firstFileFillsBeforeSecondStarts() {
        val result = spread(files, 150)
        assertEquals(listOf(100L, 50L), result.map { it.transferredBytes })
    }

    @Test
    fun everythingSentMarksAllFilesComplete() {
        assertEquals(listOf(1f, 1f), spread(files, 400).map { it.progress })
    }
}
