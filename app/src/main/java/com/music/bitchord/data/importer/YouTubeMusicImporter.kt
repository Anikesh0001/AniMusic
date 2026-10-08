package com.music.bitchord.data.importer

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * YouTube and YouTube Music playlists and albums, saved as a playlist the
 * way a Spotify or Deezer link is.
 *
 * Nothing needs matching: every row already *is* a YouTube Music song, so
 * each [ImportTrack] carries its [Song] and [TrackResolver] keeps it as it is.
 * The rows come from the same paged read the app's own playlist pages use
 * ([YtMusicRepository.allSongs]), the name and cover from the page header.
 *
 * Single videos (`watch?v=`, `youtu.be/`) are not claimed here: a song link
 * is something to play, and the import dialog opens it as one.
 */
object YouTubeMusicImporter : PlaylistImporter {

    override val service = ImportService.YOUTUBE_MUSIC

    private val HOSTS = setOf("youtube.com", "music.youtube.com", "m.youtube.com")

    override fun canHandle(url: String): Boolean = browseIdOf(url) != null

    /**
     * The browse id a playlist or album link opens: `VL<list>` for
     * `playlist?list=` (and a `watch` link that names a list but no video),
     * or a `browse/` id for an album (`MPREb_…`) or playlist (`VL…`).
     */
    fun browseIdOf(url: String): String? {
        val parsed = ImportUrls.parse(url) ?: return null
        if (ImportUrls.host(url) !in HOSTS) return null
        val segments = ImportUrls.segments(url)
        val list = parsed.queryParameter("list")?.trim().orEmpty()
        return when (segments.firstOrNull()) {
            "playlist" -> list.takeIf { it.isNotEmpty() }?.let(::playlistBrowseId)
            "watch" -> list.takeIf { it.isNotEmpty() && parsed.queryParameter("v").isNullOrBlank() }
                ?.let(::playlistBrowseId)
            "browse" -> segments.getOrNull(1)?.takeIf { it.startsWith("MPREb") || it.startsWith("VL") }
            else -> null
        }
    }

    private fun playlistBrowseId(list: String) = if (list.startsWith("VL")) list else "VL$list"

    override suspend fun fetch(url: String): ImportedCollection {
        val browseId = browseIdOf(url) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)
        val header = YtMusicRepository.browseSongs(browseId).getOrNull()?.header
        val songs = YtMusicRepository.allSongs(browseId).getOrElse {
            throw ImportException(ImportException.Reason.NOT_FOUND, it)
        }
        val playable = songs.filter { it.videoId.isNotBlank() }
        if (playable.isEmpty()) throw ImportException(ImportException.Reason.NOT_FOUND)
        return ImportedCollection(
            service = service,
            sourceUrl = url,
            title = header?.title?.takeIf { it.isNotBlank() }
                ?: playable.first().albumName?.takeIf { browseId.startsWith("MPREb") }
                ?: service.label,
            description = header?.subtitle?.takeIf { it.isNotBlank() },
            coverUrl = header?.thumbnailUrl ?: playable.first().thumbnailUrl,
            tracks = playable.map(::trackOf),
        )
    }

    internal fun trackOf(song: Song) = ImportTrack(
        title = song.title,
        artist = song.artist,
        album = song.albumName,
        durationMs = TrackMatcher.secondsOf(song.durationText)?.times(1000L),
        videoId = song.videoId,
        song = song,
    )
}
