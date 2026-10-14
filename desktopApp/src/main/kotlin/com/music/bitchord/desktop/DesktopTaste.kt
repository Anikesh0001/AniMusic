package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.BecauseRow
import com.music.bitchord.data.stats.DailyMix
import com.music.bitchord.data.stats.Discovery
import com.music.bitchord.data.stats.Mix
import com.music.bitchord.data.stats.TasteEntry
import com.music.bitchord.data.stats.TasteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.prefs.Preferences

/**
 * What the phone's Smart Shuffle, Daily Mix, Daylist and "Because you listened to" rows
 * run on, for the desktop: the same arithmetic ([TasteModel], [DailyMix], [Discovery], shared with
 * the phone) over a per-track profile kept on this computer.
 *
 * Nothing leaves the machine except, for the "Because you listened to" rows, the id of a track
 * in the same request the Radio button makes, at most once a day. It learns only while
 * [learning] is on, and [clear] forgets everything.
 *
 * Smart playlists are phone-only for now: they read the phone's month-by-month history files, and
 * the desktop keeps its listening in another shape.
 */
internal object DesktopTaste {

    private val preferences: Preferences = Preferences.userRoot().node("${DesktopBrand.preferencesNode}.taste")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val entries = HashMap<String, TasteEntry>()
    private var loaded = false
    private var dirty = false
    private var writeScheduled = false

    /** Whether it records which tracks are finished and which are skipped. */
    val learning = MutableStateFlow(DesktopPersistence().boolean(KEY_LEARNING, true))

    /** Whether shuffle favours finished tracks and spaces out an artist. */
    val smartShuffle = MutableStateFlow(DesktopPersistence().boolean(KEY_SMART_SHUFFLE, true))

    fun setLearning(value: Boolean) {
        learning.value = value
        DesktopPersistence().saveBoolean(KEY_LEARNING, value)
    }

    fun setSmartShuffle(value: Boolean) {
        smartShuffle.value = value
        DesktopPersistence().saveBoolean(KEY_SMART_SHUFFLE, value)
    }

