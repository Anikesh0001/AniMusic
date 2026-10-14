package com.music.bitchord

import com.music.bitchord.data.stats.CustomSmartRule
import com.music.bitchord.data.stats.CustomSmartRules
import com.music.bitchord.data.stats.SmartField
import com.music.bitchord.data.stats.SmartRules
import com.music.bitchord.data.stats.TrackHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** The "make your own" smart playlist: the form's rule, and keeping it. */
class CustomSmartRulesTest {

    private val now = YearMonth.of(2026, 10)
    private val nowMs = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun rule(
        minPlays: Int = 0,
        idle: Int = 0,
        recent: Int = 0,
        skip: Int = 0,
        newMonths: Int = 0,
        order: SmartRules.Order = SmartRules.Order.MOST_PLAYED,
        limit: Int = 50,
    ) = SmartRules.customRule("k", minPlays, idle, recent, skip, newMonths, order, limit)

    private fun track(n: Int, plays: Int = 5, daysAgo: Int = 1, skips: Int = 0, first: YearMonth = YearMonth.of(2025, 1)) =
        TrackHistory(
            id = "vid" + n.toString().padStart(8, '0'), title = "Song $n", artist = "A", artistId = null, art = null,
            plays = plays, recentPlays = 0, skips = skips, lastMs = nowMs - daysAgo * day, firstMonth = first,
        )

    private fun pick(r: com.music.bitchord.data.stats.SmartRule, tracks: List<TrackHistory>) =
        SmartRules.evaluate(r, tracks, nowMs, now).map { it.title }

    @Test
    fun `an empty form selects everything that was played`() {
        val out = pick(rule(), (1..5).map { track(it, plays = it) })
        assertEquals(5, out.size)
    }

    @Test
    fun `minimum plays filters`() {
        val out = pick(rule(minPlays = 4), (1..6).map { track(it, plays = it) })
        assertEquals(listOf("Song 6", "Song 5", "Song 4"), out)
    }

    @Test
    fun `not played for N days and played in the last N days form one window`() {
        val tracks = listOf(track(1, daysAgo = 5), track(2, daysAgo = 40), track(3, daysAgo = 100))
        assertEquals(listOf("Song 3", "Song 2"), pick(rule(idle = 30, order = SmartRules.Order.LONGEST_SINCE), tracks))
        assertEquals(listOf("Song 1"), pick(rule(recent = 30), tracks))
        assertEquals(listOf("Song 2"), pick(rule(idle = 30, recent = 60), tracks))
    }

    @Test
    fun `a window that cannot be met matches nothing`() {
        assertTrue(pick(rule(idle = 90, recent = 30), listOf(track(1, daysAgo = 5), track(2, daysAgo = 200))).isEmpty())
    }

    @Test
    fun `skip percentage and first heard filter`() {
        val tracks = listOf(
            track(1, plays = 8, skips = 2),
            track(2, plays = 5, skips = 5, first = YearMonth.of(2026, 9)),
            track(3, plays = 5, first = YearMonth.of(2026, 9)),
        )
        assertEquals(listOf("Song 1", "Song 3"), pick(rule(skip = 30), tracks))
        assertEquals(listOf("Song 3"), pick(rule(skip = 30, newMonths = 2), tracks))
    }

    @Test
    fun `limit is applied and bounded`() {
        val tracks = (1..300).map { track(it, plays = 1 + it % 9) }
        assertEquals(25, pick(rule(limit = 25), tracks).size)
        assertEquals(200, pick(rule(limit = 5000), tracks).size)
    }

    @Test
    fun `a rule survives being saved and read back`() {
        val original = CustomSmartRule("ab12cd34", "Old loves", rule(minPlays = 5, idle = 60, skip = 25, order = SmartRules.Order.LONGEST_SINCE, limit = 75))
        val back = CustomSmartRules.decode(CustomSmartRules.encode(listOf(original, original.copy(id = "z", name = "Two")))).first()
        assertEquals("ab12cd34", back.id)
        assertEquals("Old loves", back.name)
        assertEquals(75, back.rule.limit)
        assertEquals(SmartField.DAYS_SINCE_LAST, back.rule.sortBy)
        assertEquals(original.rule.conditions, back.rule.conditions)
        assertEquals("smart_custom_ab12cd34", back.playlistId)
        assertEquals(1, back.rule.minTracks)
    }

    @Test
    fun `damaged or unknown rules are dropped without losing the rest`() {
        assertTrue(CustomSmartRules.decode("not json").isEmpty())
        val good = CustomSmartRules.encode(listOf(CustomSmartRule("a", "Good", rule(minPlays = 2))))
        val withBad = good.dropLast(1) + """,{"id":"b","name":"Bad","sort":"NO_SUCH_FIELD","conditions":[]}]"""
        val out = CustomSmartRules.decode(withBad)
        assertEquals(listOf("Good"), out.map { it.name })
        assertNull(out.firstOrNull { it.id == "b" })
    }
}
