package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/** One generated mix: who it is built around, and what is in it. */
data class Mix(
    /** 0-based; the first mix is built around the artists heard most. */
    val index: Int,
    /** The artists it is built around, most prominent first. */
    val artists: List<String>,
    val songs: List<Song>,
) {
    val art: String? get() = songs.firstNotNullOfOrNull { it.thumbnailUrl }
}

/**
 * Splits what has been listened to into a few mixes, each around a different
 * set of artists, and picks a different selection from each every day.
 *
 * ## No genre data needed
 *
 * The obvious way to make "Daily Mix 1: indie, Daily Mix 2: hip-hop" is to
 * cluster on genre, and a track here has none. What it does have is who made it
 * and how it has been received, so the mixes are built around *artists*: the
 * artists are ranked by how much their tracks are enjoyed, and dealt out to the
 * mixes in turn, so the first mix gets the favourite, the second the next, and
 * so on round again. Each mix is therefore a coherent handful of artists you
 * actually play, which is what a mix needs to be, without claiming to know
 * their genre.
 *
 * ## Why it changes daily and not on every look
 *
 * The selection within a mix is a weighted random one (see
 * [TasteModel.smartOrder]) seeded from the date, so it is the same all day —
 * opening the app twice shows the same mix — and different tomorrow.
 */
object DailyMix {

    /** Fewer tracks than this and a "mix" is just the whole history again. */
    const val MIN_TRACKS = 12

    /** An artist needs at least this many to the others to make a mix feel varied. */
    const val ARTISTS_PER_MIX = 3

    const val MAX_MIXES = 4
    const val TRACKS_PER_MIX = 30

    /** After this many days unheard, a track's pull has halved. */
    private const val HALF_LIFE_DAYS = 45.0

    /** Old favourites fade but never vanish. */
    private const val RECENCY_FLOOR = 0.15

    private const val MS_PER_DAY = 86_400_000.0

    /** How much a track is worth to a mix right now. 0 means leave it out. */
    fun score(entry: TasteEntry, nowMs: Long): Double {
        // Never heard through, or skipped more than finished: not for a mix.
        if (entry.fulls < 1 || entry.skips > entry.fulls) return 0.0
        val days = ((nowMs - entry.last).coerceAtLeast(0L)) / MS_PER_DAY
        val recency = 0.5.pow(days / HALF_LIFE_DAYS).coerceAtLeast(RECENCY_FLOOR)
        return TasteModel.affinity(entry) * ln(1.0 + entry.fulls) * recency
    }

    /**
     * The mixes for [daySeed] (use the day number), or none while there is not
     * enough listening to make a worthwhile one.
     */
    fun build(
        entries: List<TasteEntry>,
        nowMs: Long,
        daySeed: Long,
        perMix: Int = TRACKS_PER_MIX,
    ): List<Mix> {
        val scored = entries
            .filter { it.title.isNotBlank() && it.artistKey.isNotEmpty() }
            .map { it to score(it, nowMs) }
            .filter { it.second > 0.0 }
        if (scored.size < MIN_TRACKS) return emptyList()

        // Artists, ranked by their best few tracks rather than all of them: an
        // artist with fifty so-so plays should not outrank one with five loved.
        val byArtist = scored.groupBy { it.first.artistKey }
        val ranked = byArtist.entries
            .map { (key, tracks) ->
                val best = tracks.sortedByDescending { it.second }
                Triple(key, best, best.take(5).sumOf { it.second })
            }
            .sortedByDescending { it.third }

        val mixCount = (ranked.size / ARTISTS_PER_MIX).coerceIn(0, MAX_MIXES)
        if (mixCount == 0) return emptyList()

        return (0 until mixCount).mapNotNull { m ->
            val members = ranked.filterIndexed { rank, _ -> rank % mixCount == m }
            val perArtist = (perMix / members.size).coerceAtLeast(3) + 2
            val pool = members.flatMap { (_, tracks, _) -> tracks.take(perArtist) }
            if (pool.size < MIN_TRACKS / 2) return@mapNotNull null

            // Weighted by the track's mix score, not just whether it is liked:
            // a track played fifty times should lead more often than one played
            // twice.
            val order = TasteModel.weightedOrder(
                weights = pool.map { it.second },
                artists = pool.map { it.first.artist },
                random = Random(daySeed * 31 + m),
            )
            Mix(
                index = m,
                artists = members.map { (_, tracks, _) -> tracks.first().first.artist },
                songs = order.take(perMix).map { pool[it].first.toSong() },
            )
        }
    }

    private fun TasteEntry.toSong() = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = art,
        artistId = artistId,
    )
}
