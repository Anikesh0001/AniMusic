package com.music.bitchord.data.account

import android.content.Context
import com.music.bitchord.data.Blocklist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.CustomSmartRules
import com.music.bitchord.data.stats.SmartPlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Keeps this device's own playlists and custom smart-playlist rules in step
 * with the [AniAccount] on the server.
 *
 * A three-way merge per item, against what this device last synced (the
 * "base", kept in a file): whichever side changed an item wins, a deletion on
 * one side goes through when the other side left that item alone, and when
 * both changed it this device's version wins. So two phones adding playlists
 * at the same time both keep them, and nothing is overwritten wholesale.
 *
 * Playlists the app writes itself from listening stats (ids starting `smart_`)
 * are not synced: each device rebuilds them from its own stats and the rules.
 */
object AccountSync {

    enum class Status { IDLE, SYNCING, DONE, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _lastSynced = MutableStateFlow(0L)

    /** When this device last finished a sync (epoch ms), 0 if never. */
    val lastSynced: StateFlow<Long> = _lastSynced.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var app: Context

    /** From application start: syncs now if signed in, and again after every playlist change. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (!AniAccount.available) return
        app = context.applicationContext
        scope.launch {
            AniAccount.current.filterNotNull().collect { syncNow() }
        }
        scope.launch {
            LocalPlaylistStore.playlists.drop(1).debounce(4_000).collect {
                if (AniAccount.current.value != null) syncNow()
            }
        }
        scope.launch {
            Blocklist.entries.drop(1).debounce(4_000).collect {
                if (AniAccount.current.value != null) syncNow()
            }
        }
    }

    /** Pull, merge and push. Safe to call any time; concurrent calls run one after another. */
    suspend fun syncNow(): Boolean = mutex.withLock {
        if (!::app.isInitialized || AniAccount.current.value == null) return false
        _status.value = Status.SYNCING
        val ok = runCatching {
            repeat(3) {
                if (syncOnce()) return@runCatching true
            }
            false
        }.getOrDefault(false)
        _status.value = if (ok) Status.DONE else Status.FAILED
        if (ok) _lastSynced.value = System.currentTimeMillis()
        ok
    }

