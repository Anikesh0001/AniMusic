package com.music.bitchord.data.importer

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * A BitChord playlist written out for another app: M3U (players), CSV
 * (spreadsheets, and back into any importer that reads Exportify-style
 * headers — this one included), or plain "Artist - Title" text.
 */
object PlaylistExporter {

    enum class Format(val extension: String, val mimeType: String) {
        M3U("m3u8", "audio/x-mpegurl"),
        CSV("csv", "text/csv"),
        TEXT("txt", "text/plain"),
    }

    fun export(format: Format, title: String, songs: List<Song>): String = when (format) {
        Format.M3U -> toM3u(title, songs)
        Format.CSV -> toCsv(songs)
        Format.TEXT -> toText(songs)
    }

    /** A file name for [title] that every file system will take. */
    fun fileName(title: String, format: Format): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim().replace(Regex("""\s+"""), " ")
        return (safe.ifEmpty { "playlist" }).take(80) + "." + format.extension
    }

    fun urlOf(song: Song) = "https://music.youtube.com/watch?v=${song.videoId}"

    internal fun toM3u(title: String, songs: List<Song>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(oneLine(title)).append('\n')
        for (song in songs) {
            val seconds = TrackMatcher.secondsOf(song.durationText) ?: -1
            append("#EXTINF:").append(seconds).append(',')
            if (song.artist.isNotBlank()) append(oneLine(song.artist)).append(" - ")
            append(oneLine(song.title)).append('\n')
            append(urlOf(song)).append('\n')
        }
    }

    /** Header names an importer recognises: Exportify's `Track Name`/`Artist Name(s)`/`Album Name`. */
    internal fun toCsv(songs: List<Song>): String = buildString {
        append("Track Name,Artist Name(s),Album Name,Duration,YouTube Music URL,Video ID\r\n")
        for (song in songs) {
            append(listOf(song.title, song.artist, song.albumName.orEmpty(), song.durationText.orEmpty(), urlOf(song), song.videoId)
                .joinToString(",", transform = ::csvCell))
            append("\r\n")
        }
    }

    internal fun toText(songs: List<Song>): String =
        songs.joinToString("\n", postfix = "\n") { song ->
            if (song.artist.isBlank()) oneLine(song.title) else "${oneLine(song.artist)} - ${oneLine(song.title)}"
        }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun oneLine(text: String) = text.replace('\n', ' ').replace('\r', ' ').trim()
}
