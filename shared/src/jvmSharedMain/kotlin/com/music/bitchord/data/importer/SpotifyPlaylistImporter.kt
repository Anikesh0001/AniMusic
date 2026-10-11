package com.music.bitchord.data.importer

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Public Spotify playlists, albums and tracks, read from the embed page
 * without a login.
 *
 * The embed page's `__NEXT_DATA__` holds the first 100 tracks; past that, the
 * web player's own pathfinder query is asked with the anonymous token the
 * embed hands out. Albums and tracks use the same page under
 * `/embed/album/` and `/embed/track/`, with the same entity shape.
 */
object SpotifyPlaylistImporter : PlaylistImporter {

    override val service = ImportService.SPOTIFY

    private const val USER_AGENT = ImportHttp.USER_AGENT

    private val json = ImportHttp.json

    /** A Spotify playlist id is 22 base-62 characters. */
    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    /** What a Spotify link names. The value is the path segment the embed page is fetched under. */
    enum class Kind(val path: String) { PLAYLIST("playlist"), ALBUM("album"), TRACK("track") }

    override fun canHandle(url: String): Boolean = extract(url) != null

    override suspend fun fetch(url: String): ImportedCollection {
        val (kind, id) = extract(url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        val sourceUrl = "https://open.spotify.com/${kind.path}/$id"
        if (kind == Kind.PLAYLIST) {
            val (title, tracks) = fetchPlaylistTracks(id)
            return ImportedCollection(service = service, sourceUrl = sourceUrl, title = title, tracks = tracks)
        }
        val embed = parseEmbed(ImportHttp.get("https://open.spotify.com/embed/${kind.path}/$id"))
        return ImportedCollection(
            service = service,
            sourceUrl = sourceUrl,
            title = embed.title,
            description = embed.subtitle,
            coverUrl = embed.coverUrl,
            tracks = embed.tracks,
        )
    }

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? =
        extract(input)?.takeIf { it.first == Kind.PLAYLIST }?.second

    /**
     * The kind and id a Spotify link or URI names: `open.spotify.com/playlist/…`,
     * `/album/…`, `/track/…` (with or without an `intl-xx` segment or
     * `/embed/`), and `spotify:playlist:…` / `spotify:album:…` / `spotify:track:…`.
     */
    fun extract(input: String): Pair<Kind, String>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("spotify:")) {
            val parts = trimmed.substringBefore("?").split(":")
            val kind = Kind.entries.firstOrNull { it.path == parts.getOrNull(1) } ?: return null
            val id = parts.getOrNull(2)?.substringBefore("/") ?: return null
            return id.takeIf { PLAYLIST_ID.matches(it) }?.let { kind to it }
        }

