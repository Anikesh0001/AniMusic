package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import kotlin.random.Random

/** A Home row: songs to try, because of one the listener already plays. */
data class BecauseRow(val seed: TasteEntry, val songs: List<Song>)

/**
 * Picks what to build "Because you listened to …" rows from, and what goes in
 * them. The related songs themselves come from YouTube Music's radio for the
 * seed; this only chooses the seeds and keeps the answer worth showing.
 */
object Discovery {

    const val MAX_ROWS = 3
    const val PER_ROW = 12

    /** Fewer new songs than this and the row is not worth a place on Home. */
    const val MIN_ROW = 4

    /** A radio is full of the seed's own artist; two of them is enough. */
    const val PER_ARTIST = 2

    /** The seeds are drawn from this many of the best-scoring tracks. */
    private const val SEED_POOL = 15

    /**
     * Up to [count] tracks to build rows around, each by a different artist.
     *
     * Drawn from the best-scoring tracks with the date as the seed, so the rows
     * are the same all day and different tomorrow, and a favourite is likelier
     * than a track merely liked.
     */
    fun pickSeeds(entries: List<TasteEntry>, nowMs: Long, daySeed: Long, count: Int = MAX_ROWS): List<TasteEntry> {
        val best = entries
            .filter { it.title.isNotBlank() && it.artistKey.isNotEmpty() && TasteModel.isYouTubeId(it.id) }
            .map { it to DailyMix.score(it, nowMs) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .distinctBy { it.first.artistKey }
            .take(SEED_POOL)
        if (best.isEmpty()) return emptyList()
        val order = TasteModel.weightedOrder(
            weights = best.map { it.second },
            artists = best.map { it.first.artist },
            random = Random(daySeed),
        )
        return order.take(count).map { best[it].first }
    }

    /**
     * What to show from [related], YouTube's answer for [seed].
     *
     * Leaves out the seed, anything already [heard] or in another row
     * ([taken], which this adds to), and anything not playable from its id; keeps
     * at most [PER_ARTIST] per artist; and returns nothing at all when too few
     * are left to be worth a row.
     */
    fun recommend(
        related: List<Song>,
        seed: TasteEntry,
        heard: Set<String>,
        taken: MutableSet<String>,
        perRow: Int = PER_ROW,
    ): List<Song> {
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Song>()
        for (song in related) {
            if (out.size >= perRow) break
            if (song.videoId == seed.id || song.videoId in heard || song.videoId in taken) continue
            if (!TasteModel.isYouTubeId(song.videoId) || song.title.isBlank()) continue
            val artist = TasteModel.artistKey(song.artist)
            val n = perArtist.getOrDefault(artist, 0)
            if (n >= PER_ARTIST) continue
            perArtist[artist] = n + 1
            out += song
        }
        if (out.size < MIN_ROW) return emptyList()
        out.forEach { taken += it.videoId }
        return out
    }
}
