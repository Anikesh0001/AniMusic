package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.ResolveResult
import com.music.bitchord.data.importer.ResolvedTrack
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.ReviewRow
import com.music.bitchord.ui.components.needsReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportReviewTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null)
    private fun row(title: String, song: Song?, confident: Boolean) =
        ResolvedTrack(ImportTrack(title, "A"), song, confident)

    @Test
    fun onlyGuessesAndMissesNeedReview() {
        assertFalse(row("sure", song("1"), true).needsReview())
        assertTrue(row("guess", song("2"), false).needsReview())
        assertTrue(row("missing", null, false).needsReview())
    }

    @Test
    fun savingKeepsOrderAndDropsRemovedRows() {
        val rows = listOf(
            ReviewRow(row("a", song("1"), true)),
            ReviewRow(row("b", song("2"), false), removed = true),
            ReviewRow(row("c", null, false)),
            ReviewRow(row("d", song("4"), true)),
        )
        val saved = ResolveResult(rows.filterNot { it.removed }.map { it.resolved })
        assertEquals(listOf("1", "4"), saved.songs.map { it.videoId })
        assertEquals(listOf("c"), saved.unmatched.map { it.title })
    }
}
