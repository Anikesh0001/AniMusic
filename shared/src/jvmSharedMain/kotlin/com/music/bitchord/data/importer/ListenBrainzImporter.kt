package com.music.bitchord.data.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

/**
 * ListenBrainz playlists, through its open API: a user's own public
 * playlists and the ones made for them (Daily Jams, Weekly Exploration),
 * and any `listenbrainz.org/playlist/<mbid>` link. Every playlist is JSPF
 * with title, artist, release and runtime per row.
 */
object ListenBrainzImporter : PlaylistImporter {

    override val service = ImportService.LISTENBRAINZ

    private const val API = "https://api.listenbrainz.org/1"
    private val MBID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    /** A playlist in a user's list, before its tracks are read. */
    data class PlaylistSummary(val mbid: String, val title: String, val createdFor: Boolean)

    override fun canHandle(url: String): Boolean = playlistId(url) != null

    fun playlistId(url: String): String? {
        if (!ImportUrls.hostIs(url, "listenbrainz.org")) return null
        val segments = ImportUrls.segments(url)
        val at = segments.indexOf("playlist")
        return segments.getOrNull(at + 1)?.takeIf { at >= 0 && MBID.matches(it) }
    }

    override suspend fun fetch(url: String): ImportedCollection {
        val mbid = playlistId(url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        return playlist(mbid)
    }

    suspend fun playlist(mbid: String): ImportedCollection =
        parsePlaylist(ImportHttp.get("$API/playlist/$mbid"), mbid)
            ?: throw ImportException(ImportException.Reason.NOT_FOUND)

    /** [user]'s public playlists, then the ones ListenBrainz generated for them. */
    suspend fun playlistsOf(user: String): List<PlaylistSummary> {
        val name = URLEncoder.encode(user.trim(), "UTF-8")
        if (name.isEmpty()) throw ImportException(ImportException.Reason.INVALID_LINK)
        val own = parsePlaylistList(ImportHttp.get("$API/user/$name/playlists?count=100"), createdFor = false)
        val made = runCatching {
            parsePlaylistList(ImportHttp.get("$API/user/$name/playlists/createdfor?count=100"), createdFor = true)
        }.getOrDefault(emptyList())
        return (own + made).distinctBy { it.mbid }
    }

    internal fun parsePlaylistList(body: String, createdFor: Boolean): List<PlaylistSummary> {
        val root = runCatching { ImportHttp.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return (root["playlists"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val p = (entry as? JsonObject)?.get("playlist") as? JsonObject ?: return@mapNotNull null
            val mbid = p.text("identifier")?.let { MBID.find(it)?.value } ?: return@mapNotNull null
            PlaylistSummary(mbid, p.text("title") ?: mbid, createdFor)
        }
    }

    internal fun parsePlaylist(body: String, mbid: String): ImportedCollection? {
        val root = runCatching { ImportHttp.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val p = root["playlist"] as? JsonObject ?: return null
        val tracks = (p["track"] as? JsonArray).orEmpty().mapNotNull { element ->
            val t = element as? JsonObject ?: return@mapNotNull null
            val title = t.text("title") ?: return@mapNotNull null
            ImportTrack(
                title = title,
                artist = t.text("creator").orEmpty(),
                album = t.text("album"),
                durationMs = t.text("duration")?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
        if (tracks.isEmpty()) return null
        return ImportedCollection(
            service = service,
            sourceUrl = "https://listenbrainz.org/playlist/$mbid",
            title = p.text("title") ?: service.label,
            description = p.text("creator"),
            tracks = tracks,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
