package com.music.bitchord.data.stats

import android.content.Context
import android.util.Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * The per-track counters Smart Shuffle and the mixes read: [TasteEntry], one
 * per track, in a single file.
 *
 * ## Nothing leaves the device
 *
 * Like [ListeningStats], there is no upload, no account and no id. It is
 * written only while learning is on and Incognito is off, [clear] empties it,
 * and [Backup] carries it only because the user asked for a backup.
 *
 * ## One file, bounded
 *
 * A track is a few dozen bytes, so one file read once at start is cheaper than
 * a database for this. It is capped at [MAX_ENTRIES]: past that, the tracks
 * with the least history and the longest since they were heard are dropped,
 * which is the opposite of the ones worth keeping.
 */
object TasteStore {

    private lateinit var file: File

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val entries = HashMap<String, TasteEntry>()
    private val lock = Any()
    private var dirty = false

    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        writer.launch { load() }
    }

    private val ready: Boolean get() = this::file.isInitialized

    /** What the track at [id] has done so far, or null if it is new here. */
    fun entry(id: String): TasteEntry? = synchronized(lock) { entries[id] }

    /** A copy, for a reader that wants to go through everything. */
    fun snapshot(): List<TasteEntry> = synchronized(lock) { entries.values.map { it.copy() } }

    /** How many tracks it knows anything about. */
    fun size(): Int = synchronized(lock) { entries.size }

    /** A track was heard through. */
    fun recordListen(song: Song, now: LocalDateTime = LocalDateTime.now(), nowMs: Long = System.currentTimeMillis()) {
        if (!ready) return
        synchronized(lock) {
            val e = touch(song, nowMs)
            e.fulls += 1
            val slot = TasteModel.slotOf(now.hour, now.dayOfWeek.isWeekend())
            e.slots = e.slots.mapIndexed { i, n -> if (i == slot) n + 1 else n }
            dirty = true
        }
    }

    /** A track was left within seconds of starting. */
    fun recordSkip(song: Song, nowMs: Long = System.currentTimeMillis()) {
        if (!ready) return
        synchronized(lock) {
            touch(song, nowMs).skips += 1
            dirty = true
        }
    }

    private fun DayOfWeek.isWeekend() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

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
        if (entries.size > MAX_ENTRIES) trim()
        return e
    }

    /** Drops the least-evidenced, longest-unheard tracks back under the cap. */
    private fun trim() {
        val keep = entries.values
            .sortedWith(compareByDescending<TasteEntry> { it.fulls + it.skips }.thenByDescending { it.last })
            .take(MAX_ENTRIES - TRIM_SLACK)
            .map { it.id }
            .toSet()
        entries.keys.retainAll(keep)
    }

    /** Writes any pending changes. Cheap when there are none. */
    fun flush() {
        if (!ready) return
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            entries.values.map { it.copy() }
        }
        writer.launch { write(snapshot) }
    }

    /** Forgets everything, on disk too. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            dirty = false
        }
        if (!ready) return
        writer.launch { writeLock.withLock { file.delete() } }
    }

    /** Everything, for [Backup]. */
    fun exportAll(): List<TasteEntry> = snapshot()

    /** Replaces what is held with [imported], for [Backup]. */
    fun importAll(imported: List<TasteEntry>) {
        val clean = imported.filter { it.id.isNotBlank() }.map { e ->
            e.copy(slots = List(TasteModel.SLOT_COUNT) { e.slots.getOrElse(it) { 0 } })
        }
        synchronized(lock) {
            entries.clear()
            clean.forEach { entries[it.id] = it }
            if (entries.size > MAX_ENTRIES) trim()
            dirty = false
        }
        if (ready) writer.launch { write(snapshot()) }
    }

    private suspend fun load() = writeLock.withLock {
        runCatching {
            if (!file.exists()) return@runCatching
            val stored = json.decodeFromString(StoredTaste.serializer(), file.readText())
            importLoaded(stored.entries)
        }.onFailure {
            // A damaged file costs the taste profile, not the app.
            Log.w(TAG, "Couldn't read the taste file; starting empty", it)
        }
    }

    private fun importLoaded(loaded: List<TasteEntry>) = synchronized(lock) {
        loaded.forEach { e ->
            if (e.id.isBlank()) return@forEach
            // Anything recorded before the load finished wins over the file.
            entries.putIfAbsent(e.id, e.copy(slots = List(TasteModel.SLOT_COUNT) { e.slots.getOrElse(it) { 0 } }))
        }
    }

    private suspend fun write(snapshot: List<TasteEntry>) = writeLock.withLock {
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(StoredTaste.serializer(), StoredTaste(entries = snapshot)))
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "Couldn't write the taste file", it) }
    }

    @Serializable
    private data class StoredTaste(val version: Int = 1, val entries: List<TasteEntry> = emptyList())

    private const val TAG = "TasteStore"
    private const val FILE_NAME = "taste.json"
    const val MAX_ENTRIES = 4000
    private const val TRIM_SLACK = 400
}
