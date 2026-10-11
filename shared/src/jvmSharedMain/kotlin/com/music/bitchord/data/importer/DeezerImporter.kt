package com.music.bitchord.data.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Public Deezer playlists, albums and tracks, through Deezer's open JSON API
 * (`api.deezer.com`), which needs no key for public content.
 *
 * Tracks are read from the `/tracks` sub-resource rather than the list
 * embedded in the playlist itself: the embedded one stops at an arbitrary
 * page and carries no `next` link, while `/tracks` pages through all of it.
 * Every row carries its ISRC and runtime; the runtime goes to the matcher.
 */
object DeezerImporter : PlaylistImporter {

    override val service = ImportService.DEEZER

    enum class Kind(val path: String) { PLAYLIST("playlist"), ALBUM("album"), TRACK("track") }

    private const val API = "https://api.deezer.com"
    private const val PAGE_SIZE = 100
    /** A runaway `next` chain stops here (Deezer playlists cap at 2,000 tracks). */
    private const val MAX_PAGES = 40

    override fun canHandle(url: String): Boolean = extract(url) != null

    /** `deezer.com/{lang}/playlist/<id>`, `/album/<id>`, `/track/<id>`, with or without the locale. */
    fun extract(url: String): Pair<Kind, String>? {
        if (!ImportUrls.hostIs(url, "deezer.com")) return null
        val segments = ImportUrls.segmentsWithoutLocale(url)
        val kind = Kind.entries.firstOrNull { it.path == segments.getOrNull(0) } ?: return null
        val id = segments.getOrNull(1)?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        return kind to id
    }

    override suspend fun fetch(url: String): ImportedCollection {
        val (kind, id) = extract(url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        val head = getJson("$API/${kind.path}/$id")
        val sourceUrl = "https://www.deezer.com/${kind.path}/$id"
        if (kind == Kind.TRACK) {
            val track = parseTrack(head) ?: throw ImportException(ImportException.Reason.NOT_FOUND)
            return ImportedCollection(
                service = service,
                sourceUrl = sourceUrl,
                title = track.title,
                coverUrl = (head["album"] as? JsonObject)?.string("cover_xl"),
                tracks = listOf(track),
            )
        }
        val album = head.string("title")?.takeIf { kind == Kind.ALBUM }
        val tracks = mutableListOf<ImportTrack>()
        var next: String? = "$API/${kind.path}/$id/tracks?limit=$PAGE_SIZE"
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val page = parseTracksPage(getJson(next), album)
            tracks += page.first
            next = page.second
        }
        if (tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        return ImportedCollection(
            service = service,
            sourceUrl = sourceUrl,
            title = head.string("title") ?: service.label,
            description = head.string("description")?.takeIf { it.isNotBlank() }
                ?: (head["artist"] as? JsonObject)?.string("name"),
            coverUrl = head.string("picture_xl") ?: head.string("cover_xl"),
            tracks = tracks,
        )
    }

    private suspend fun getJson(url: String): JsonObject {
        val root = runCatching { ImportHttp.json.parseToJsonElement(ImportHttp.get(url)).jsonObject }
            .getOrElse { if (it is ImportException) throw it else throw ImportException(ImportException.Reason.PARSE, it) }
        checkError(root)
        return root
    }

    /**
     * Deezer reports errors as HTTP 200 with an `error` object. Code 4 is its
     * request quota; 800 ("no data") is a private or deleted item.
     */
    internal fun checkError(root: JsonObject) {
        val error = root["error"] as? JsonObject ?: return
        val code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        throw ImportException(
            when (code) {
                4 -> ImportException.Reason.RATE_LIMITED
                else -> ImportException.Reason.NOT_FOUND
            },
        )
    }

    /** One `/tracks` page: its rows, and the next page's URL if there is one. */
    internal fun parseTracksPage(root: JsonObject, album: String? = null): Pair<List<ImportTrack>, String?> {
        val rows = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { obj -> parseTrack(obj, album) } }
        return rows to root.string("next")
    }

    internal fun parseTrack(obj: JsonObject, album: String? = null): ImportTrack? {
        val title = obj.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = (obj["artist"] as? JsonObject)?.string("name").orEmpty().trim()
        return ImportTrack(
            title = title,
            artist = artist,
            album = album ?: (obj["album"] as? JsonObject)?.string("title"),
            durationMs = (obj["duration"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            isrc = obj.string("isrc"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
