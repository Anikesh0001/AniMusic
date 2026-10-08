package com.music.bitchord.data.importer

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.spotify.LocalPlaylistStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Sync from source": re-read an imported playlist's source and append what
 * was added there since.
 *
 * Only rows the source didn't list last time are matched, so a sync of a
 * 500-song playlist with three new songs is three searches, not 500. Nothing
 * is removed: a row dropped at the source may be one the listener still
 * wants, and taking songs out of their playlist unasked is the worse mistake.
 */
object ImportSync {

    /** How a sync went. */
    sealed interface Outcome {
        data class Added(val added: Int, val unmatched: Int) : Outcome
        data object UpToDate : Outcome
        data class Failed(val reason: ImportException.Reason?) : Outcome
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private val running = Mutex()

    /**
     * The record to sync [browseId] from: its import, or — for a playlist
     * saved on this device before imports were recorded — one made from the
     * source link the local playlist kept.
     */
    fun recordFor(browseId: String?): ImportRecord? {
        browseId ?: return null
        ImportHistoryStore.forBrowseId(browseId)?.let { return it }
        val local = LocalPlaylistStore.getPlaylist(browseId) ?: return null
        val source = local.sourceUrl ?: return null
        return ImportRecord(
            id = "local_${local.id}",
            service = local.service ?: ImportService.WEB.name,
            sourceUrl = source,
            title = local.title,
            browseId = local.browseId,
            savedLocally = true,
            importedAt = 0,
            matched = local.songs.size,
            total = local.songs.size,
        )
    }

    suspend fun sync(record: ImportRecord, resolver: TrackResolver = TrackResolver.Default): Outcome =
        running.withLock { syncLocked(record, resolver) }

    private suspend fun syncLocked(record: ImportRecord, resolver: TrackResolver): Outcome {
        val browseId = record.browseId ?: return Outcome.Failed(null)
        val collection = try {
            ImporterRegistry.fetch(record.sourceUrl)
        } catch (e: ImportException) {
            return Outcome.Failed(e.reason)
        } catch (e: Exception) {
            return Outcome.Failed(ImportException.Reason.NETWORK)
        }
        val known = record.sourceKeys.toSet()
        val fresh = collection.tracks.filter { TrackResolver.key(it) !in known }
        val now = System.currentTimeMillis()
        val keys = (record.sourceKeys + collection.tracks.map(TrackResolver::key)).distinct()
        if (fresh.isEmpty()) {
            save(record, keys, now, added = 0, total = collection.tracks.size)
            return Outcome.UpToDate
        }
        val matched = resolver.resolve(fresh)
        val isLocal = record.savedLocally || browseId.startsWith("local:playlist:")
        val added = if (isLocal) {
            val before = LocalPlaylistStore.getPlaylist(browseId)?.songs?.size ?: 0
            LocalPlaylistStore.appendSongs(browseId, matched.songs)
            (LocalPlaylistStore.getPlaylist(browseId)?.songs?.size ?: 0) - before
        } else {
            val have = YtMusicRepository.allSongs(browseId).getOrNull()?.map { it.videoId }?.toSet().orEmpty()
            val ids = matched.songs.map { it.videoId }.filter { it !in have }.distinct()
            if (ids.isEmpty()) {
                0
            } else {
                YtMusicRepository.addToPlaylist(browseId.removePrefix("VL"), ids).getOrNull()
                    ?: return Outcome.Failed(ImportException.Reason.NETWORK)
                ids.size
            }
        }
        save(record, keys, now, added, collection.tracks.size)
        return if (added == 0 && matched.unmatched.isEmpty()) Outcome.UpToDate
        else Outcome.Added(added, matched.unmatched.size)
    }

    private fun save(record: ImportRecord, keys: List<String>, now: Long, added: Int, total: Int) {
        val updated = record.copy(
            syncedAt = now,
            sourceKeys = keys,
            matched = record.matched + added,
            total = maxOf(total, record.total),
        )
        if (ImportHistoryStore.records.value.any { it.id == record.id }) {
            ImportHistoryStore.update(record.id) { updated }
        } else {
            // A local playlist from before imports were recorded gets a record now.
            ImportHistoryStore.adopt(updated)
        }
    }

    /**
     * Syncs every recorded import from its source, once a day at most, when
     * [ImportSettings.autoSyncDaily] is on. Called as the app opens.
     */
    suspend fun autoSyncIfDue(now: Long = System.currentTimeMillis()) {
        if (!ImportSettings.autoSyncDaily.value) return
        if (now - ImportSettings.lastAutoSync < DAY_MS) return
        ImportSettings.lastAutoSync = now
        for (record in ImportHistoryStore.records.value.distinctBy { it.browseId }) {
            if (record.browseId == null) continue
            sync(record)
        }
    }
}
