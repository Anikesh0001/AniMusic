package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.Discovery
import com.music.bitchord.data.stats.TasteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing what to seed "Because you listened to" rows from, and what they show. */
class DiscoveryTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun yt(n: Int) = "vid" + n.toString().padStart(8, '0')

    private fun entry(n: Int, artist: String = "Artist $n", fulls: Int = 5, skips: Int = 0) =
        TasteEntry(id = yt(n), title = "Song $n", artist = artist, fulls = fulls, skips = skips, last = now - day)

    private fun song(n: Int, artist: String = "Other $n") =
        Song(videoId = yt(n), title = "Song $n", artist = artist, thumbnailUrl = null)

    @Test
    fun `seeds are by different artists and no more than asked for`() {
        val seeds = Discovery.pickSeeds((1..20).map { entry(it, artist = "Artist ${it % 5}") }, now, 1)
        assertEquals(3, seeds.size)
        assertEquals(3, seeds.map { it.artistKey }.toSet().size)
    }

    @Test
    fun `no seeds without listening worth seeding from`() {
        assertTrue(Discovery.pickSeeds(emptyList(), now, 1).isEmpty())
        val skipped = (1..10).map { entry(it, fulls = 1, skips = 5) }
        assertTrue(Discovery.pickSeeds(skipped, now, 1).isEmpty())
    }

    @Test
    fun `the same day gives the same seeds and another day can differ`() {
        val entries = (1..30).map { entry(it, fulls = 3 + it % 4) }
        fun ids(seed: Long) = Discovery.pickSeeds(entries, now, seed).map { it.id }
        assertEquals(ids(7), ids(7))
        assertTrue((1L..30L).map(::ids).toSet().size > 1)
    }

    @Test
    fun `files and other services' tracks are never seeds`() {
        val local = (1..10).map { entry(it).copy(id = "content://media/$it") }
        assertTrue(Discovery.pickSeeds(local, now, 1).isEmpty())
    }

    @Test
    fun `a row leaves out the seed, what was heard and what another row took`() {
        val seed = entry(1)
        val related = (1..10).map { song(it) }
        val taken = mutableSetOf(yt(2))
        val out = Discovery.recommend(related, seed, heard = setOf(yt(3), yt(4)), taken = taken)
        assertEquals((5..10).map(::yt), out.map { it.videoId })
        assertTrue(taken.containsAll((5..10).map(::yt)))
    }

    @Test
    fun `an artist is limited and the row is capped`() {
        val one = (1..10).map { song(it, artist = "Same") } + (11..40).map { song(it) }
        val out = Discovery.recommend(one, entry(99), emptySet(), mutableSetOf())
        assertEquals(2, out.count { it.artist == "Same" })
        assertEquals(Discovery.PER_ROW, out.size)
    }

    @Test
    fun `too few new songs gives no row and takes nothing`() {
        val taken = mutableSetOf<String>()
        val out = Discovery.recommend((1..3).map { song(it) }, entry(99), emptySet(), taken)
        assertTrue(out.isEmpty())
        assertTrue(taken.isEmpty())
    }

    @Test
    fun `unplayable ids and untitled songs are dropped`() {
        val related = (1..8).map { song(it) } +
            Song(videoId = "content://x", title = "Local", artist = "A", thumbnailUrl = null) +
            Song(videoId = yt(50), title = "", artist = "B", thumbnailUrl = null)
        val out = Discovery.recommend(related, entry(99), emptySet(), mutableSetOf())
        assertEquals(8, out.size)
    }
}
