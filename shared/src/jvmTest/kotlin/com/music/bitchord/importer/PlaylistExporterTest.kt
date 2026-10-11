package com.music.bitchord.importer

import com.music.bitchord.data.importer.PlaylistExporter
import com.music.bitchord.data.importer.PlaylistExporter.Format
import com.music.bitchord.data.importer.SongListParser
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExporterTest {

    private val songs = listOf(
        Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, durationText = "3:33", albumName = "Whenever You Need Somebody"),
        Song("kJQP7kiw5Fk", "Despacito, \"Remix\"", "Luis Fonsi, Daddy Yankee", null, durationText = "4:41"),
    )

    @Test
    fun m3u() {
        val out = PlaylistExporter.export(Format.M3U, "Road Trip", songs)
        assertEquals(
            "#EXTM3U\n#PLAYLIST:Road Trip\n" +
                "#EXTINF:213,Rick Astley - Never Gonna Give You Up\nhttps://music.youtube.com/watch?v=dQw4w9WgXcQ\n" +
                "#EXTINF:281,Luis Fonsi, Daddy Yankee - Despacito, \"Remix\"\nhttps://music.youtube.com/watch?v=kJQP7kiw5Fk\n",
            out,
        )
    }

    @Test
    fun csvEscapesAndRoundTripsThroughTheImporter() {
        val csv = PlaylistExporter.export(Format.CSV, "x", songs)
        assertTrue(csv.contains("\"Despacito, \"\"Remix\"\"\""))
        val (_, back) = SongListParser.parseCsv(csv)
        assertEquals(songs.map { it.title }, back.map { it.title })
        assertEquals(songs.map { it.artist }, back.map { it.artist })
        assertEquals(songs.map { it.videoId }, back.map { it.videoId })
        assertEquals(213_000L, back[0].durationMs)
    }

    @Test
    fun textRoundTripsToo() {
        val text = PlaylistExporter.export(Format.TEXT, "x", songs)
        assertEquals("Rick Astley - Never Gonna Give You Up\nLuis Fonsi, Daddy Yankee - Despacito, \"Remix\"\n", text)
        assertEquals("Never Gonna Give You Up", SongListParser.parseText(text).first().title)
    }

    @Test
    fun safeFileNames() {
        assertEquals("AC DC Hits.m3u8", PlaylistExporter.fileName("AC/DC: Hits?", Format.M3U))
        assertEquals("playlist.csv", PlaylistExporter.fileName("  ", Format.CSV))
    }
}
