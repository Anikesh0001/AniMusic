package com.music.bitchord

import com.music.bitchord.data.stats.Condition
import com.music.bitchord.data.stats.SmartField
import com.music.bitchord.data.stats.SmartRule
import com.music.bitchord.data.stats.SmartRules
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import com.music.bitchord.data.stats.TrackHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Which tracks the built-in smart playlists pick, and how history is folded for them. */
class SmartRulesTest {

    private val now = YearMonth.of(2026, 10)
    private val nowMs = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun yt(n: Int) = "vid" + n.toString().padStart(8, '0')

    private fun track(
        n: Int,
        plays: Int = 10,
        recent: Int = 0,
        skips: Int = 0,
        daysAgo: Int = 1,
        firstMonth: YearMonth = YearMonth.of(2025, 1),
        title: String = "Song $n",
    ) = TrackHistory(
        id = yt(n), title = title, artist = "Artist", artistId = null, art = null,
        plays = plays, recentPlays = recent, skips = skips, lastMs = nowMs - daysAgo * day, firstMonth = firstMonth,
    )

    private fun ids(list: List<TrackHistory>) = list.map { it.id }

    @Test
    fun `on repeat takes recent heavy listens and ranks by them`() {
        val tracks = (1..10).map { track(it, recent = it, daysAgo = 2) } +
            track(50, recent = 20, daysAgo = 30) + // played a lot, but not lately enough
            track(51, recent = 1, daysAgo = 1) // too few recent plays
        val out = SmartRules.evaluate(SmartRules.ON_REPEAT, tracks, nowMs, now)
        // Needs RECENT_PLAYS >= 3, so tracks 3..10 qualify: exactly the minimum of 8.
        assertEquals(listOf(10, 9, 8, 7, 6, 5, 4, 3).map(::yt), ids(out))
    }

    @Test
    fun `rediscover wants old favourites that were not skipped`() {
        val old = (1..12).map { track(it, plays = 20 - it, daysAgo = 90) }
        val tooRecent = track(60, plays = 50, daysAgo = 10)
        val skipped = track(61, plays = 8, skips = 8, daysAgo = 120)
        val tooFew = track(62, plays = 4, daysAgo = 120)
        val out = SmartRules.evaluate(SmartRules.REDISCOVER, old + tooRecent + skipped + tooFew, nowMs, now)
        assertEquals((1..12).map(::yt), ids(out))
    }

    @Test
    fun `new favourites are recent finds that have been played on`() {
        val fresh = (1..9).map { track(it, plays = 3 + it, firstMonth = YearMonth.of(2026, 9)) }
        val old = track(70, plays = 99, firstMonth = YearMonth.of(2024, 1))
        val barelyPlayed = track(71, plays = 1, firstMonth = YearMonth.of(2026, 10))
        val out = SmartRules.evaluate(SmartRules.NEW_FAVORITES, fresh + old + barelyPlayed, nowMs, now)
        assertEquals((9 downTo 1).map(::yt), ids(out))
    }

    @Test
    fun `a playlist is hidden until enough tracks qualify`() {
        val few = (1..9).map { track(it, plays = 9) }
        assertTrue(SmartRules.evaluate(SmartRules.FAVORITES, few, nowMs, now).isEmpty())
        assertEquals(10, SmartRules.evaluate(SmartRules.FAVORITES, few + track(10), nowMs, now).size)
    }

    @Test
    fun `the limit is respected`() {
        val many = (1..300).map { track(it, plays = 5 + it % 7) }
        assertEquals(100, SmartRules.evaluate(SmartRules.FAVORITES, many, nowMs, now).size)
    }

    @Test
    fun `ties break the same way every time`() {
        val tied = (1..12).map { track(it, plays = 7, title = "T${13 - it}") }
        val a = SmartRules.evaluate(SmartRules.FAVORITES, tied, nowMs, now)
        val b = SmartRules.evaluate(SmartRules.FAVORITES, tied.shuffled(), nowMs, now)
        assertEquals(ids(a), ids(b))
        assertEquals("T1", a.first().title)
    }

    @Test
    fun `tracks that are not on YouTube are never selected`() {
        val local = (1..20).map { track(it).copy(id = "content://media/$it") }
        assertTrue(SmartRules.evaluate(SmartRules.FAVORITES, local, nowMs, now).isEmpty())
    }

    @Test
    fun `conditions can be open at either end and combined`() {
        val rule = SmartRule(
            key = "x",
            conditions = listOf(Condition(SmartField.PLAYS, min = 5.0, max = 8.0)),
            sortBy = SmartField.PLAYS,
            descending = false,
            minTracks = 1,
        )
        val out = SmartRules.evaluate(rule, (1..12).map { track(it, plays = it) }, nowMs, now)
        assertEquals(listOf(5, 6, 7, 8).map(::yt), ids(out))
    }

    // ── Folding the monthly files ───────────────────────────────────────────

    private fun entry(n: Int, plays: Int, last: Long, title: String = "Song $n", art: String? = null) =
        TrackEntry(id = yt(n), title = title, artist = "Artist", art = art, plays = plays, last = last)

    @Test
    fun `plays add up across months and recent plays cover this month and the last`() {
        val buckets = listOf(
            StoredBucket(month = "2026-07", tracks = listOf(entry(1, 4, 100))),
            StoredBucket(month = "2026-09", tracks = listOf(entry(1, 2, 300))),
            StoredBucket(month = "2026-10", tracks = listOf(entry(1, 1, 500))),
        )
        val h = SmartRules.histories(buckets, now) { 0 }.single()
        assertEquals(7, h.plays)
        assertEquals(3, h.recentPlays)
        assertEquals(500L, h.lastMs)
        assertEquals(YearMonth.of(2026, 7), h.firstMonth)
    }

    @Test
    fun `the latest month's art wins but an older one fills a gap`() {
        val buckets = listOf(
            StoredBucket(month = "2026-08", tracks = listOf(entry(1, 1, 1, art = "old.jpg"))),
            StoredBucket(month = "2026-10", tracks = listOf(entry(1, 1, 2, art = null, title = "Renamed"))),
        )
        val h = SmartRules.histories(buckets, now) { 0 }.single()
        assertEquals("Renamed", h.title)
        assertEquals("old.jpg", h.art)
    }

    @Test
    fun `skips come from the supplied source and unreadable months are ignored`() {
        val buckets = listOf(
            StoredBucket(month = "not-a-month", tracks = listOf(entry(2, 5, 1))),
            StoredBucket(month = "2026-10", tracks = listOf(entry(1, 5, 1))),
        )
        val out = SmartRules.histories(buckets, now) { id -> if (id == yt(1)) 3 else 0 }
        assertEquals(1, out.size)
        assertEquals(3, out.single().skips)
    }
}