        val uri = ImportUrls.parse(trimmed) ?: return null
        if (uri.scheme.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host.lowercase())) return null
        val segments = uri.pathSegments
        for (kind in Kind.entries) {
            val idx = segments.indexOf(kind.path)
            if (idx == -1 || idx + 1 >= segments.size) continue
            return segments[idx + 1].takeIf { PLAYLIST_ID.matches(it) }?.let { kind to it }
        }
        return null
    }

    /** What an embed page said: the entity, its rows, and the anonymous token for paging. */
    internal data class Embed(
        val title: String,
        val subtitle: String?,
        val coverUrl: String?,
        val tracks: List<ImportTrack>,
        val accessToken: String?,
    )

    /**
     * Reads an embed page's `__NEXT_DATA__`. A playlist or album lists its
     * rows in `trackList`; a track page *is* the row, with its artists in
     * `artists`.
     */
    internal fun parseEmbed(html: String): Embed {
        // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
        val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val match = scriptRegex.find(html)
            ?: throw ImportException(ImportException.Reason.NOT_FOUND)
        val root = runCatching { json.parseToJsonElement(match.groupValues[1]).jsonObject }.getOrNull()
            ?: throw ImportException(ImportException.Reason.PARSE)
        val props = root["props"]?.jsonObject?.get("pageProps")?.jsonObject
        val entity = props?.get("state")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("entity")?.jsonObject
            ?: props?.get("entity")?.jsonObject
            ?: throw ImportException(ImportException.Reason.NOT_FOUND)
        val title = entity.string("name") ?: entity.string("title") ?: "Imported Spotify Playlist"
        val type = entity.string("type")
        val subtitle = entity.string("subtitle")
        val tracks = if (type == "track") {
            val artist = entity["artists"]?.jsonArray?.joinToString(", ") {
                it.jsonObject.string("name").orEmpty()
            }.orEmpty()
            listOf(ImportTrack(title = title.trim(), artist = artist.trim(), durationMs = entity.long("duration")))
        } else {
            val album = title.takeIf { type == "album" }
            (entity["trackList"]?.jsonArray ?: entity["tracks"]?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { element ->
                    val obj = element.jsonObject
                    val rowTitle = (obj.string("title") ?: obj.string("name"))?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val artist = obj.string("subtitle")
                        ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                            it.jsonObject.string("name").orEmpty()
                        }
                        ?: ""
                    ImportTrack(title = rowTitle, artist = artist.trim(), album = album, durationMs = obj.long("duration"))
                }
        }
        if (tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        val cover = entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()
            ?.jsonObject?.string("url")
            ?: entity["visualIdentity"]?.jsonObject?.get("image")?.jsonArray
                ?.maxByOrNull { it.jsonObject.long("maxWidth") ?: 0 }
                ?.jsonObject?.string("url")
        val token = props?.get("state")?.jsonObject
            ?.get("settings")?.jsonObject
            ?.get("session")?.jsonObject
            ?.string("accessToken")
        return Embed(title, subtitle, cover, tracks, token)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Fetches public Spotify playlist details from Spotify's embed endpoint.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<ImportTrack>> =
        withContext(Dispatchers.IO) {
            val embed = parseEmbed(ImportHttp.get("https://open.spotify.com/embed/playlist/$playlistId"))
            val tracks = embed.tracks.toMutableList()

            // The embed page stops at its first 100 tracks. The web player's
            // own query, with the anonymous token the embed hands out, pages
            // through the rest; if it refuses, the 100 already read stand.
            if (tracks.size >= EMBED_TRACK_LIMIT && embed.accessToken != null) {
                runCatching { fetchRemainingTracks(playlistId, embed.accessToken, tracks.size) }
                    .getOrNull()
                    ?.let { tracks.addAll(it) }
            }

            Pair(embed.title, tracks)
        }

    /** Tracks the embed page lists before it cuts off. */
    private const val EMBED_TRACK_LIMIT = 100
    private const val PAGE_SIZE = 100
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FETCH_PLAYLIST_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"

    /** Tracks from [start] to the end of the playlist, a page at a time. */
    private fun fetchRemainingTracks(
        playlistId: String,
        token: String,
        start: Int,
    ): List<ImportTrack> {
        val out = mutableListOf<ImportTrack>()
        var offset = start
        while (true) {
            val variables = """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$PAGE_SIZE,"enableWatchFeedEntrypoint":false}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$FETCH_PLAYLIST_HASH"}}"""
            val url = PATHFINDER_URL.toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "fetchPlaylist")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer")
                .header("Accept", "application/json")
                .build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val content = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("playlistV2")?.jsonObject?.get("content")?.jsonObject
                ?: break
            val items = content["items"]?.jsonArray ?: break
            for (item in items) {
                val data = item.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject ?: continue
                val title = data["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artist = data["artists"]?.jsonObject?.get("items")?.jsonArray
                    ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                    ?.joinToString(", ")
                    .orEmpty()
                val durationMs = data["trackDuration"]?.jsonObject?.get("totalMilliseconds")
                    ?.jsonPrimitive?.content?.toLongOrNull()
                val album = data["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                out.add(ImportTrack(title = title, artist = artist, album = album, durationMs = durationMs))
            }
            val total = content["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: break
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }
}
