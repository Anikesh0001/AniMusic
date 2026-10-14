package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * One track's listening, folded from every month [ListeningStats] holds.
 *
 * The months are what make a "rediscover" possible at all: the profile in
 * [TasteStore] only knows the time since it started counting, while the monthly
 * files reach back as far as the app has been used.
 */
data class TrackHistory(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val art: String?,
    /** Listens that counted, across all time. */
    val plays: Int,
    /** Listens in this calendar month and the one before. */
    val recentPlays: Int,
    /** Times it was left within seconds, from [TasteStore]; 0 if never recorded. */
    val skips: Int,
    /** Epoch ms of the latest listen. */
    val lastMs: Long,
    /** The earliest month it appears in. */
    val firstMonth: YearMonth,
)

/** What a [Condition] or a sort can look at. */
enum class SmartField { PLAYS, RECENT_PLAYS, SKIP_RATE, DAYS_SINCE_LAST, MONTHS_SINCE_FIRST }

/** [field] must lie within [min]..[max]; either end may be left open. */
data class Condition(val field: SmartField, val min: Double? = null, val max: Double? = null)

/**
 * A playlist described as data: which tracks qualify, how they are ranked and
 * how many are kept. Evaluated again whenever the history changes, so the
 * playlist is never edited by hand and never goes stale.
 *
 * [minTracks] keeps a playlist from appearing until it is worth opening: a
 * "Rediscover" of two songs is just two songs.
 */
data class SmartRule(
    val key: String,
    val conditions: List<Condition>,
    val sortBy: SmartField,
    val descending: Boolean = true,
    val limit: Int = 50,
    val minTracks: Int = 8,
)

object SmartRules {

    private const val MS_PER_DAY = 86_400_000.0

    /** The values a condition or sort reads off [t], as of [nowMs] in [now]'s month. */
    fun valueOf(t: TrackHistory, field: SmartField, nowMs: Long, now: YearMonth): Double = when (field) {
        SmartField.PLAYS -> t.plays.toDouble()
        SmartField.RECENT_PLAYS -> t.recentPlays.toDouble()
        SmartField.SKIP_RATE -> t.skips.toDouble() / (t.plays + t.skips).coerceAtLeast(1)
        SmartField.DAYS_SINCE_LAST -> ((nowMs - t.lastMs).coerceAtLeast(0L)) / MS_PER_DAY
        SmartField.MONTHS_SINCE_FIRST -> ChronoUnit.MONTHS.between(t.firstMonth, now).coerceAtLeast(0).toDouble()
    }

    /**
     * The tracks [rule] selects, best first; empty if fewer than [SmartRule.minTracks] qualify.
     * Ties go to the track with more plays, then to the title, so the order is the same every time.
     */
    fun evaluate(rule: SmartRule, tracks: List<TrackHistory>, nowMs: Long, now: YearMonth): List<TrackHistory> {
        val matching = tracks.filter { t ->
            TasteModel.isYouTubeId(t.id) && t.title.isNotBlank() && rule.conditions.all { c ->
                val v = valueOf(t, c.field, nowMs, now)
                (c.min == null || v >= c.min) && (c.max == null || v <= c.max)
            }
        }
        if (matching.size < rule.minTracks) return emptyList()
        val byValue = Comparator<TrackHistory> { a, b ->
            val x = valueOf(a, rule.sortBy, nowMs, now)
            val y = valueOf(b, rule.sortBy, nowMs, now)
            if (rule.descending) y.compareTo(x) else x.compareTo(y)
        }
        return matching
            .sortedWith(byValue.thenByDescending { it.plays }.thenBy { it.title })
            .take(rule.limit)
    }

    /**
     * Folds the monthly files into one [TrackHistory] per track.
     *
     * [skipsOf] supplies skips, which the monthly files do not hold. A track's
     * name and art come from the latest month that has them.
     */
    fun histories(buckets: List<StoredBucket>, now: YearMonth, skipsOf: (String) -> Int): List<TrackHistory> {
        class Acc(var entry: TrackEntry, var plays: Int, var recent: Int, var last: Long, var first: YearMonth)

        val byId = HashMap<String, Acc>()
        for (bucket in buckets.sortedBy { it.month }) {
            val month = runCatching { YearMonth.parse(bucket.month) }.getOrNull() ?: continue
            val isRecent = !month.isBefore(now.minusMonths(1))
            for (t in bucket.tracks) {
                if (t.plays <= 0) continue
                val acc = byId[t.id]
                if (acc == null) {
                    byId[t.id] = Acc(t, t.plays, if (isRecent) t.plays else 0, t.last, month)
                } else {
                    acc.plays += t.plays
                    if (isRecent) acc.recent += t.plays
                    acc.last = maxOf(acc.last, t.last)
                    // Buckets are walked oldest first, so the newest spelling wins.
                    acc.entry = t.copy(art = t.art ?: acc.entry.art, artistId = t.artistId ?: acc.entry.artistId)
                }
            }
        }
        return byId.values.map { a ->
            TrackHistory(
                id = a.entry.id,
                title = a.entry.title,
                artist = a.entry.artist,
                artistId = a.entry.artistId,
                art = a.entry.art,
                plays = a.plays,
                recentPlays = a.recent,
                skips = skipsOf(a.entry.id),
                lastMs = a.last,
                firstMonth = a.first,
            )
        }
    }

    fun TrackHistory.toSong() = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = art,
        artistId = artistId,
    )

    /** Heard a lot lately and in the last two weeks. */
    val ON_REPEAT = SmartRule(
        key = "on_repeat",
        conditions = listOf(
            Condition(SmartField.RECENT_PLAYS, min = 3.0),
            Condition(SmartField.DAYS_SINCE_LAST, max = 14.0),
        ),
        sortBy = SmartField.RECENT_PLAYS,
        limit = 50,
        minTracks = 8,
    )

    /** Songs you played a lot and have not heard for two months. */
    val REDISCOVER = SmartRule(
        key = "rediscover",
        conditions = listOf(
            Condition(SmartField.PLAYS, min = 5.0),
            Condition(SmartField.DAYS_SINCE_LAST, min = 60.0),
            Condition(SmartField.SKIP_RATE, max = 0.25),
        ),
        sortBy = SmartField.PLAYS,
        limit = 50,
        minTracks = 10,
    )

    /** Your most played, skips aside. */
    val FAVORITES = SmartRule(
        key = "favorites",
        conditions = listOf(
            Condition(SmartField.PLAYS, min = 3.0),
            Condition(SmartField.SKIP_RATE, max = 0.4),
        ),
        sortBy = SmartField.PLAYS,
        limit = 100,
        minTracks = 10,
    )

    /** Found in the last couple of months and already played on. */
    val NEW_FAVORITES = SmartRule(
        key = "new_favorites",
        conditions = listOf(
            Condition(SmartField.MONTHS_SINCE_FIRST, max = 2.0),
            Condition(SmartField.PLAYS, min = 3.0),
            Condition(SmartField.SKIP_RATE, max = 0.4),
        ),
        sortBy = SmartField.PLAYS,
        limit = 50,
        minTracks = 8,
    )

    val BUILT_IN = listOf(ON_REPEAT, NEW_FAVORITES, FAVORITES, REDISCOVER)
}
