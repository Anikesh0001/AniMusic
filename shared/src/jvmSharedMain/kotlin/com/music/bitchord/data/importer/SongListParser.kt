package com.music.bitchord.data.importer

/**
 * Song lists that aren't links: an exported CSV, an M3U playlist, or plain
 * text — typed, pasted, or read from a `.txt` file.
 *
 * CSV columns are found by their header, not their position, so one reader
 * covers the common exporters:
 *
 *  - Exportify: `Track Name`, `Artist Name(s)`, `Album Name`, `Duration (ms)`, `ISRC`
 *  - TuneMyMusic: `Track name`, `Artist name`, `Album`, `Playlist name`, `ISRC`
 *  - Google Takeout (YouTube Music): `Song Title`, `Album Title`,
 *    `Artist Name 1…n`, and playlists that are nothing but `Video ID`s —
 *    those rows carry the id and play as they are.
 */
object SongListParser {

    enum class Format { CSV, M3U, TEXT }

    fun formatOf(fileName: String?, text: String): Format {
        val name = fileName.orEmpty().lowercase()
        return when {
            name.endsWith(".csv") -> Format.CSV
            name.endsWith(".m3u") || name.endsWith(".m3u8") -> Format.M3U
            text.trimStart().startsWith("#EXTM3U") -> Format.M3U
            else -> Format.TEXT
        }
    }