    // ── The profile ─────────────────────────────────────────────────────────

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                DesktopPreferenceChunks.read(preferences, KEY_ENTRIES)?.takeIf { it.isNotBlank() }?.let { text ->
                    json.decodeFromString(ListSerializer(TasteEntry.serializer()), text).forEach { e ->
                        if (e.id.isNotBlank()) {
                            entries[e.id] = e.copy(slots = List(TasteModel.SLOT_COUNT) { e.slots.getOrElse(it) { 0 } })
                        }
                    }
                }
            }
            loaded = true
        }
    }

    private fun entry(id: String): TasteEntry? {
        ensureLoaded()
        return synchronized(lock) { entries[id] }
    }

    private fun touch(song: Song, nowMs: Long): TasteEntry {
        val existing = entries[song.videoId]
        val e = existing?.copy(
            title = existing.title.ifEmpty { song.title },
            artist = existing.artist.ifEmpty { song.artist },
            artistId = existing.artistId ?: song.artistId,
            art = existing.art ?: song.thumbnailUrl,
            last = nowMs,
        ) ?: TasteEntry(
            id = song.videoId,
            title = song.title,
            artist = song.artist,
            artistId = song.artistId,
            art = song.thumbnailUrl,
            last = nowMs,
        )
        entries[song.videoId] = e
        if (entries.size > MAX_ENTRIES) {
            val keep = entries.values
                .sortedWith(compareByDescending<TasteEntry> { it.fulls + it.skips }.thenByDescending { it.last })
                .take(MAX_ENTRIES - TRIM_SLACK).map { it.id }.toSet()
            entries.keys.retainAll(keep)
        }
        return e
    }

    private fun recordListen(song: Song) {
        ensureLoaded()
        val now = LocalDateTime.now()
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        val slot = TasteModel.slotOf(now.hour, weekend)
        synchronized(lock) {
            val e = touch(song, System.currentTimeMillis())
            e.fulls += 1
            e.slots = e.slots.mapIndexed { i, n -> if (i == slot) n + 1 else n }
            dirty = true
        }
        scheduleWrite()
    }

    private fun recordSkip(song: Song) {
        ensureLoaded()
        synchronized(lock) {
            touch(song, System.currentTimeMillis()).skips += 1
            dirty = true
        }
        scheduleWrite()
    }

    /** Writes soon, once, however many tracks finish in the meantime. */
    private fun scheduleWrite() {
        synchronized(lock) {
            if (writeScheduled) return
            writeScheduled = true
        }
        scope.launch {
            delay(WRITE_DELAY_MS)
            flush()
        }
    }

    /** Writes any pending changes now. */
    fun flush() {
        val snapshot = synchronized(lock) {
            writeScheduled = false
            if (!dirty) return
            dirty = false
            entries.values.map { it.copy() }
        }
        runCatching {
            DesktopPreferenceChunks.write(
                preferences,
                KEY_ENTRIES,
                json.encodeToString(ListSerializer(TasteEntry.serializer()), snapshot),
            )
            preferences.flush()
        }
    }

    /** Forgets everything learned, on disk too. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            loaded = true
            dirty = false
        }
        runCatching {
            DesktopPreferenceChunks.write(preferences, KEY_ENTRIES, "")
            preferences.flush()
        }
        _shelves.value = emptyList()
        mixes = emptyList()
        daylist = null
    }

    // ── Telling finished from skipped ───────────────────────────────────────

    private class Playing(val song: Song) {
        var playedMs = 0L
        var lengthMs = 0L
        var counted = false
        var paused = false
    }

    private var current: Playing? = null

    /** [song] is playing now — it started, or resumed after a pause. */
    fun onPlaying(song: Song, lengthMs: Long) {
        val previous = synchronized(lock) {
            val c = current
            if (c != null && c.song.videoId == song.videoId) {
                c.paused = false
                if (lengthMs > 0) c.lengthMs = lengthMs
                return
            }
            current = Playing(song).also { it.lengthMs = lengthMs }
            c
        }
        finish(previous)
    }

    /** Time heard of [song], and whether it has now counted as a play. */
    fun onProgress(song: Song, deltaMs: Long, reachedThreshold: Boolean, lengthMs: Long) {
        synchronized(lock) {
            val c = current?.takeIf { it.song.videoId == song.videoId } ?: return
            c.playedMs += deltaMs
            if (reachedThreshold) c.counted = true
            if (lengthMs > 0) c.lengthMs = lengthMs
        }
    }

    /**
     * Playback paused or stopped. Not an outcome: a track paused and then left for another one
     * was not skipped, and a listen already counted stays counted.
     */
    fun onPaused() {
        synchronized(lock) { current?.paused = true }
    }

    /**
     * Files how the track that has just been left went: heard through if it counted, skipped if
     * it was left within [TasteModel.SKIP_MS] while it was playing, and nothing in between.
     */
    private fun finish(played: Playing?) {
        if (played == null || !learning.value) return
        val ranOut = played.lengthMs > 0 && played.playedMs >= played.lengthMs * 0.9
        when {
            played.counted -> recordListen(played.song)
            played.paused -> Unit
            played.playedMs < TasteModel.SKIP_MS && !ranOut -> recordSkip(played.song)
        }
    }

    // ── Smart Shuffle ───────────────────────────────────────────────────────

    /** [songs] in a random order that favours what has been heard through — or a plain shuffle. */
    fun reorderSongs(songs: List<Song>): List<Song> {
        if (!smartShuffle.value || songs.size < 2) return songs.shuffled()
        ensureLoaded()
        val order = TasteModel.smartOrder(songs.map { it.videoId }, songs.map { it.artist }, ::entry)
        return order.map { songs[it] }
    }

    /** The same for a section given as indices into [source]. */
    fun reorderIndices(indices: List<Int>, source: List<Song>): List<Int> {
        if (!smartShuffle.value || indices.size < 2) return indices.shuffled()
        ensureLoaded()
        val order = TasteModel.smartOrder(
            indices.map { source[it].videoId },
            indices.map { source[it].artist },
            ::entry,
        )
        return order.map { indices[it] }
    }

    // ── Mixes and rows for Home ─────────────────────────────────────────────

    private const val MIX_PREFIX = "mix:daily:"
    private const val DAYLIST_ID = "mix:daylist"

    private var mixes: List<Mix> = emptyList()
    private var daylist: Mix? = null
    private var because: List<BecauseRow> = emptyList()
    private var becauseKey: String? = null

    private val _shelves = MutableStateFlow<List<HomeShelf>>(emptyList())

    /** Shelves for Home, already worded in the current language; empty while there is too little to go on. */
    val shelves: StateFlow<List<HomeShelf>> = _shelves.asStateFlow()

    fun isMix(browseId: String?): Boolean = browseId != null && browseId.startsWith("mix:")

    /** The songs behind a mix card, or null if [browseId] is not one. */
    fun songsFor(browseId: String?): List<Song>? = when {
        browseId == DAYLIST_ID -> daylist?.songs
        browseId != null && browseId.startsWith(MIX_PREFIX) ->
            mixes.getOrNull(browseId.removePrefix(MIX_PREFIX).toIntOrNull() ?: -1)?.songs
        else -> null
    }

    /**
     * Recomputes the shelves. Cheap but for the radio requests, which are made at most once a day.
     * [fetchRows] is false when YouTube Music is switched off.
     */
    suspend fun refresh(fetchRows: Boolean) = withContext(Dispatchers.Default) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val known = synchronized(lock) { entries.values.map { it.copy() } }
        val have = known.mapTo(HashSet()) { it.id }
        // Until the profile has filled in, the most played tracks of the Replay stand in for it.
        val borrowed = runCatching {
            DesktopListeningStats.summary(DesktopReplayPeriod.ALL_TIME).songs
        }.getOrDefault(emptyList()).filter { it.song.videoId !in have }.map {
            TasteEntry(
                id = it.song.videoId,
                title = it.song.title,
                artist = it.song.artist,
                artistId = it.song.artistId,
                art = it.song.thumbnailUrl,
                fulls = it.plays,
                last = now - 7 * 86_400_000L,
            )
        }
        val all = known + borrowed
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        mixes = DailyMix.build(all, now, today)
        val clock = LocalDateTime.now()
        val weekend = clock.dayOfWeek == DayOfWeek.SATURDAY || clock.dayOfWeek == DayOfWeek.SUNDAY
        daylist = DailyMix.buildDaylist(known, now, clock.hour, weekend, today)

        if (fetchRows) {
            val seeds = Discovery.pickSeeds(all, now, today)
            val key = today.toString() + seeds.joinToString(",") { it.id }
            if (seeds.isNotEmpty() && (key != becauseKey || because.isEmpty())) {
                val heard = all.mapTo(HashSet()) { it.id }
                val taken = HashSet<String>()
                val built = seeds.mapNotNull { seed ->
                    val related = DesktopSearchClient.radio(seed.id).getOrNull() ?: return@mapNotNull null
                    Discovery.recommend(related, seed, heard, taken).takeIf { it.isNotEmpty() }?.let { BecauseRow(seed, it) }
                }
                if (built.isNotEmpty()) {
                    because = built
                    becauseKey = key
                }
            }
        }
        _shelves.value = buildShelves(clock)
    }

    private fun buildShelves(clock: LocalDateTime): List<HomeShelf> {
        val cards = ArrayList<ShelfItem>()
        daylist?.let { mix ->
            val day = clock.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val key = when (mix.slot % 4) {
                1 -> "daylist_morning"
                2 -> "daylist_afternoon"
                3 -> "daylist_evening"
                else -> "daylist_night"
            }
            cards += ShelfItem(
                title = DesktopStrings.format(key, day, fallback = "$day"),
                subtitle = DesktopStrings.format("mix_subtitle", mix.artists.joinToString(", "), fallback = mix.artists.joinToString(", ")),
                thumbnailUrl = mix.art,
                videoId = null,
                browseId = DAYLIST_ID,
            )
        }
        mixes.forEach { mix ->
            cards += ShelfItem(
                title = DesktopStrings.format("daily_mix_n", mix.index + 1, fallback = "Daily Mix ${mix.index + 1}"),
                subtitle = DesktopStrings.format("mix_subtitle", mix.artists.take(3).joinToString(", "), fallback = mix.artists.take(3).joinToString(", ")),
                thumbnailUrl = mix.art,
                videoId = null,
                browseId = MIX_PREFIX + mix.index,
            )
        }
        val shelves = ArrayList<HomeShelf>()
        if (cards.isNotEmpty()) {
            shelves += HomeShelf(title = DesktopStrings["made_for_you", "Made for you"], items = cards)
        }
        because.forEach { row ->
            shelves += HomeShelf(
                title = DesktopStrings.format("because_you_listened_to", row.seed.artist, fallback = "Because you listened to ${row.seed.artist}"),
                items = row.songs.map { song ->
                    ShelfItem(
                        title = song.title,
                        subtitle = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        videoId = song.videoId,
                        browseId = null,
                    )
                },
            )
        }
        return shelves
    }

    private const val KEY_ENTRIES = "taste.entries"
    private const val KEY_LEARNING = "taste_learning"
    private const val KEY_SMART_SHUFFLE = "smart_shuffle"

    // Smaller than the phone's: this is kept in the preferences store, which suits kilobytes
    // better than megabytes.
    private const val MAX_ENTRIES = 1500
    private const val TRIM_SLACK = 150
    private const val WRITE_DELAY_MS = 30_000L
}