    /** One round; false when another device wrote in between and it must be retried. */
    private suspend fun syncOnce(): Boolean {
        val token = AniAccount.token ?: return true
        val remoteResponse = AniAccount.request("GET", "/account/sync", null, token)
        val remoteAt = (remoteResponse["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
        val remote = (remoteResponse["data"] as? JsonObject)?.let(::sections) ?: Sections()
        val base = if (AniAccount.syncedAt == 0L) Sections() else readBase()
        val local = localSections()

        val merged = Sections(
            playlists = SyncMerge.merge(base.playlists, local.playlists, remote.playlists),
            smartRules = SyncMerge.merge(base.smartRules, local.smartRules, remote.smartRules),
            blocked = SyncMerge.merge(base.blocked, local.blocked, remote.blocked),
        )
        if (merged != local) applyLocally(merged)
        var updatedAt = remoteAt
        if (merged != remote || remoteAt == 0L) {
            try {
                val put = AniAccount.request(
                    "PUT",
                    "/account/sync",
                    buildJsonObject {
                        put("data", toJson(merged))
                        put("baseUpdatedAt", remoteAt)
                    },
                    token,
                )
                updatedAt = (put["updatedAt"] as? JsonPrimitive)?.longOrNull ?: remoteAt
            } catch (e: AniAccount.ConflictException) {
                return false
            }
        }
        writeBase(merged)
        AniAccount.syncedAt = updatedAt
        return true
    }

    // ---------------------------------------------------------------- model --

    /** Each synced kind of item, keyed by its id, in display order. */
    internal data class Sections(
        val playlists: Map<String, JsonElement> = emptyMap(),
        val smartRules: Map<String, JsonElement> = emptyMap(),
        /** "Don't play this": `s:<videoId>` and `a:<artist>`, each with its display name. */
        val blocked: Map<String, JsonElement> = emptyMap(),
    )

    private fun sections(doc: JsonObject) = Sections(
        playlists = byId(doc["playlists"]),
        smartRules = byId(doc["smartRules"]),
        blocked = byId(doc["blocked"]),
    )

    private fun toJson(s: Sections) = buildJsonObject {
        put("v", 1)
        put("playlists", JsonArray(s.playlists.values.toList()))
        put("smartRules", JsonArray(s.smartRules.values.toList()))
        put("blocked", JsonArray(s.blocked.values.toList()))
    }

    private fun byId(element: JsonElement?): Map<String, JsonElement> {
        val array = element as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, JsonElement>()
        for (item in array) {
            val id = ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: continue
            out[id] = item
        }
        return out
    }

    private fun isGenerated(id: String) = id.startsWith("smart_")

    private fun localSections(): Sections {
        val playlists = LocalPlaylistStore.exportJson()
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val rules = CustomSmartRules.encode(CustomSmartRules.load(app))
        return Sections(
            playlists = byId(playlists).filterKeys { !isGenerated(it) },
            smartRules = byId(runCatching { Json.parseToJsonElement(rules) }.getOrNull()),
            blocked = blockedItems(Blocklist.entries.value),
        )
    }

    private fun blockedItems(e: Blocklist.Entries): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        e.songs.forEach { (id, name) -> out["s:$id"] = item("s:$id", name) }
        e.artists.forEach { (key, name) -> out["a:$key"] = item("a:$key", name) }
        return out
    }

    private fun item(id: String, name: String) = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    private suspend fun applyLocally(merged: Sections) {
        // Generated playlists stay as they are, after the user's own.
        val generated = LocalPlaylistStore.exportJson()
            ?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
            .orEmpty()
            .filter { item ->
                val id = ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                id != null && isGenerated(id)
            }
        LocalPlaylistStore.importJson(JsonArray(merged.playlists.values.toList() + generated).toString())
        val rulesBefore = CustomSmartRules.encode(CustomSmartRules.load(app))
        val rulesAfter = JsonArray(merged.smartRules.values.toList()).toString()
        if (blockedItems(Blocklist.entries.value) != merged.blocked) {
            fun name(v: JsonElement) = ((v as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val entries = Blocklist.Entries(
                songs = merged.blocked.filterKeys { it.startsWith("s:") }.map { (k, v) -> k.removePrefix("s:") to name(v) }.toMap(),
                artists = merged.blocked.filterKeys { it.startsWith("a:") }.map { (k, v) -> k.removePrefix("a:") to name(v) }.toMap(),
            )
            Blocklist.load(entries)
            Blocklist.onChange?.invoke(entries)
        }
        if (byId(Json.parseToJsonElement(rulesBefore)) != merged.smartRules) {
            CustomSmartRules.save(app, CustomSmartRules.decode(rulesAfter))
            runCatching { SmartPlaylists.refresh(app) }
        }
    }

    private val baseFile get() = File(app.filesDir, "account_sync_base.json")

    private fun readBase(): Sections = runCatching {
        sections(Json.parseToJsonElement(baseFile.readText()).jsonObject)
    }.getOrDefault(Sections())

    private fun writeBase(s: Sections) {
        runCatching { baseFile.writeText(toJson(s).toString()) }
    }

    /** Forgets the sync point, for sign-out: the next account starts fresh. */
    fun reset() {
        if (::app.isInitialized) runCatching { baseFile.delete() }
        _lastSynced.value = 0L
        _status.value = Status.IDLE
    }
}

/** The per-item three-way merge behind [AccountSync]; pure, so it can be tested on its own. */
internal object SyncMerge {
    fun <T> merge(base: Map<String, T>, local: Map<String, T>, remote: Map<String, T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        // Local order first, then whatever only the other side has, in its order.
        for (id in (local.keys + remote.keys + base.keys)) {
            if (id in out) continue
            val b = base[id]
            val l = local[id]
            val r = remote[id]
            val pick = when {
                l == r -> l
                l == b -> r // only the server's side changed (an edit, or a deletion)
                r == b -> l // only this device changed it
                else -> l ?: r // both changed: this device's edit wins over a deletion elsewhere and vice versa
            }
            if (pick != null) out[id] = pick
        }
        return out
    }
}
