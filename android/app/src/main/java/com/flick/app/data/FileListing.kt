package com.flick.app.data

import com.flick.app.core.DeviceFile
import com.flick.app.core.FileSort
import com.flick.app.core.SortKey
import me.xdrop.fuzzywuzzy.FuzzySearch

/**
 * Searches and orders a list of files for the send screen.
 *
 * The search is fuzzy: a name that contains the typed text always matches, and a name that is close
 * to it matches too, so "invoce" still finds "invoice_march.pdf". The matching itself is done by the
 * FuzzyWuzzy library. Better matches come first, then the chosen order breaks ties.
 */
object FileListing {
    /** Lowest FuzzyWuzzy score, from 0 to 100, that still counts as a match. */
    const val MIN_SCORE = 70

    fun apply(files: List<DeviceFile>, query: String, sort: FileSort): List<DeviceFile> {
        val order = comparator(sort)
        val typed = query.trim().lowercase()
        if (typed.isEmpty()) return files.sortedWith(order)
        return files
            .map { it to score(typed, it.name.lowercase()) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith { a, b -> b.second.compareTo(a.second).takeIf { it != 0 } ?: order.compare(a.first, b.first) }
            .map { it.first }
    }

    private fun score(typed: String, name: String): Int =
        if (name.contains(typed)) 100 else FuzzySearch.partialRatio(typed, name)

    private fun comparator(sort: FileSort): Comparator<DeviceFile> {
        val base: Comparator<DeviceFile> = when (sort.key) {
            SortKey.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.Date -> compareBy { it.modifiedMs }
            SortKey.Size -> compareBy { it.sizeBytes }
        }
        return if (sort.descending) base.reversed() else base
    }
}
