package com.music.bitchord.data.importer

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What one imported row turned into on YouTube Music. */
data class ResolvedTrack(
    val track: ImportTrack,
    /** Null when nothing was found at all. */
    val song: Song?,
    /**
     * False when [TrackMatcher] accepted none of the results and [song] is
     * only the search's first hit — the rows the match review lists.
     */
    val confident: Boolean,
)

data class ResolveResult(val rows: List<ResolvedTrack>) {
    /** Every matched song, in the source's order. */
    val songs: List<Song> get() = rows.mapNotNull { it.song }
    val unmatched: List<ImportTrack> get() = rows.filter { it.song == null }.map { it.track }
    val lowConfidence: List<ResolvedTrack> get() = rows.filter { it.song != null && !it.confident }
}

/**
 * Finds each imported row on YouTube Music.
 *
 * Every importer goes through this, so every one gets [TrackMatcher]'s
 * judgement — title proper, version markers, artist overlap and runtime —
 * rather than the search's first hit. The first hit is still used when the
 * matcher accepts nothing, because a slightly wrong song is a better import
 * than a hole, but it is flagged so the review screen can offer the
 * alternatives.
 *
 * [search] is injected so the ordering, caching and fallback rules are
 * testable without a network.
 */
class TrackResolver(
    private val search: suspend (String) -> List<Song>,
    private val concurrency: Int = 4,
    /** The song behind a known video id; for rows that arrive already knowing it. */
    private val lookup: suspend (videoId: String) -> Song? = { null },
) {
    /** Keyed by [key]; lives as long as the resolver, so a re-import or sync is cheap. */
    private val cache = ConcurrentHashMap<String, ResolvedTrack>()

    suspend fun resolve(
        tracks: List<ImportTrack>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ResolveResult = coroutineScope {
        val total = tracks.size
        val done = AtomicInteger(0)
        val gate = Semaphore(concurrency)
        // The same song twice in a playlist is searched once.
        val groups = tracks.withIndex().groupBy { key(it.value) }
        val found = groups.map { (_, members) ->
            async(Dispatchers.IO) {
                val match = gate.withPermit { match(members.first().value) }
                // Serialised, so the count a listener sees only ever rises:
                // two workers finishing together must not leave "2 of 3"
                // standing after "3 of 3".
                synchronized(done) { onProgress(done.addAndGet(members.size), total) }
                members.map { it.index to match.copy(track = it.value) }
            }
        }.awaitAll().flatten().sortedBy { it.first }.map { it.second }
        ResolveResult(found)
    }

    /** One row's match, from the cache when it was looked up before. */
    suspend fun match(track: ImportTrack): ResolvedTrack {
        val key = key(track)
        track.song?.let { return ResolvedTrack(track, it, confident = true) }
        cache[key]?.let { return it.copy(track = track) }
        track.videoId?.let { id ->
            runCatching { lookup(id) }.getOrNull()?.let { return ResolvedTrack(track, it, confident = true) }
        }
        val target = targetOf(track)
        val queries = TrackMatcher.queries(target)
            .ifEmpty { listOf("${track.title} ${track.artist}".trim()) }
            .filter { it.isNotBlank() }
        var firstHit: Song? = null
        var result: ResolvedTrack? = null
        for (query in queries) {
            val candidates = runCatching { search(query) }.getOrDefault(emptyList())
            if (firstHit == null) firstHit = candidates.firstOrNull()
            val best = TrackMatcher.best(candidates, target)
            if (best != null) {
                result = ResolvedTrack(track, best, confident = true)
                break
            }
        }
        val resolved = result ?: ResolvedTrack(track, firstHit, confident = false)
        // A miss is not remembered: it may have been the network, and
        // "Retry unmatched" has to really ask again.
        if (resolved.song != null) cache[key] = resolved
        return resolved
    }

    /**
     * The best few YouTube Music songs for [track], for the review screen:
     * the matcher's ranking first, then the rest of the search in its order.
     */
    suspend fun candidates(track: ImportTrack, limit: Int = 5): List<Song> {
        val target = targetOf(track)
        val query = TrackMatcher.queries(target).firstOrNull()
            ?: "${track.title} ${track.artist}".trim()
        val results = runCatching { search(query) }.getOrDefault(emptyList())
        val ranked = TrackMatcher.ranked(results, target)
        return (ranked + results).distinctBy { it.videoId }.take(limit)
    }

    /** Free-text search, for the review screen's manual box. */
    suspend fun searchSongs(query: String): List<Song> =
        runCatching { search(query) }.getOrDefault(emptyList())

    companion object {
        /** Searches YouTube Music's song shelf. */
        val Default: TrackResolver by lazy {
            TrackResolver(
                search = { query ->
                    YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()
                },
                lookup = { videoId -> YtMusicRepository.trackLinks(videoId).getOrNull() },
            )
        }

        fun targetOf(track: ImportTrack) = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs?.takeIf { it > 0 }?.div(1000)?.toInt(),
            album = track.album?.takeIf { it.isNotBlank() },
        )

        /**
         * "title|artist", case- and spacing-insensitive — or the video id, for
         * rows that are nothing else (a Takeout playlist is all ids).
         */
        fun key(track: ImportTrack): String =
            track.videoId?.let { "v:$it" } ?: (normalizeKey(track.title) + "|" + normalizeKey(track.artist))

        private fun normalizeKey(s: String) = s.lowercase().trim().replace(Regex("""\s+"""), " ")
    }
}
