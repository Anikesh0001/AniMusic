package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.TrackResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TrackResolverTest {

    private fun song(id: String, title: String, artist: String, duration: String? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = duration)

    private val catalogue = mapOf(
        "blinding lights" to listOf(
            song("karaoke", "Blinding Lights (Karaoke Version)", "Sing King"),
            song("real", "Blinding Lights", "The Weeknd", "3:20"),
        ),
        "levitating" to listOf(song("lev", "Levitating", "Dua Lipa")),
        "obscure" to listOf(song("other", "Something Else Entirely", "Nobody")),
    )

    private fun resolver(calls: AtomicInteger = AtomicInteger()) = TrackResolver(search = { query ->
        calls.incrementAndGet()
        catalogue.entries.firstOrNull { query.lowercase().startsWith(it.key) }?.value.orEmpty()
    })

    @Test
    fun picksTheMatcherBestRatherThanTheFirstHit() = runTest {
        val result = resolver().resolve(listOf(ImportTrack("Blinding Lights", "The Weeknd", durationMs = 200_000)))
        assertEquals("real", result.songs.single().videoId)
        assertTrue(result.rows.single().confident)
    }

    @Test
    fun keepsTheSourceOrder() = runTest {
        val result = resolver().resolve(
            listOf(
                ImportTrack("Levitating", "Dua Lipa"),
                ImportTrack("Blinding Lights", "The Weeknd"),
            ),
        )
        assertEquals(listOf("lev", "real"), result.songs.map { it.videoId })
    }

    @Test
    fun fallsBackToTheFirstHitButFlagsIt() = runTest {
        val result = resolver().resolve(listOf(ImportTrack("Obscure", "Unknown Band")))
        val row = result.rows.single()
        assertEquals("other", row.song?.videoId)
        assertFalse(row.confident)
        assertEquals(1, result.lowConfidence.size)
    }

    @Test
    fun reportsTracksWithNoResultsAsUnmatched() = runTest {
        val result = resolver().resolve(listOf(ImportTrack("Nothing Like This", "Ghost")))
        assertNull(result.rows.single().song)
        assertEquals("Nothing Like This", result.unmatched.single().title)
    }

    @Test
    fun searchesDuplicatesOnce() = runTest {
        val calls = AtomicInteger()
        val r = resolver(calls)
        val result = r.resolve(
            listOf(
                ImportTrack("Levitating", "Dua Lipa"),
                ImportTrack("levitating ", "DUA LIPA"),
            ),
        )
        assertEquals(2, result.songs.size)
        assertEquals(1, calls.get())
        // And the cache carries across calls.
        r.resolve(listOf(ImportTrack("Levitating", "Dua Lipa")))
        assertEquals(1, calls.get())
    }

    @Test
    fun reportsProgressUpToTheTotal() = runTest {
        var last = 0 to 0
        resolver().resolve(
            listOf(
                ImportTrack("Levitating", "Dua Lipa"),
                ImportTrack("Levitating", "Dua Lipa"),
                ImportTrack("Blinding Lights", "The Weeknd"),
            ),
        ) { done, total -> if (done > last.first) last = done to total }
        assertEquals(3 to 3, last)
    }
}
