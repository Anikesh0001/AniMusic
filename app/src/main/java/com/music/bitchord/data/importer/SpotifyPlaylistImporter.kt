package com.music.bitchord.data.importer

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Public Spotify playlists, read from the embed page without a login.
 *
 * The embed page's `__NEXT_DATA__` holds the first 100 tracks; past that, the
 * web player's own pathfinder query is asked with the anonymous token the
 * embed hands out.
 */
object SpotifyPlaylistImporter : PlaylistImporter {

    override val service = ImportService.SPOTIFY

    private const val USER_AGENT = ImportHttp.USER_AGENT

    private val json = ImportHttp.json

    /** A Spotify playlist id is 22 base-62 characters. */
    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    override fun canHandle(url: String): Boolean = extractPlaylistId(url) != null

    override suspend fun fetch(url: String): ImportedCollection {
        val id = extractPlaylistId(url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        val (title, tracks) = fetchPlaylistTracks(id)
        return ImportedCollection(
            service = service,
            sourceUrl = "https://open.spotify.com/playlist/$id",
            title = title,
            tracks = tracks,
        )
    }

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("spotify:playlist:")) {
            val id = trimmed.removePrefix("spotify:playlist:").substringBefore("?").substringBefore("/")
            return id.takeIf { PLAYLIST_ID.matches(it) }
        }

        val uri = ImportUrls.parse(trimmed) ?: return null
        if (uri.scheme.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host.lowercase())) return null
        val segments = uri.pathSegments
        val playlistIdx = segments.indexOf("playlist")
        if (playlistIdx == -1 || playlistIdx + 1 >= segments.size) return null
        return segments[playlistIdx + 1].takeIf { PLAYLIST_ID.matches(it) }
    }

    /**
     * Fetches public Spotify playlist details from Spotify's embed endpoint.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<ImportTrack>> =
        withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val html = ImportHttp.get(url)

            // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = scriptRegex.find(html)
                ?: throw ImportException(ImportException.Reason.NOT_FOUND)

            val jsonString = match.groupValues[1]
            val root = runCatching { json.parseToJsonElement(jsonString).jsonObject }.getOrNull()
                ?: throw ImportException(ImportException.Reason.PARSE)

            val props = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
            val stateData = props?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject
                ?: props?.get("entity")?.jsonObject
                ?: throw ImportException(ImportException.Reason.NOT_FOUND)

            val playlistTitle = stateData["name"]?.jsonPrimitive?.content
                ?: stateData["title"]?.jsonPrimitive?.content
                ?: "Imported Spotify Playlist"

            val trackListJson = stateData["trackList"]?.jsonArray
                ?: stateData["tracks"]?.jsonArray
                ?: JsonArray(emptyList())

            val tracks = mutableListOf<ImportTrack>()
            for (element in trackListJson) {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content
                    ?: continue
                val subtitle = obj["subtitle"]?.jsonPrimitive?.content
                    ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                        it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    }
                    ?: ""
                if (title.isNotBlank()) {
                    tracks.add(ImportTrack(title = title.trim(), artist = subtitle.trim()))
                }
            }

            if (tracks.isEmpty()) {
                throw ImportException(ImportException.Reason.NOT_FOUND)
            }

            // The embed page stops at its first 100 tracks. The web player's
            // own query, with the anonymous token the embed hands out, pages
            // through the rest; if it refuses, the 100 already read stand.
            if (tracks.size >= EMBED_TRACK_LIMIT) {
                val token = props?.get("state")?.jsonObject
                    ?.get("settings")?.jsonObject
                    ?.get("session")?.jsonObject
                    ?.get("accessToken")?.jsonPrimitive?.content
                if (token != null) {
                    runCatching { fetchRemainingTracks(playlistId, token, tracks.size) }
                        .getOrNull()
                        ?.let { tracks.addAll(it) }
                }
            }

            Pair(playlistTitle, tracks)
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
                out.add(ImportTrack(title = title, artist = artist))
            }
            val total = content["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: break
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }
}
