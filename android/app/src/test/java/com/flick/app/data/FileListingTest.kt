package com.flick.app.data

import com.flick.app.core.DeviceFile
import com.flick.app.core.FileCategory
import com.flick.app.core.FileSort
import com.flick.app.core.SortKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FileListingTest {
    private fun file(name: String, size: Long = 1, modified: Long = 0) =
        DeviceFile("file:///$name", name, size, modified, "application/pdf", FileCategory.Documents)

    private val files = listOf(
        file("invoice_march.pdf", size = 300, modified = 20),
        file("holiday.jpg", size = 900, modified = 30),
        file("Report.docx", size = 100, modified = 10),
    )

    @Test
    fun sortsBySizeAscending() {
        val result = FileListing.apply(files, "", FileSort(SortKey.Size, descending = false))
        assertEquals(listOf("Report.docx", "invoice_march.pdf", "holiday.jpg"), result.map { it.name })
    }

    @Test
    fun sortsByNameIgnoringCase() {
        val result = FileListing.apply(files, "", FileSort(SortKey.Name, descending = false))
        assertEquals(listOf("holiday.jpg", "invoice_march.pdf", "Report.docx"), result.map { it.name })
    }

    @Test
    fun newestFirstByDefault() {
        val result = FileListing.apply(files, "", FileSort())
        assertEquals("holiday.jpg", result.first().name)
    }

    @Test
    fun findsAnExactPartOfTheName() {
        assertEquals(listOf("Report.docx"), FileListing.apply(files, "report", FileSort()).map { it.name })
    }

    @Test
    fun toleratesATypo() {
        assertEquals(listOf("invoice_march.pdf"), FileListing.apply(files, "invoce", FileSort()).map { it.name })
    }

    @Test
    fun dropsNamesThatAreNotClose() {
        assertEquals(emptyList<String>(), FileListing.apply(files, "zebra", FileSort()).map { it.name })
    }
}
