package com.music.bitchord.data.importer

/** One row of a playlist, album or song read from another music service. */
data class ImportTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
)

/** A playlist, album or single song read from another service, before matching. */
data class ImportedCollection(
    val service: ImportService,
    val sourceUrl: String,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val tracks: List<ImportTrack>,
    /**
     * Rows the source listed but would not describe without a login or a
     * second API — SoundCloud's tail past its first page is the case. Reported
     * to the listener rather than silently dropped.
     */
    val missingCount: Int = 0,
)

/**
 * Where an import came from. [label] is a brand name and so is not
 * translated; [hosts] is only for showing the service as a link is typed —
 * which importer actually reads the link is decided by
 * [ImporterRegistry.find].
 */
enum class ImportService(val label: String, val hosts: List<String> = emptyList()) {
    SPOTIFY("Spotify", listOf("spotify.com", "spotify.link", "spoti.fi")),
    DEEZER("Deezer", listOf("deezer.com", "deezer.page.link", "dzr.page.link")),
    APPLE_MUSIC("Apple Music", listOf("music.apple.com", "itunes.apple.com")),
    YOUTUBE_MUSIC("YouTube Music", listOf("youtube.com", "music.youtube.com", "youtu.be")),
    JIOSAAVN("JioSaavn", listOf("jiosaavn.com", "saavn.com")),
    SOUNDCLOUD("SoundCloud", listOf("soundcloud.com", "on.soundcloud.com", "soundcloud.app.goo.gl")),
    GAANA("Gaana", listOf("gaana.com")),
    WYNK("Wynk Music", listOf("wynk.in")),
    AMAZON_MUSIC("Amazon Music", listOf("music.amazon.com", "music.amazon.in", "music.amazon.co.uk", "amazon.com/music")),
    TIDAL("Tidal", listOf("tidal.com")),
    ANGHAMI("Anghami", listOf("anghami.com")),
    BOOMPLAY("Boomplay", listOf("boomplay.com", "boomplaymusic.com")),
    AUDIOMACK("Audiomack", listOf("audiomack.com")),
    HUNGAMA("Hungama", listOf("hungama.com")),
    QOBUZ("Qobuz", listOf("qobuz.com")),
    NAPSTER("Napster", listOf("napster.com")),
    ODESLI("song.link", listOf("song.link", "album.link", "odesli.co")),
    LISTENBRAINZ("ListenBrainz", listOf("listenbrainz.org")),
    LASTFM("Last.fm", listOf("last.fm")),
    FILE("File"),
    TEXT("Text"),
    WEB("Web page");

    companion object {
        /** The service a link visibly belongs to, for the dialog's "Detected:" line. */
        fun detect(input: String): ImportService? {
            val url = ImportUrls.firstUrl(input) ?: return null
            if (url.startsWith("spotify:")) return SPOTIFY
            val host = ImportUrls.host(url) ?: return null
            val path = ImportUrls.parse(url)?.encodedPath.orEmpty()
            return entries.firstOrNull { service ->
                service.hosts.any { h ->
                    val (hHost, hPath) = h.substringBefore('/') to h.substringAfter('/', "")
                    (host == hHost || host.endsWith(".$hHost")) &&
                        (hPath.isEmpty() || path.startsWith("/$hPath"))
                }
            }
        }
    }
}

/**
 * Why an import failed, as something the dialog can put in the listener's
 * language. Importers throw this instead of a bare message so no English
 * string from a parser ever reaches the screen.
 */
class ImportException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** Nothing in the text looked like a link. */
        INVALID_LINK,
        /** A link, but to a service or page shape nothing here reads. */
        UNSUPPORTED,
        /** The page loaded but held no tracks, or is private. */
        NOT_FOUND,
        /** HTTP error, timeout, no connection. */
        NETWORK,
        /** The service answered 429. */
        RATE_LIMITED,
        /** The page loaded but its layout is not one this parser knows. */
        PARSE,
    }
}
