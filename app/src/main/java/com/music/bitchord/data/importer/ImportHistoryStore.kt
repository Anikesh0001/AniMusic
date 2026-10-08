package com.music.bitchord.data.importer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One import: where it came from, where it went, and how well it matched. */
@Serializable
data class ImportRecord(
    val id: String,
    /** [ImportService] name. */
    val service: String,
    val sourceUrl: String,
    val title: String,
    /** The playlist it was saved as; null if saving failed. */
    val browseId: String? = null,
    val savedLocally: Boolean = false,
    val importedAt: Long,
    val syncedAt: Long? = null,
    val matched: Int,
    val total: Int,
    /**
     * [TrackResolver.key] of every source row seen so far, so a sync matches
     * only what was added at the source since.
     */
    val sourceKeys: List<String> = emptyList(),
) {
    val serviceLabel: String
        get() = runCatching { ImportService.valueOf(service).label }.getOrDefault(service)
}

/**
 * Past imports, newest first, kept on the device.
 *
 * Separate from [LocalPlaylistStore][com.music.bitchord.data.spotify.LocalPlaylistStore]
 * because an import signed in to YouTube Music becomes a playlist on the
 * account, which keeps no note of where it came from — this does, and that is
 * what lets such a playlist be synced from its source later.
 */
object ImportHistoryStore {
    private const val PREF_NAME = "bitchord_import_history"
    private const val KEY = "records"
    private const val MAX_RECORDS = 100

    private var prefs: SharedPreferences? = null
    private val serializer = ListSerializer(ImportRecord.serializer())

    private val _records = MutableStateFlow<List<ImportRecord>>(emptyList())
    val records: StateFlow<List<ImportRecord>> = _records.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        _records.value = decode(p.getString(KEY, null))
    }

    internal fun decode(raw: String?): List<ImportRecord> =
        raw?.let { runCatching { ImportHttp.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    internal fun encode(records: List<ImportRecord>): String = ImportHttp.json.encodeToString(serializer, records)

    /** Notes an import that has just been saved as [browseId]. */
    fun record(
        collection: ImportedCollection,
        result: ResolveResult,
        browseId: String?,
        savedLocally: Boolean,
        now: Long = System.currentTimeMillis(),
    ): ImportRecord {
        val record = ImportRecord(
            id = "imp_$now",
            service = collection.service.name,
            sourceUrl = collection.sourceUrl,
            title = collection.title,
            browseId = browseId,
            savedLocally = savedLocally,
            importedAt = now,
            matched = result.songs.size,
            total = collection.tracks.size + collection.missingCount,
            sourceKeys = collection.tracks.map(TrackResolver::key).distinct(),
        )
        set((listOf(record) + _records.value).take(MAX_RECORDS))
        return record
    }

    /** Adds a record made elsewhere (see [ImportSync.recordFor]). */
    fun adopt(record: ImportRecord) = set((listOf(record) + _records.value).take(MAX_RECORDS))

    fun update(id: String, change: (ImportRecord) -> ImportRecord) =
        set(_records.value.map { if (it.id == id) change(it) else it })

    fun remove(id: String) = set(_records.value.filterNot { it.id == id })

    /** The latest import saved as [browseId], if any. */
    fun forBrowseId(browseId: String): ImportRecord? {
        val bare = browseId.removePrefix("VL")
        return _records.value.firstOrNull { r ->
            r.browseId != null && (r.browseId == browseId || r.browseId.removePrefix("VL") == bare)
        }
    }

    private fun set(records: List<ImportRecord>) {
        _records.value = records
        prefs?.edit()?.putString(KEY, encode(records))?.apply()
    }
}
