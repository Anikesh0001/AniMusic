package com.music.bitchord.data.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/**
 * Public Apple Music playlists, albums and songs, read from the web page.
 *
 * Two copies of the tracklist are in every page and Apple reshuffles both
 * from time to time, so both are read: the app's own
 * `<script id="serialized-server-data">` (artist, album and runtime per row)
 * first, and the schema.org JSON-LD that [GenericPageImporter] reads as the
 * fallback. A `?i=<trackId>` on an album link names one song on it, and only
 * that song is imported.
 */
object AppleMusicImporter : PlaylistImporter {

    override val service = ImportService.APPLE_MUSIC

    private val KINDS = setOf("album", "playlist", "song")

    override fun canHandle(url: String): Boolean {
        if (!ImportUrls.hostIs(url, "music.apple.com", "itunes.apple.com")) return false
        return ImportUrls.segmentsWithoutLocale(url).firstOrNull() in KINDS
    }

    override suspend fun fetch(url: String): ImportedCollection =
        parse(ImportHttp.get(url), url) ?: throw ImportException(ImportException.Reason.NOT_FOUND)

    internal fun parse(html: String, url: String): ImportedCollection? {
        val songId = ImportUrls.parse(url)?.queryParameter("i")
        val (page, byId) = fromServerData(html, url)
            ?: (GenericPageImporter.parse(html, url)?.copy(service = service) ?: return null) to emptyMap()
        if (songId == null) return page
        // An album link that names one of its songs is a link to the song.
        val only = byId[songId] ?: return page
        return page.copy(title = only.title, tracks = listOf(only))
    }

    /** The page as the app's own data has it, and its rows by Apple's song id. */
    private fun fromServerData(html: String, url: String): Pair<ImportedCollection, Map<String, ImportTrack>>? {
        val raw = Jsoup.parse(html).selectFirst("script#serialized-server-data")?.data() ?: return null
        val root = runCatching { ImportHttp.json.parseToJsonElement(raw) }.getOrNull() ?: return null
        // `{"data":[{"intent":…,"data":{"sections":[…]}}]}` today; a bare array before that.
        val pageData = ((root as? JsonObject)?.get("data") ?: root)
            .let { (it as? JsonArray)?.firstOrNull() as? JsonObject }
            ?.get("data") as? JsonObject
            ?: return null
        val sections = (pageData["sections"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val isAlbum = ImportUrls.segmentsWithoutLocale(url).firstOrNull() == "album"

        val songHeader = sections.firstOrNull { it.text("itemKind") == "songDetailHeader" }?.firstItem()
        if (songHeader != null) {
            val title = songHeader.text("title") ?: return null
            val track = ImportTrack(
                title = title,
                artist = songHeader.text("artists").orEmpty(),
                album = songHeader.text("album"),
                durationMs = GenericPageImporter.parse(html, url)?.tracks?.firstOrNull()?.durationMs,
            )
            return ImportedCollection(
                service = service,
                sourceUrl = url,
                title = title,
                coverUrl = artworkOf(songHeader),
                tracks = listOf(track),
            ) to emptyMap()
        }

        val header = sections.firstOrNull { it.text("itemKind") == "containerDetailHeaderLockup" }?.firstItem()
        val headerTitle = header?.text("title")
        val headerArtist = (header?.get("subtitleLinks") as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.text("title") }
        val byId = LinkedHashMap<String, ImportTrack>()
        val rows = sections.filter { it.text("itemKind") == "trackLockup" }
            .flatMap { (it["items"] as? JsonArray).orEmpty() }
            .mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val title = item.text("title") ?: return@mapNotNull null
                val album = if (isAlbum) {
                    headerTitle
                } else {
                    (item["tertiaryLinks"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.text("title") }
                }
                val track = ImportTrack(
                    title = title,
                    artist = item.text("artistName") ?: headerArtist.orEmpty(),
                    album = album,
                    durationMs = item.text("duration")?.toLongOrNull()?.takeIf { it > 0 },
                )
                val id = ((item["contentDescriptor"] as? JsonObject)?.get("identifiers") as? JsonObject)
                    ?.text("storeAdamID")
                if (id != null) byId[id] = track
                track
            }
        if (rows.isEmpty()) return null
        return ImportedCollection(
            service = service,
            sourceUrl = url,
            title = headerTitle ?: service.label,
            description = headerArtist,
            coverUrl = header?.let(::artworkOf),
            tracks = rows,
        ) to byId
    }

    /** Apple's artwork URLs are templates: `…/{w}x{h}bb.{f}`. */
    private fun artworkOf(item: JsonObject): String? =
        ((item["artwork"] as? JsonObject)?.get("dictionary") as? JsonObject)?.text("url")
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")

    private fun JsonObject.firstItem(): JsonObject? = (this["items"] as? JsonArray)?.firstOrNull() as? JsonObject

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
