package com.music.bitchord.data.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Public SoundCloud sets (playlists and albums) and single tracks.
 *
 * The page's `window.__sc_hydration` holds the set with its first few
 * tracks described in full and the rest as bare ids. The same hydration
 * carries the anonymous `client_id` the web player itself uses
 * (`apiClient`), and `api-v2.soundcloud.com/tracks?ids=` describes the rest
 * fifty at a time. If that second step fails, the described tracks are
 * imported and the remainder reported as missing rather than the whole
 * import failing.
 */
object SoundCloudImporter : PlaylistImporter {

    override val service = ImportService.SOUNDCLOUD

    private const val API = "https://api-v2.soundcloud.com"
    private const val BATCH = 50

    /** First path segments that are site pages, not user names. */
    private val RESERVED = setOf(
        "discover", "search", "you", "charts", "stream", "upload", "pages", "tags", "people",
        "messages", "notifications", "settings", "terms-of-use", "jobs", "imprint", "mobile", "pro",
    )

    override fun canHandle(url: String): Boolean {
        if (!ImportUrls.hostIs(url, "soundcloud.com") || ImportUrls.host(url) == "on.soundcloud.com") return false
        val segments = ImportUrls.segments(url)
        if (segments.firstOrNull() in RESERVED) return false
        return when (segments.size) {
            2 -> segments[1] !in setOf("sets", "albums", "tracks", "likes", "reposts", "popular-tracks", "followers", "following")
            3 -> segments[1] == "sets"
            else -> false
        }
    }

    override suspend fun fetch(url: String): ImportedCollection {
        val page = parse(ImportHttp.get(url), url) ?: throw ImportException(ImportException.Reason.NOT_FOUND)
        if (page.missingIds.isEmpty()) return page.collection
        val filled = page.clientId?.let { id -> runCatching { describe(page.missingIds, id) }.getOrNull() }
            ?: return page.collection.copy(missingCount = page.missingIds.size)
        // Back into the set's own order, with any the API would not describe left out.
        val tracks = page.order.mapNotNull { trackId -> page.described[trackId] ?: filled[trackId] }
        return page.collection.copy(
            tracks = tracks,
            missingCount = page.order.size - tracks.size,
        )
    }

    /** What the page itself said. */
    internal data class Page(
        val collection: ImportedCollection,
        /** Every track id in the set's order. */
        val order: List<Long>,
        val described: Map<Long, ImportTrack>,
        val missingIds: List<Long>,
        val clientId: String?,
    )

    internal fun parse(html: String, url: String): Page? {
        val raw = HYDRATION.find(html)?.groupValues?.get(1) ?: return null
        val entries = runCatching { ImportHttp.json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
        fun data(kind: String) = entries.firstOrNull { (it as? JsonObject)?.text("hydratable") == kind }
            ?.let { (it as JsonObject)["data"] as? JsonObject }
        val clientId = data("apiClient")?.text("id")

        data("sound")?.let { sound ->
            val track = parseTrack(sound) ?: return null
            val collection = ImportedCollection(
                service = service,
                sourceUrl = url,
                title = track.title,
                coverUrl = sound.text("artwork_url")?.let(::largeArtwork),
                tracks = listOf(track),
            )
            return Page(collection, emptyList(), emptyMap(), emptyList(), clientId)
        }

        val set = data("playlist") ?: return null
        val rows = (set["tracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val order = rows.mapNotNull { it.text("id")?.toLongOrNull() }
        val described = LinkedHashMap<Long, ImportTrack>()
        rows.forEach { row ->
            val id = row.text("id")?.toLongOrNull() ?: return@forEach
            parseTrack(row)?.let { described[id] = it }
        }
        val collection = ImportedCollection(
            service = service,
            sourceUrl = set.text("permalink_url") ?: url,
            title = set.text("title") ?: service.label,
            description = (set["user"] as? JsonObject)?.text("username"),
            coverUrl = set.text("artwork_url")?.let(::largeArtwork),
            tracks = order.mapNotNull { described[it] },
        )
        return Page(collection, order, described, order.filter { it !in described }, clientId)
    }

    /** `/tracks?ids=` for [ids], fifty per request, by id. */
    private suspend fun describe(ids: List<Long>, clientId: String): Map<Long, ImportTrack> {
        val out = HashMap<Long, ImportTrack>()
        for (batch in ids.chunked(BATCH)) {
            val requestUrl = "$API/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("ids", batch.joinToString(","))
                .addQueryParameter("client_id", clientId)
                .build()
                .toString()
            out += parseTrackBatch(ImportHttp.get(requestUrl))
        }
        return out
    }

    internal fun parseTrackBatch(body: String): Map<Long, ImportTrack> {
        val array = runCatching { ImportHttp.json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: throw ImportException(ImportException.Reason.PARSE)
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id")?.toLongOrNull() ?: return@mapNotNull null
            parseTrack(obj)?.let { id to it }
        }.toMap()
    }

    /**
     * One track. SoundCloud uploads are titled by whoever uploaded them, and
     * "Artist - Title" is the convention; label uploads carry the real credit
     * in `publisher_metadata` instead, which wins when present.
     */
    internal fun parseTrack(obj: JsonObject): ImportTrack? {
        val rawTitle = obj.text("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val publisher = obj["publisher_metadata"] as? JsonObject
        val uploader = (obj["user"] as? JsonObject)?.text("username").orEmpty()
        val credited = publisher?.text("artist")
        val (title, artist) = when {
            // "Sweet Medicine w/ ØDYSSEE - Breezin'": the uploader's prefix
            // names the credited artist and often a guest; the song is after it.
            credited != null && " - " in rawTitle &&
                rawTitle.substringBefore(" - ").contains(credited, ignoreCase = true) ->
                rawTitle.substringAfter(" - ").trim() to rawTitle.substringBefore(" - ").trim()
            credited != null -> rawTitle to credited
            " - " in rawTitle -> rawTitle.substringAfter(" - ").trim() to rawTitle.substringBefore(" - ").trim()
            else -> rawTitle to uploader
        }
        return ImportTrack(
            title = title,
            artist = artist,
            album = publisher?.text("album_title") ?: publisher?.text("release_title"),
            // `duration` is the 30-second snippet on preview-only tracks.
            durationMs = (obj.text("full_duration") ?: obj.text("duration"))?.toLongOrNull()?.takeIf { it > 0 },
            isrc = publisher?.text("isrc"),
        )
    }

    /** `-large` is 100px; `-t500x500` is the same artwork at a size worth showing. */
    private fun largeArtwork(url: String) = url.replace("-large.", "-t500x500.")

    private val HYDRATION = Regex("""window\.__sc_hydration\s*=\s*(\[.*?]);\s*</script>""", RegexOption.DOT_MATCHES_ALL)

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