    /** A file's rows as a collection named after the file (or the list's own name, when it has one). */
    fun parseFile(fileName: String?, text: String): ImportedCollection {
        val base = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        val (name, tracks) = when (formatOf(fileName, text)) {
            Format.CSV -> parseCsv(text)
            Format.M3U -> parseM3u(text)
            Format.TEXT -> null to parseText(text)
        }
        return ImportedCollection(
            service = ImportService.FILE,
            sourceUrl = "file:" + (fileName ?: "list"),
            title = name ?: base ?: ImportService.FILE.label,
            tracks = tracks,
        )
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    private val TITLE = listOf("track name", "song title", "track title", "title", "song", "name", "track")
    private val ARTIST = listOf("artist name(s)", "artist names", "artist name", "artist", "artists", "artist name 1")
    private val ALBUM = listOf("album name", "album title", "album")
    private val DURATION_MS = listOf("duration (ms)", "duration_ms", "duration ms")
    private val DURATION = listOf("duration", "length", "time")
    private val ISRC = listOf("isrc")
    private val VIDEO_ID = listOf("video id", "videoid", "video_id")
    private val PLAYLIST = listOf("playlist name", "playlist")

    /** The list's name (when a column carries one) and its rows. */
    fun parseCsv(text: String): Pair<String?, List<ImportTrack>> {
        val records = csvRecords(text.removePrefix("﻿"))
        // Takeout's older playlist files put a metadata block above the real
        // header; the header is the first row naming a title or a video id.
        val headerAt = records.indexOfFirst { row ->
            val cells = row.map { it.trim().lowercase() }
            cells.any { it in TITLE } || cells.any { it in VIDEO_ID }
        }
        if (headerAt == -1) return null to emptyList()
        val header = records[headerAt].map { it.trim().lowercase() }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val title = col(TITLE)
        val artist = col(ARTIST)
        // Takeout spreads a credit over `Artist Name 1`, `Artist Name 2`, …
        val moreArtists = header.indices.filter { header[it].matches(Regex("""artist name \d+""")) && it != artist }
        val album = col(ALBUM)
        val durationMs = col(DURATION_MS)
        val duration = col(DURATION)
        val isrc = col(ISRC)
        val videoId = col(VIDEO_ID)
        val playlist = col(PLAYLIST)
        var listName: String? = null
        val tracks = records.drop(headerAt + 1).mapNotNull { row ->
            fun cell(i: Int?) = i?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            if (listName == null) listName = cell(playlist)
            val id = cell(videoId)?.takeIf { VIDEO_ID_FORMAT.matches(it) }
            val name = cell(title)
            if (name == null && id == null) return@mapNotNull null
            val credits = (listOfNotNull(cell(artist)) + moreArtists.mapNotNull { cell(it) }).distinct()
            ImportTrack(
                title = name.orEmpty(),
                artist = credits.joinToString(", "),
                album = cell(album),
                durationMs = cell(durationMs)?.toLongOrNull()?.takeIf { it > 0 }
                    ?: cell(duration)?.let(::clockOrSecondsMs),
                isrc = cell(isrc),
                videoId = id,
            )
        }
        return listName to tracks
    }

    /** RFC 4180-ish: quoted cells, doubled quotes, commas and newlines inside quotes. */
    internal fun csvRecords(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        // Semicolon-separated files come out of spreadsheet apps in many locales.
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) out += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) out += row
        return out
    }

    // ── M3U ─────────────────────────────────────────────────────────────────

    fun parseM3u(text: String): Pair<String?, List<ImportTrack>> {
        var name: String? = null
        var pending: ImportTrack? = null
        val tracks = mutableListOf<ImportTrack>()
        for (raw in text.removePrefix("﻿").lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line == "#EXTM3U" -> Unit
                line.startsWith("#PLAYLIST:") -> name = line.removePrefix("#PLAYLIST:").trim().takeIf { it.isNotEmpty() }
                line.startsWith("#EXTINF:") -> {
                    val body = line.removePrefix("#EXTINF:")
                    // `#EXTINF:<seconds> key="value"…,<display name>`
                    val seconds = body.substringBefore(',').trim().substringBefore(' ').toLongOrNull()
                    val display = body.substringAfter(',', "").trim()
                    val (title, artist) = splitArtistTitle(display)
                    pending = ImportTrack(title, artist, durationMs = seconds?.takeIf { it > 0 }?.times(1000))
                }
                line.startsWith("#") -> Unit
                else -> {
                    val id = OdesliResolver.videoIdOf(line)
                    val track = pending?.copy(videoId = id)
                        ?: if (id != null) {
                            ImportTrack("", "", videoId = id)
                        } else {
                            // No #EXTINF: the file name is all there is ("Artist - Title.mp3").
                            val file = line.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
                            val decoded = runCatching { java.net.URLDecoder.decode(file, "UTF-8") }.getOrDefault(file)
                            val (title, artist) = splitArtistTitle(decoded.replace('_', ' '))
                            ImportTrack(title, artist)
                        }
                    if (track.title.isNotBlank() || track.videoId != null) tracks += track
                    pending = null
                }
            }
        }
        return name to tracks
    }

    // ── Plain text ──────────────────────────────────────────────────────────

    /**
     * One song per line: "Artist - Title", "Artist – Title", "Title by
     * Artist", "Title<TAB>Artist", with list numbering and bullets ignored. A
     * YouTube link on a line of its own is that video.
     */
    fun parseText(text: String): List<ImportTrack> =
        text.lines().mapNotNull { raw ->
            val line = raw.trim().replace(LEADING_MARKER, "").trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            if (line.startsWith("http://") || line.startsWith("https://")) {
                return@mapNotNull OdesliResolver.videoIdOf(line)?.let { ImportTrack("", "", videoId = it) }
            }
            if ('\t' in line) {
                val (title, artist) = line.split('\t', limit = 2).map { it.trim() }
                return@mapNotNull ImportTrack(title, artist).takeIf { title.isNotEmpty() }
            }
            val (title, artist) = splitArtistTitle(line)
            ImportTrack(title, artist).takeIf { title.isNotEmpty() }
        }

    /** "Artist - Title" / "Title by Artist" into (title, artist); a bare title has no artist. */
    internal fun splitArtistTitle(text: String): Pair<String, String> {
        val clean = text.trim()
        for (sep in listOf(" – ", " — ", " - ", " -- ")) {
            val idx = clean.indexOf(sep)
            if (idx > 0) return clean.substring(idx + sep.length).trim() to clean.substring(0, idx).trim()
        }
        val by = clean.lastIndexOf(" by ", ignoreCase = true)
        if (by > 0) return clean.substring(0, by).trim() to clean.substring(by + 4).trim()
        return clean to ""
    }

    /** `1.`, `01)`, `12 -`, `-`, `*`, `•` at the start of a list line. */
    private val LEADING_MARKER = Regex("""^(\d{1,4}\s*[.)\]:]\s+|\d{1,4}\s+-\s+|[-*•·]\s+)""")
    private val VIDEO_ID_FORMAT = Regex("""[A-Za-z0-9_-]{11}""")

    /** `3:45`, `1:02:03` or a bare number of seconds. */
    private fun clockOrSecondsMs(text: String): Long? {
        val parts = text.trim().split(":")
        val nums = parts.map { it.trim().toLongOrNull() ?: return null }
        val seconds = nums.fold(0L) { acc, n -> acc * 60 + n }
        return seconds.takeIf { it > 0 }?.times(1000)
    }
}
