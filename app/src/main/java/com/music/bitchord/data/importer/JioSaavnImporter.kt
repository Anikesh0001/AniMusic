package com.music.bitchord.data.importer

import com.music.bitchord.data.jiosaavn.JioSaavnService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Public JioSaavn playlists, albums and songs, through the same `api.php`
 * the app already streams from ([JioSaavnService.getByToken]).
 *
 * Every JioSaavn link ends in a token that `webapi.get` accepts as it is:
 * `/featured/<slug>/<token>` and `/s/playlist/<user>/<slug>/<token>` are
 * playlists, `/album/<slug>/<token>` an album, `/song/<slug>/<token>` a song.
 * The catalogue writes HTML entities into its titles (`&quot;`), which are
 * decoded before matching.
 */
object JioSaavnImporter : PlaylistImporter {

    override val service = ImportService.JIOSAAVN

    enum class Kind(val type: String) { PLAYLIST("playlist"), ALBUM("album"), SONG("song") }

    private const val PAGE_SIZE = 50
    private const val MAX_PAGES = 40

    override fun canHandle(url: String): Boolean = extract(url) != null

    fun extract(url: String): Pair<Kind, String>? {
        if (!ImportUrls.hostIs(url, "jiosaavn.com", "saavn.com")) return null
        val segments = ImportUrls.segments(url)
        val kind = when (segments.firstOrNull()) {
            "featured" -> Kind.PLAYLIST
            "album" -> Kind.ALBUM
            "song" -> Kind.SONG
            "s" -> if (segments.getOrNull(1) == "playlist") Kind.PLAYLIST else return null
            else -> return null
        }
        // The token is always last, after a human-readable slug.
        val token = segments.lastOrNull()?.takeIf { segments.size >= 3 && TOKEN.matches(it) } ?: return null
        return kind to token
    }

    private val TOKEN = Regex("""[A-Za-z0-9_,\-]{6,}""")

    override suspend fun fetch(url: String): ImportedCollection {
        val (kind, token) = extract(url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        if (kind == Kind.SONG) {
            val body = JioSaavnService.getByToken(token, kind.type)
                ?: throw ImportException(ImportException.Reason.NETWORK)
            val track = parseSongs(body).firstOrNull() ?: throw ImportException(ImportException.Reason.NOT_FOUND)
            return ImportedCollection(service, url, track.title, tracks = listOf(track))
        }
        var first: Page? = null
        val tracks = mutableListOf<ImportTrack>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = JioSaavnService.getByToken(token, kind.type, page, PAGE_SIZE)
                ?: if (tracks.isEmpty()) throw ImportException(ImportException.Reason.NETWORK) else break
            val parsed = parseCollection(body) ?: break
            if (first == null) first = parsed
            if (parsed.tracks.isEmpty()) break
            tracks += parsed.tracks
            if (tracks.size >= parsed.total) break
            page++
        }
        val head = first
        if (head == null || tracks.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        return ImportedCollection(
            service = service,
            sourceUrl = url,
            title = head.title,
            description = head.subtitle,
            coverUrl = head.image,
            tracks = tracks,
            missingCount = (head.total - tracks.size).coerceAtLeast(0),
        )
    }

    internal data class Page(
        val title: String,
        val subtitle: String?,
        val image: String?,
        val total: Int,
        val tracks: List<ImportTrack>,
    )

    /** One page of a playlist or album. Null when the body isn't JSON at all. */
    internal fun parseCollection(body: String): Page? {
        val root = runCatching { ImportHttp.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        // An unknown token answers with `"list": ""` rather than an error.
        val rows = (root["list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseSong) }
        return Page(
            title = root.text("title")?.let(GenericPageImporter::decode) ?: service.label,
            subtitle = root.text("subtitle")?.let(GenericPageImporter::decode),
            image = root.text("image")?.replace("150x150", "500x500"),
            total = root.text("list_count")?.toIntOrNull() ?: rows.size,
            tracks = rows,
        )
    }

    /** A `webapi.get` song answer: `{"songs":[…]}`. */
    internal fun parseSongs(body: String): List<ImportTrack> {
        val root = runCatching { ImportHttp.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return (root["songs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseSong) }
    }

    private fun parseSong(obj: JsonObject): ImportTrack? {
        val title = obj.text("title")?.let(GenericPageImporter::decode)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val info = obj["more_info"] as? JsonObject
        val primary = ((info?.get("artistMap") as? JsonObject)?.get("primary_artists") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.text("name") }
            .map(GenericPageImporter::decode)
        // `subtitle` is "Artist, Artist - Album" when the artist map is missing.
        val artist = primary.joinToString(", ").ifEmpty {
            obj.text("subtitle")?.let(GenericPageImporter::decode)?.substringBefore(" - ").orEmpty()
        }
        return ImportTrack(
            title = title,
            artist = artist,
            album = info?.text("album")?.let(GenericPageImporter::decode),
            durationMs = info?.text("duration")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
