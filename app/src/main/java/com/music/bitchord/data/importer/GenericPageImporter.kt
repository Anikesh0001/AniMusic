package com.music.bitchord.data.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Any public web page that describes its music in a standard way.
 *
 * Most music services render a crawler-readable copy of a playlist or album
 * page for search engines and link previews, and that copy speaks one of a
 * few shared vocabularies. This reads them, best first:
 *
 *  1. schema.org JSON-LD: a `MusicPlaylist` or `MusicAlbum` with its
 *     `track`/`tracks`, or a lone `MusicRecording`.
 *  2. Open Graph `music.song` pages: `og:title` plus `music:musician`.
 *  3. `music:song` tags on an album/playlist page: a song name, or a link
 *     whose slug is read as the title.
 *
 * It is the last importer in [ImporterRegistry]: anything no dedicated
 * importer claims is tried here, and a page offering none of the above is
 * "not supported yet".
 */
object GenericPageImporter : PlaylistImporter {

    /** Reported as the detected service when the host is one, otherwise [ImportService.WEB]. */
    override val service = ImportService.WEB

    override fun canHandle(url: String): Boolean = ImportUrls.parse(url) != null

    override suspend fun fetch(url: String): ImportedCollection {
        val html = ImportHttp.get(url, mapOf("Accept" to "text/html,application/xhtml+xml"))
        val page = parse(html, url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        return page.copy(service = ImportService.detect(url) ?: ImportService.WEB)
    }

    /** The music [html] describes, or null when it describes none in a way this can read. */
    fun parse(html: String, url: String): ImportedCollection? {
        val doc = Jsoup.parse(html, url)
        return fromJsonLd(doc, url) ?: fromOpenGraphSong(doc, url) ?: fromMusicSongTags(doc, url)
    }

    // ── 1. JSON-LD ──────────────────────────────────────────────────────────

    /** What a page's JSON-LD says about the release itself, for importers that read its rows elsewhere. */
    data class Header(val name: String?, val artist: String?, val image: String?)

    fun headerOf(doc: Document): Header? {
        val node = ldNodes(doc).firstOrNull { it.isType("MusicPlaylist", "MusicAlbum") } ?: return null
        return Header(
            name = node.text("name")?.let(::decode),
            artist = artistOf(node["byArtist"])?.let(::decode),
            image = imageOf(node["image"]),
        )
    }

    private fun ldNodes(doc: Document): List<JsonObject> =
        doc.select("script[type=application/ld+json]").flatMap { script ->
            val element = runCatching { ImportHttp.json.parseToJsonElement(script.data()) }.getOrNull()
            element?.let(::flatten).orEmpty()
        }

    private fun fromJsonLd(doc: Document, url: String): ImportedCollection? {
        val nodes = ldNodes(doc)
        val collection = nodes.firstOrNull { it.isType("MusicPlaylist", "MusicAlbum") }
        if (collection != null) {
            val album = collection.text("name")?.takeIf { collection.isType("MusicAlbum") }
            val albumArtist = artistOf(collection["byArtist"])
            val listed = recordingsOf(collection)
            val tracks = listed.mapNotNull { recordingToTrack(it, album, albumArtist) }
            if (tracks.isNotEmpty()) {
                val declared = (collection.text("numTracks") ?: collection.text("numberOfTracks"))?.toIntOrNull()
                return ImportedCollection(
                    service = ImportService.WEB,
                    sourceUrl = url,
                    title = decode(collection.text("name") ?: doc.title()),
                    description = (albumArtist ?: collection.text("description"))?.let(::decode),
                    coverUrl = imageOf(collection["image"]) ?: ogImage(doc),
                    tracks = tracks,
                    missingCount = ((declared ?: 0) - tracks.size).coerceAtLeast(0),
                )
            }
        }
        val single = nodes.firstOrNull { it.isType("MusicRecording") }
            ?.let { recordingToTrack(it, null, null) }
            ?: return null
        return ImportedCollection(
            service = ImportService.WEB,
            sourceUrl = url,
            title = single.title,
            coverUrl = ogImage(doc),
            tracks = listOf(single),
        )
    }

    /** Every object in a JSON-LD block: top-level arrays and `@graph` included. */
    private fun flatten(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap(::flatten)
        is JsonObject -> listOf(element) + (element["@graph"]?.let(::flatten).orEmpty())
        else -> emptyList()
    }

    /** `track`/`tracks` as a plain array, a single object, or an `ItemList` of `ListItem`s. */
    private fun recordingsOf(collection: JsonObject): List<JsonObject> {
        val raw = collection["track"] ?: collection["tracks"] ?: return emptyList()
        fun unwrap(e: JsonElement): List<JsonObject> = when (e) {
            is JsonArray -> e.flatMap(::unwrap)
            is JsonObject -> when {
                e["itemListElement"] != null -> unwrap(e["itemListElement"]!!)
                e["item"] is JsonObject -> listOf(e["item"] as JsonObject)
                else -> listOf(e)
            }
            else -> emptyList()
        }
        return unwrap(raw)
    }

    private fun recordingToTrack(obj: JsonObject, album: String?, albumArtist: String?): ImportTrack? {
        val title = obj.text("name")?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = artistOf(obj["byArtist"]) ?: artistOf(obj["artist"]) ?: albumArtist.orEmpty()
        val inAlbum = (obj["inAlbum"] as? JsonObject)?.text("name")
        return ImportTrack(
            title = title,
            artist = decode(artist).trim(),
            album = (album ?: inAlbum)?.let(::decode),
            durationMs = obj.text("duration")?.let(::isoDurationMs),
            isrc = obj.text("isrcCode"),
        )
    }

    /** A `byArtist` that is an object, an array of them, or a bare string. */
    private fun artistOf(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.text("name")
        is JsonArray -> element.mapNotNull { artistOf(it) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        is JsonPrimitive -> element.takeIf { it.isString }?.content
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun imageOf(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.takeIf { it.isString }?.content
        is JsonArray -> element.firstNotNullOfOrNull { imageOf(it) }
        is JsonObject -> element.text("url") ?: element.text("contentUrl")
        else -> null
    }

    private fun JsonObject.isType(vararg types: String): Boolean = when (val t = this["@type"]) {
        is JsonPrimitive -> t.content in types
        is JsonArray -> t.any { (it as? JsonPrimitive)?.content in types }
        else -> false
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    // ── 2. Open Graph song ──────────────────────────────────────────────────

    private fun fromOpenGraphSong(doc: Document, url: String): ImportedCollection? {
        if (meta(doc, "og:type") != "music.song") return null
        val rawTitle = meta(doc, "og:title") ?: return null
        val musician = doc.select("meta[property=music:musician]").map { it.attr("content") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
        val (title, artist) = if (musician != null) {
            rawTitle to musician
        } else {
            splitTitleArtist(rawTitle, meta(doc, "og:description"))
        }
        val track = ImportTrack(
            title = title,
            artist = artist,
            durationMs = meta(doc, "music:duration")?.toLongOrNull()?.times(1000),
        )
        return ImportedCollection(
            service = ImportService.WEB,
            sourceUrl = url,
            title = title,
            coverUrl = ogImage(doc),
            tracks = listOf(track),
        )
    }

    /**
     * "Song - Artist", "Song by Artist", "Song · Artist", with any trailing
     * "| Service" or "- song and lyrics by" boilerplate taken off.
     */
    internal fun splitTitleArtist(ogTitle: String, description: String?): Pair<String, String> {
        val clean = decode(ogTitle).substringBefore(" | ").trim()
        Regex("""^(.+?) - song (?:and lyrics )?by (.+?)$""", RegexOption.IGNORE_CASE).find(clean)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        for (sep in listOf(" · ", " – ", " - ", " by ")) {
            val idx = clean.indexOf(sep)
            if (idx > 0) return clean.substring(0, idx).trim() to clean.substring(idx + sep.length).trim()
        }
        val byInDescription = description?.let {
            Regex("""by ([^.·|]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()
        }
        return clean to byInDescription.orEmpty()
    }

    // ── 3. music:song links ─────────────────────────────────────────────────

    private fun fromMusicSongTags(doc: Document, url: String): ImportedCollection? {
        val links = doc.select("meta[property=music:song]").map { it.attr("content") }.filter { it.isNotBlank() }
        if (links.isEmpty()) return null
        val musician = doc.select("meta[property=music:musician]").map { it.attr("content") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("http") }.orEmpty()
        // Some services put the song's name here (Audiomack), the spec says
        // its URL; a URL is read for the title in its last slug.
        val tracks = links.mapNotNull { link ->
            val title = if (link.startsWith("http")) {
                ImportUrls.segments(link).lastOrNull { it.any(Char::isLetter) }
                    ?.replace('-', ' ')?.replace('_', ' ')
            } else {
                decode(link)
            }
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { ImportTrack(title = it, artist = musician) }
        }.takeIf { it.isNotEmpty() } ?: return null
        return ImportedCollection(
            service = ImportService.WEB,
            sourceUrl = url,
            title = meta(doc, "og:title")?.let(::decode) ?: doc.title(),
            coverUrl = ogImage(doc),
            tracks = tracks,
        )
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    private fun meta(doc: Document, property: String): String? =
        doc.select("meta[property=$property], meta[name=$property]").firstOrNull()
            ?.attr("content")?.takeIf { it.isNotBlank() }

    private fun ogImage(doc: Document) = meta(doc, "og:image")

    /** HTML entities some services leave inside their JSON (`&quot;`, `&amp;`). */
    internal fun decode(text: String): String =
        if ('&' in text) Jsoup.parse(text).text() else text

    /**
     * An ISO-8601 duration (`PT5M20S`, `PT06M06S`, `PT1H2M3.5S`, `P0DT3M`) in
     * milliseconds, or null when it isn't one.
     */
    internal fun isoDurationMs(text: String): Long? {
        val m = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""", RegexOption.IGNORE_CASE)
            .find(text.trim()) ?: return null
        val (d, h, min, s) = m.destructured
        val ms = ((d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 + (min.toLongOrNull() ?: 0) * 60) * 1000 +
            ((s.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        return ms.takeIf { it > 0 }
    }
}
