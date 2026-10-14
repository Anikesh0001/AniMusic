package com.music.bitchord.data.stats

import com.music.bitchord.data.YtMusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Because you listened to …": a few songs to try for each of a few tracks you
 * play, from YouTube Music's radio for that track.
 *
 * ## What leaves the device
 *
 * Only the id of each seed track, in the same request the Radio button makes.
 * Nothing is sent from Incognito, where no rows are made, and the rows are
 * fetched once a day rather than on every look at Home.
 */
object BecauseRows {

    private val _rows = MutableStateFlow<List<BecauseRow>>(emptyList())

    /** Empty until there is something to seed from, and while Incognito is on. */
    val rows: StateFlow<List<BecauseRow>> = _rows.asStateFlow()

    private var fetchedFor: String? = null

    suspend fun refresh(incognito: Boolean) = withContext(Dispatchers.Default) {
        if (incognito) {
            _rows.value = emptyList()
            fetchedFor = null
            return@withContext
        }
        val now = System.currentTimeMillis()
        val (known, borrowed) = DailyMixes.gather(now)
        val all = known + borrowed
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val seeds = Discovery.pickSeeds(all, now, today)
        if (seeds.isEmpty()) return@withContext

        // Today's seeds already answered: nothing new to ask.
        val key = today.toString() + seeds.joinToString(",") { it.id }
        if (key == fetchedFor && _rows.value.isNotEmpty()) return@withContext

        val heard = all.mapTo(HashSet()) { it.id }
        val taken = HashSet<String>()
        val built = seeds.mapNotNull { seed ->
            val related = YtMusicRepository.radio(seed.id).getOrNull() ?: return@mapNotNull null
            Discovery.recommend(related, seed, heard, taken)
                .takeIf { it.isNotEmpty() }
                ?.let { BecauseRow(seed, it) }
        }
        // A failed round keeps what was already showing rather than blanking it.
        if (built.isNotEmpty()) {
            _rows.value = built
            fetchedFor = key
        }
    }
}
