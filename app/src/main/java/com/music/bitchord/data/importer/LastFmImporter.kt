package com.music.bitchord.data.importer

import com.music.bitchord.BuildConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A Last.fm user's loved tracks or all-time top tracks, through
 * `user.getLovedTracks` / `user.getTopTracks`. Both are public for any
 * profile and need only the app's API key, so the option exists only in
 * builds that were given one ([available]).
 */
object LastFmImporter {

    enum class Kind { LOVED, TOP }

    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 10

    val available: Boolean get() = BuildConfig.LASTFM_API_KEY.isNotBlank()

    suspend fun fetch(user: String, kind: Kind): ImportedCollection {
        val name = user.trim().takeIf { it.isNotEmpty() } ?: throw ImportException(ImportException.Reason.INVALID_LINK)
        if (!available) throw ImportException(ImportException.Reason.UNSUPPORTED)
        val tracks = mutableListOf<ImportTrack>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= MAX_PAGES) {
            val url = API.toHttpUrl().newBuilder()
                .addQueryParameter("method", if (kind == Kind.LOVED) "user.getlovedtracks" else "user.gettoptracks")
                .addQueryParameter("user", name)
                .addQueryParameter("api_key", BuildConfig.LASTFM_API_KEY)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .build()
                .toString()
            val (rows, total) = parsePage(ImportHttp.get(url), kind)
            tracks += rows
            pages = total
            if (rows.isEmpty()) break
            page++
        }
        if (tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        return ImportedCollection(
            service = ImportService.LASTFM,
            sourceUrl = "https://www.last.fm/user/$name/" + if (kind == Kind.LOVED) "loved" else "library/tracks",
            title = name,
            tracks = tracks,
        )
    }

    /** One page's rows and the total page count. Last.fm errors come back as `{"error":6,…}`. */
    internal fun parsePage(body: String, kind: Kind): Pair<List<ImportTrack>, Int> {
        val root = runCatching { ImportHttp.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw ImportException(ImportException.Reason.PARSE)
        (root["error"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { code ->
            throw ImportException(
                when (code) {
                    6 -> ImportException.Reason.NOT_FOUND
                    29 -> ImportException.Reason.RATE_LIMITED
                    else -> ImportException.Reason.NETWORK
                },
            )
        }
        val box = root[if (kind == Kind.LOVED) "lovedtracks" else "toptracks"] as? JsonObject
            ?: return emptyList<ImportTrack>() to 0
        val rows = when (val t = box["track"]) {
            is JsonArray -> t.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(t) // a single-row page is an object, not an array
            else -> emptyList()
        }.mapNotNull { t ->
            val title = (t["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = when (val a = t["artist"]) {
                is JsonObject -> (a["name"] as? JsonPrimitive ?: a["#text"] as? JsonPrimitive)?.content
                is JsonPrimitive -> a.content
                else -> null
            }.orEmpty()
            ImportTrack(
                title = title,
                artist = artist,
                // Top tracks state seconds; "0" means unknown.
                durationMs = (t["duration"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            )
        }
        val pages = ((box["@attr"] as? JsonObject)?.get("totalPages") as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        return rows to pages
    }
}
