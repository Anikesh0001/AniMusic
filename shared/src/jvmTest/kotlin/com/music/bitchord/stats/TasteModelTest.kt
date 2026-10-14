package com.music.bitchord

import com.music.bitchord.data.stats.TasteEntry
import com.music.bitchord.data.stats.TasteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The arithmetic behind Smart Shuffle: weights, ordering and artist spacing. */
class TasteModelTest {

    private fun entry(id: String, fulls: Int = 0, skips: Int = 0, artist: String = "") =
        TasteEntry(id = id, artist = artist, fulls = fulls, skips = skips)

    @Test
    fun `a track with no history is neutral`() {
        assertEquals(1.0, TasteModel.affinity(null), 1e-9)
        assertEquals(1.0, TasteModel.affinity(entry("a")), 1e-9)
    }

    @Test
    fun `listens raise the weight and skips lower it`() {
        val loved = TasteModel.affinity(entry("a", fulls = 20))
        val disliked = TasteModel.affinity(entry("b", skips = 20))
        assertTrue(loved > 1.5)
        assertTrue(disliked < 0.2)
        assertTrue(disliked >= TasteModel.MIN_WEIGHT)
    }

    @Test
    fun `one accident does not pin a track`() {
        val oneSkip = TasteModel.affinity(entry("a", skips = 1))
        val oneListen = TasteModel.affinity(entry("b", fulls = 1))
        assertTrue(oneSkip in 0.4..0.8)
        assertTrue(oneListen in 1.1..1.5)
    }

    @Test
    fun `a skip outweighs a listen`() {
        assertTrue(TasteModel.affinity(entry("a", fulls = 5, skips = 5)) < 1.0)
    }

    @Test
    fun `the order is a permutation of every track`() {
        val ids = List(50) { "t$it" }
        val order = TasteModel.smartOrder(ids, List(50) { "artist$it" }, { null }, Random(1))
        assertEquals(ids.indices.toList(), order.sorted())
    }

    @Test
    fun `the same seed gives the same order`() {
        val ids = List(30) { "t$it" }
        val artists = List(30) { "artist${it % 7}" }
        val a = TasteModel.smartOrder(ids, artists, { null }, Random(42))
        val b = TasteModel.smartOrder(ids, artists, { null }, Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `liked tracks lead more often than skipped ones`() {
        val ids = listOf("loved", "disliked")
        val artists = listOf("a", "b")
        val entries = mapOf(
            "loved" to entry("loved", fulls = 30),
            "disliked" to entry("disliked", skips = 30),
        )
        var lovedFirst = 0
        val runs = 2_000
        val random = Random(7)
        repeat(runs) {
            val order = TasteModel.smartOrder(ids, artists, { entries[it] }, random)
            if (order.first() == 0) lovedFirst++
        }
        // Weights are about 1.9 against 0.1, so the favourite should lead ~95%.
        assertTrue("loved led $lovedFirst of $runs", lovedFirst > runs * 0.85)
    }

    @Test
    fun `a disliked track can still come first`() {
        val ids = listOf("loved", "disliked")
        val entries = mapOf("disliked" to entry("disliked", skips = 30))
        val random = Random(3)
        val dislikedLed = (1..2_000).count {
            TasteModel.smartOrder(ids, listOf("a", "b"), { entries[it] }, random).first() == 1
        }
        assertTrue(dislikedLed > 0)
    }

    @Test
    fun `same artist back to back is rare`() {
        // Best effort, not a guarantee: near the end only one artist may be left.
        // Plain shuffle of this queue repeats an artist about 10 times in 39.
        val artists = List(40) { "artist${it % 4}" }
        val ids = List(40) { "t$it" }
        repeat(20) { seed ->
            val order = TasteModel.smartOrder(ids, artists, { null }, Random(seed))
            val repeats = order.zipWithNext().count { (x, y) -> artists[x] == artists[y] }
            assertTrue("seed $seed repeated an artist $repeats times", repeats <= 4)
        }
    }

    @Test
    fun `spacing separates neighbours when another artist is within reach`() {
        val artists = listOf("a", "a", "a", "b", "b")
        val out = TasteModel.spaceArtists(listOf(0, 1, 2, 3, 4), artists)
        assertEquals(listOf(0, 3, 1, 4, 2), out)
    }

    @Test
    fun `a single artist queue still plays`() {
        val order = TasteModel.smartOrder(List(5) { "t$it" }, List(5) { "same" }, { null }, Random(1))
        assertEquals(listOf(0, 1, 2, 3, 4), order.sorted())
    }

    @Test
    fun `artist spacing is case and whitespace insensitive`() {
        val out = TasteModel.spaceArtists(listOf(0, 1, 2), listOf("Daft Punk", " daft punk ", "Air"))
        assertEquals(listOf(0, 2, 1), out)
    }

    @Test
    fun `unknown artists are never treated as the same artist`() {
        val out = TasteModel.spaceArtists(listOf(0, 1, 2), listOf("", "", "x"))
        assertEquals(listOf(0, 1, 2), out)
    }

    @Test
    fun `empty and single queues are handled`() {
        assertEquals(emptyList<Int>(), TasteModel.smartOrder(emptyList(), emptyList(), { null }))
        assertEquals(listOf(0), TasteModel.smartOrder(listOf("a"), listOf("x"), { null }))
    }

    @Test
    fun `hours map to the four slots and weekends add four`() {
        assertEquals(0, TasteModel.slotOf(3, weekend = false))
        assertEquals(1, TasteModel.slotOf(6, weekend = false))
        assertEquals(1, TasteModel.slotOf(11, weekend = false))
        assertEquals(2, TasteModel.slotOf(12, weekend = false))
        assertEquals(3, TasteModel.slotOf(23, weekend = false))
        assertEquals(4, TasteModel.slotOf(0, weekend = true))
        assertEquals(7, TasteModel.slotOf(20, weekend = true))
    }
}
