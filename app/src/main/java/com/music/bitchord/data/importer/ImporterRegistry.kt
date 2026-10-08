package com.music.bitchord.data.importer

/**
 * Every importer, and the one place a pasted or shared link is turned into
 * the importer that reads it.
 */
object ImporterRegistry {

    /** Most specific first: the first whose [PlaylistImporter.canHandle] says yes wins. */
    val importers: List<PlaylistImporter> = listOf(
        SpotifyPlaylistImporter,
        DeezerImporter,
        QobuzImporter,
        AppleMusicImporter,
        JioSaavnImporter,
        SoundCloudImporter,
        OdesliResolver,
        // Last: it claims any web page, so everything above gets first refusal.
        GenericPageImporter,
    )

    /**
     * Hosts that only ever redirect to the real link. Checked before [find]
     * gives up, so `spotify.link/abc` reaches the Spotify importer.
     */
    private val SHORT_LINK_HOSTS = listOf(
        "spotify.link", "spoti.fi",
        "deezer.page.link", "dzr.page.link", "link.deezer.com",
        "on.soundcloud.com", "soundcloud.app.goo.gl",
        "wynk.in/u",
        "gaana.com/s",
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "amzn.to",
        "jiosaavn.page.link",
        "tidal.link",
        "anghami.page.link", "play.anghami.com",
    )

    fun find(url: String): PlaylistImporter? = importers.firstOrNull { it.canHandle(url) }

    /** The importer written for [url]'s service, ignoring the generic page reader. */
    fun findDedicated(url: String): PlaylistImporter? =
        importers.firstOrNull { it !== GenericPageImporter && it.canHandle(url) }

    fun isShortLink(url: String): Boolean {
        val host = ImportUrls.host(url) ?: return false
        val path = ImportUrls.parse(url)?.encodedPath.orEmpty()
        return SHORT_LINK_HOSTS.any { entry ->
            val entryHost = entry.substringBefore('/')
            val entryPath = entry.substringAfter('/', "")
            (host == entryHost || host.endsWith(".$entryHost")) &&
                (entryPath.isEmpty() || path.startsWith("/$entryPath/"))
        }
    }

    /** [url] after its redirects, for share links that name nothing themselves. */
    suspend fun resolveShortLink(url: String): String = ImportHttp.finalUrl(url)

    /**
     * The importer for [input] and the link it should be given: the first URL
     * in the text, followed through a share shortener when it is one.
     */
    suspend fun resolve(input: String): Pair<PlaylistImporter, String> {
        val url = ImportUrls.firstUrl(input)
            ?: throw ImportException(ImportException.Reason.INVALID_LINK)
        if (!isShortLink(url)) findDedicated(url)?.let { return it to url }
        // A shortener, or a link no dedicated importer recognises: where it
        // lands may well be one that is (a `bit.ly` to Spotify, an
        // `app.link` to Deezer), so its redirects are followed first.
        val target = if (url.startsWith("http")) {
            runCatching { resolveShortLink(url) }.getOrDefault(url)
        } else url
        val importer = find(target) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        return importer to target
    }

    /**
     * Reads the collection [input] links to. A link nothing here can read is
     * put to song.link once before giving up: a single song on a service
     * with no importer (a Tidal track, say) is still a song Odesli knows.
     */
    suspend fun fetch(input: String): ImportedCollection {
        val (importer, url) = resolve(input)
        return fetchWith(importer, url)
    }

    /** [importer]'s read of [url], with the song.link fallback described on [fetch]. */
    suspend fun fetchWith(importer: PlaylistImporter, url: String): ImportedCollection {
        return try {
            importer.fetch(url)
        } catch (e: ImportException) {
            if (importer !== GenericPageImporter || e.reason != ImportException.Reason.UNSUPPORTED) throw e
            val entity = runCatching { OdesliResolver.lookup(url) }.getOrNull() ?: throw e
            OdesliResolver.toCollection(entity, url)
        }
    }
}
