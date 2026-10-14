package com.music.bitchord.data.stats

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.random.Random

/**
 * What this device has learned about one track: how often it was heard through,
 * how often it was left within seconds, and when in the day it tends to play.
 *
 * Kept apart from [ListeningStats] on purpose. That store is monthly totals for
 * the Replay page and has no per-track time of day and no notion of a skip, and
 * changing its file format would put every existing install's history at risk
 * for the sake of a feature that only needs a few counters per track.
 *
 * The title, artist and art are here so a mix can be drawn from this alone,
 * without looking each track up again.
 */
@Serializable
data class TasteEntry(
    val id: String,
    val title: String = "",
    /** Display name; [artistKey] is what compares. */
    val artist: String = "",
    val artistId: String? = null,
    val art: String? = null,
    /** Plays that counted as listened to. */
    var fulls: Int = 0,
    /** Left within the first [TasteModel.SKIP_MS] of a track that was played. */
    var skips: Int = 0,
    /** [fulls] split by [TasteModel.slotOf]; always [TasteModel.SLOT_COUNT] long. */
    var slots: List<Int> = List(TasteModel.SLOT_COUNT) { 0 },
    var last: Long = 0L,
) {
    val artistKey: String get() = TasteModel.artistKey(artist)
}

/**
 * The arithmetic behind Smart Shuffle and the mixes. No Android and no storage
 * in here, so it can be tested on the JVM and reused as it is.
 *
 * ## Why a ratio, and why it is smoothed
 *
 * The signal is "of the times this track came up, how often did I stay". Raw,
 * that is 1/1 for a track heard once and 0/1 for a track skipped once, and one
 * accident would then pin a track at the top or bottom of every shuffle. Adding
 * one pretend full listen and one pretend skip to every track pulls a thin
 * history toward the middle, and lets a long one speak for itself.
 *
 * A skip counts double a listen. Leaving a song is a deliberate act; letting it
 * run is also what happens when the phone is in a pocket.
 */
object TasteModel {

    /** Left inside half a minute, a track was skipped however it ended. */
    const val SKIP_MS = 30_000L

    /** Four times of day, on weekdays and at weekends. */
    const val SLOT_COUNT = 8

    /** A track with no history weighs this, so unknown tracks are not buried. */
    const val NEUTRAL = 1.0

    /** The lightest a track can weigh: unlikely to lead, never impossible. */
    const val MIN_WEIGHT = 0.1

    /** How far down the remaining tracks to look for one by a different artist. */
    private const val SPACING_LOOKAHEAD = 8

    /** 0 night, 1 morning, 2 afternoon, 3 evening — plus 4 at weekends. */
    fun slotOf(hour: Int, weekend: Boolean): Int {
        val time = when (hour) {
            in 6..11 -> 1
            in 12..17 -> 2
            in 18..23 -> 3
            else -> 0
        }
        return if (weekend) time + 4 else time
    }

    fun artistKey(artist: String): String = artist.trim().lowercase()

    /**
     * How much to favour [entry], around [NEUTRAL]: 1.0 with no history, toward
     * 2.0 for a track nearly always heard through, toward [MIN_WEIGHT] for one
     * nearly always skipped.
     */
    fun affinity(entry: TasteEntry?): Double {
        if (entry == null) return NEUTRAL
        val kept = entry.fulls + 1.0
        val left = entry.skips * 2.0 + 1.0
        return (2.0 * kept / (kept + left)).coerceAtLeast(MIN_WEIGHT)
    }

    /**
     * Where [ids] should go: indices into the list, best-first, in an order
     * that is random but leans the way [entryOf] says.
     *
     * Each track gets the key `ln(u) / weight` for a uniform `u`, and the tracks
     * are sorted by it, largest first. That is weighted sampling without
     * replacement (Efraimidis and Spirakis, 2006): a track twice as heavy is
     * twice as likely to go first, and nothing is ever excluded. It needs one
     * pass and no rescaling, which is why it is used rather than drawing from a
     * shrinking pool.
     *
     * A second pass then keeps one artist from playing twice in a row where it
     * can. Pure randomness does that more often than it feels right, and
     * hearing the same artist back to back is what people mean when they say a
     * shuffle is not random.
     */
    fun smartOrder(
        ids: List<String>,
        artists: List<String>,
        entryOf: (String) -> TasteEntry?,
        random: Random = Random.Default,
    ): List<Int> {
        require(ids.size == artists.size) { "one artist per track" }
        val keyed = ids.indices.map { i ->
            // u is kept off zero: ln(0) is -infinity, which would pin the track last.
            val u = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
            i to ln(u) / affinity(entryOf(ids[i]))
        }.sortedByDescending { it.second }.map { it.first }
        return spaceArtists(keyed, artists)
    }

    /**
     * [order] with neighbours by the same artist pulled apart where possible.
     *
     * Greedy and local: each slot takes the first of the next few tracks that
     * differs from the one before it, and falls back to the head when none does
     * (an album queue, say). It never moves a track far from where chance put
     * it, so it does not undo the weighting.
     */
    internal fun spaceArtists(order: List<Int>, artists: List<String>): List<Int> {
        val pool = ArrayList(order)
        val out = ArrayList<Int>(order.size)
        var previous: String? = null
        while (pool.isNotEmpty()) {
            val window = minOf(SPACING_LOOKAHEAD, pool.size)
            var pick = 0
            for (k in 0 until window) {
                val key = artistKey(artists[pool[k]])
                if (key.isEmpty() || key != previous) {
                    pick = k
                    break
                }
            }
            val index = pool.removeAt(pick)
            out += index
            previous = artistKey(artists[index]).ifEmpty { null }
        }
        return out
    }
}
