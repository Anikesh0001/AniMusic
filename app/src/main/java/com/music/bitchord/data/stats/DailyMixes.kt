package com.music.bitchord.data.stats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's [Mix]es, built from [TasteStore] and kept for the Home screen.
 *
 * ## Borrowing from Replay for the first weeks
 *
 * The taste profile only starts counting from the version that introduced it,
 * so on the day someone updates it knows nothing, even if they have years of
 * listening. [ListeningStats] does know. Its most played tracks are folded in
 * for anything the profile has not seen, counted as finished listens, so the
 * mixes are worth showing from the first launch and then hand over to the
 * profile as it fills in.
 */
object DailyMixes {

    /** The id a mix's Home card carries, followed by the mix's index. */
    const val BROWSE_PREFIX = "mix:daily:"

    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())

    /** Empty while there is too little listening, which hides the Home shelf. */
    val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()

    /** Recomputes the mixes. Cheap enough to call whenever Home is shown. */
    suspend fun refresh() = withContext(Dispatchers.Default) {
        TasteStore.awaitLoaded()
        val known = TasteStore.snapshot()
        val have = known.mapTo(HashSet()) { it.id }
        val now = System.currentTimeMillis()

        val borrowed = runCatching {
            ListeningStats.summary(ReplayPeriod.ALL_TIME).songs
        }.getOrDefault(emptyList()).filter { it.song.videoId !in have }.map {
            TasteEntry(
                id = it.song.videoId,
                title = it.song.title,
                artist = it.song.artist,
                artistId = it.song.artistId,
                art = it.song.thumbnailUrl,
                fulls = it.plays,
                // Replay does not keep when, so assume a week ago: mildly
                // fresh, rather than claiming either today or never.
                last = now - 7 * 86_400_000L,
            )
        }

        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        _mixes.value = DailyMix.build(known + borrowed, now, today)
    }
}
