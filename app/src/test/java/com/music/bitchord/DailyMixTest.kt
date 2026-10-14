package com.music.bitchord

import com.music.bitchord.data.stats.DailyMix
import com.music.bitchord.data.stats.TasteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking and splitting tracks into the Daily Mixes. */
class DailyMixTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun track(
        id: String,
        artist: String,
        fulls: Int = 5,
        skips: Int = 0,
        daysAgo: Int = 1,
        title: String = "Song $id",
    ) = TasteEntry(id = id, title = title, artist = artist, fulls = fulls, skips = skips, last = now - daysAgo * day)

    /** [artists] artists with [each] well-liked tracks apiece. */
    private fun library(artists: Int, each: Int) = (0 until artists).flatMap { a ->
        (0 until each).map { t -> track("a${a}t$t", "Artist $a", fulls = (60 - a).coerceAtLeast(1)) }
    }

    @Test
    fun `too little listening makes no mixes`() {
        assertTrue(DailyMix.build(library(2, 3), now, 1).isEmpty())
    }

    @Test
    fun `tracks never finished or mostly skipped are left out`() {
        assertEquals(0.0, DailyMix.score(track("x", "A", fulls = 0, skips = 3), now), 0.0)
        assertEquals(0.0, DailyMix.score(track("x", "A", fulls = 2, skips = 5), now), 0.0)
        assertTrue(DailyMix.score(track("x", "A", fulls = 2, skips = 1), now) > 0.0)
    }

    @Test
    fun `more listens and recent listens score higher`() {
        val few = DailyMix.score(track("a", "A", fulls = 2), now)
        val many = DailyMix.score(track("b", "A", fulls = 40), now)
        val recent = DailyMix.score(track("c", "A", fulls = 10, daysAgo = 1), now)
        val stale = DailyMix.score(track("d", "A", fulls = 10, daysAgo = 200), now)
        assertTrue(many > few)
        assertTrue(recent > stale)
        assertTrue("old favourites fade but stay", stale > 0.0)
    }

    @Test
    fun `mix count follows the number of artists and is capped`() {
        assertEquals(1, DailyMix.build(library(3, 6), now, 1).size)
        assertEquals(2, DailyMix.build(library(6, 6), now, 1).size)
        assertEquals(DailyMix.MAX_MIXES, DailyMix.build(library(30, 6), now, 1).size)
    }

    @Test
    fun `every artist lands in exactly one mix`() {
        val mixes = DailyMix.build(library(12, 6), now, 1)
        val artists = mixes.flatMap { it.artists }
        assertEquals(artists.size, artists.toSet().size)
        assertEquals(12, artists.size)
    }

    @Test
    fun `the first mix holds the favourite artist`() {
        val mixes = DailyMix.build(library(9, 6), now, 1)
        assertEquals("Artist 0", mixes.first().artists.first())
    }

    @Test
    fun `a mix never repeats a track and stays within its size`() {
        DailyMix.build(library(12, 20), now, 1).forEach { mix ->
            assertTrue(mix.songs.size <= DailyMix.TRACKS_PER_MIX)
            assertEquals(mix.songs.size, mix.songs.map { it.videoId }.toSet().size)
        }
    }

    @Test
    fun `no track appears in two mixes`() {
        val all = DailyMix.build(library(12, 10), now, 1).flatMap { m -> m.songs.map { it.videoId } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `the same day gives the same mix`() {
        val a = DailyMix.build(library(9, 12), now, 5).map { m -> m.songs.map { it.videoId } }
        val b = DailyMix.build(library(9, 12), now, 5).map { m -> m.songs.map { it.videoId } }
        assertEquals(a, b)
    }

    @Test
    fun `a different day gives a different selection`() {
        val a = DailyMix.build(library(9, 12), now, 5).map { m -> m.songs.map { it.videoId } }
        val b = DailyMix.build(library(9, 12), now, 6).map { m -> m.songs.map { it.videoId } }
        assertNotEquals(a, b)
    }

    @Test
    fun `tracks without a title or an artist cannot be played or shown`() {
        val junk = (0 until 20).map { track("j$it", "", title = "T$it") } +
            (0 until 20).map { track("k$it", "Artist", title = "") }
        assertTrue(DailyMix.build(junk, now, 1).isEmpty())
    }

    @Test
    fun `songs carry what the player needs`() {
        val song = DailyMix.build(library(9, 6), now, 1).first().songs.first()
        assertTrue(song.videoId.startsWith("a"))
        assertFalse(song.title.isBlank())
        assertFalse(song.artist.isBlank())
    }

    // ── Daylist ─────────────────────────────────────────────────────────────

    private val morning = com.music.bitchord.data.stats.TasteModel.slotOf(8, weekend = false)

    /** A track played [count] times in [slot] and nowhere else. */
    private fun played(id: String, slot: Int, count: Int = 5, artist: String = "Artist $id") =
        track(id, artist, fulls = count).also { e ->
            e.slots = List(com.music.bitchord.data.stats.TasteModel.SLOT_COUNT) { if (it == slot) count else 0 }
        }

    @Test
    fun `no daylist without enough listening at this hour`() {
        // Plenty of tracks, none of them ever played in the morning.
        val night = com.music.bitchord.data.stats.TasteModel.slotOf(2, weekend = false)
        val entries = (0 until 20).map { played("n$it", night) }
        assertEquals(null, DailyMix.buildDaylist(entries, now, hour = 8, weekend = false, daySeed = 1))
    }

    @Test
    fun `morning favourites lead in the morning and not at night`() {
        val night = com.music.bitchord.data.stats.TasteModel.slotOf(2, weekend = false)
        val entries = (0 until 15).map { played("m$it", morning) } + (0 until 15).map { played("n$it", night) }
        val am = DailyMix.buildDaylist(entries, now, hour = 8, weekend = false, daySeed = 1)!!
        val pm = DailyMix.buildDaylist(entries, now, hour = 2, weekend = false, daySeed = 1)!!
        val amMorning = am.songs.take(10).count { it.videoId.startsWith("m") }
        val pmNight = pm.songs.take(10).count { it.videoId.startsWith("n") }
        assertTrue("morning list led with $amMorning morning tracks of 10", amMorning >= 8)
        assertTrue("night list led with $pmNight night tracks of 10", pmNight >= 8)
        assertEquals(morning, am.slot)
        assertEquals(-1, am.index)
    }

    @Test
    fun `a daylist is stable within a slot and changes with the day`() {
        val entries = (0 until 20).map { played("m$it", morning) }
        fun ids(seed: Long, hour: Int) =
            DailyMix.buildDaylist(entries, now, hour, weekend = false, daySeed = seed)!!.songs.map { it.videoId }
        assertEquals(ids(3, 7), ids(3, 10))
        assertNotEquals(ids(3, 7), ids(4, 7))
    }

    @Test
    fun `the other day type counts for half`() {
        val weekendMorning = com.music.bitchord.data.stats.TasteModel.slotOf(8, weekend = true)
        val e = played("w", weekendMorning, count = 4)
        assertEquals(2.0, DailyMix.timePlays(e, morning), 1e-9)
        assertEquals(4.0, DailyMix.timePlays(e, weekendMorning), 1e-9)
    }

    @Test
    fun `a daylist has no more than the requested size and repeats nothing`() {
        val entries = (0 until 60).map { played("m$it", morning) }
        val list = DailyMix.buildDaylist(entries, now, hour = 8, weekend = false, daySeed = 1)!!
        assertTrue(list.songs.size <= DailyMix.DAYLIST_TRACKS)
        assertEquals(list.songs.size, list.songs.map { it.videoId }.toSet().size)
    }
}
