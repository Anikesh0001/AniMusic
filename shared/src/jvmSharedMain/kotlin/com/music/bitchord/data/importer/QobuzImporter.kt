package com.music.bitchord.data.importer

import org.jsoup.Jsoup

/**
 * Qobuz album and playlist pages.
 *
 * Qobuz's JSON-LD names the album but leaves out its tracks, so the generic
 * reader finds nothing to import. The store page renders the whole tracklist
 * as markup instead — one `div.track` per row, with the name, the runtime and
 * a credits line in which the performing artists are marked `MainArtist`.
 */
object QobuzImporter : PlaylistImporter {

    override val service = ImportService.QOBUZ

    override fun canHandle(url: String): Boolean {
        if (!ImportUrls.hostIs(url, "qobuz.com")) return false
        val segments = ImportUrls.segmentsWithoutLocale(url)
        return segments.firstOrNull() in setOf("album", "playlists", "playlist") && segments.size >= 2
    }

    override suspend fun fetch(url: String): ImportedCollection {
        val html = ImportHttp.get(url)
        return parse(html, url) ?: throw ImportException(ImportException.Reason.NOT_FOUND)
    }

    internal fun parse(html: String, url: String): ImportedCollection? {
        val doc = Jsoup.parse(html, url)
        // The page's own JSON-LD gives the release name and its artist; the
        // generic reader already knows how to get them out.
        val header = GenericPageImporter.headerOf(doc)
        val isAlbum = ImportUrls.segmentsWithoutLocale(url).firstOrNull() == "album"
        // Qobuz's JSON-LD names no artist; its <title> is "Release, Artist - Qobuz".
        val pageTitle = doc.title().substringBeforeLast(" - Qobuz").trim()
        val releaseArtist = header?.artist
            ?: pageTitle.substringAfterLast(", ", "").trim().takeIf { isAlbum && it.isNotEmpty() }
        val tracks = doc.select("div.track").mapNotNull { row ->
            val title = row.selectFirst(".track__item--name span")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val credits = row.selectFirst(".track__info")?.text().orEmpty()
            ImportTrack(
                title = title,
                artist = mainArtists(credits) ?: releaseArtist.orEmpty(),
                album = header?.name?.takeIf { isAlbum },
                // The visible clock, not `data-duration`: that attribute is
                // the player's, and is not the track's length.
                durationMs = clockMs(row.selectFirst(".track__item--duration")?.text()),
            )
        }
        if (tracks.isEmpty()) return null
        return ImportedCollection(
            service = service,
            sourceUrl = url,
            title = header?.name ?: pageTitle,
            description = releaseArtist,
            coverUrl = header?.image,
            tracks = tracks,
        )
    }

    /**
     * The performers from a credits line such as
     * `Daft Punk, MainArtist - Romanthony, Vocals - Thomas Bangalter, Producer`:
     * each ` - `-separated entry is a name and its roles.
     */
    internal fun mainArtists(credits: String): String? =
        credits.split(" - ")
            .map { entry -> entry.split(",").map { it.trim() } }
            .filter { parts -> parts.drop(1).any { it == "MainArtist" || it == "FeaturedArtist" } }
            .map { it.first() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    /** `00:05:20` or `05:20` in milliseconds. */
    internal fun clockMs(text: String?): Long? {
        val parts = text?.trim()?.split(":")?.map { it.toLongOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p }.takeIf { it > 0 }?.times(1000)
    }
}
