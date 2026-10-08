package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.SongListParser
import com.music.bitchord.data.importer.SongListParser.Format
import com.music.bitchord.data.importer.TrackResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongListParserTest {

    @Test
    fun exportify() {
        val list = SongListParser.parseFile("My Playlist.csv", Fixtures.read("exportify.csv"))
        assertEquals("My Playlist", list.title)
        assertEquals(ImportService.FILE, list.service)
        assertEquals(2, list.tracks.size)
        val first = list.tracks[0]
        assertEquals("Blinding Lights", first.title)
        assertEquals("The Weeknd", first.artist)
        assertEquals("After Hours", first.album)
        assertEquals(200_040L, first.durationMs)
        assertEquals("USUG11904206", first.isrc)
        // A comma-separated credit inside one quoted cell stays one credit list.
        assertEquals("Doja Cat,Nicki Minaj", list.tracks[1].artist)
    }

    @Test
    fun tuneMyMusicTakesItsPlaylistName() {
        val list = SongListParser.parseFile("export.csv", Fixtures.read("tunemymusic.csv"))
        assertEquals("Summer Mix", list.title)
        assertEquals(listOf("Levitating", "Hello, Goodbye"), list.tracks.map { it.title })
        assertEquals("The Beatles", list.tracks[1].artist)
        assertNull(list.tracks[1].isrc)
    }

    @Test
    fun takeoutPlaylistIsAllVideoIds() {
        val rows = SongListParser.parseFile("Liked.csv", Fixtures.read("takeout_playlist.csv")).tracks
        assertEquals(listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk"), rows.map { it.videoId })
        // Rows that are only ids must not collapse into one when matched.
        assertNotEquals(TrackResolver.key(rows[0]), TrackResolver.key(rows[1]))
    }

    @Test
    fun takeoutSongsJoinNumberedArtistColumns() {
        val rows = SongListParser.parseFile("music library songs.csv", Fixtures.read("takeout_songs.csv")).tracks
        assertEquals("Despacito", rows[1].title)
        assertEquals("Luis Fonsi, Daddy Yankee", rows[1].artist)
        assertEquals("Vida", rows[1].album)
        assertEquals("kJQP7kiw5Fk", rows[1].videoId)
    }

    @Test
    fun m3u() {
        val list = SongListParser.parseFile("trip.m3u8", Fixtures.read("playlist.m3u8"))
        assertEquals("Road Trip", list.title)
        assertEquals(3, list.tracks.size)
        assertEquals(ImportTrack("Blinding Lights", "The Weeknd", durationMs = 200_000), list.tracks[0])
        assertEquals("One More Time", list.tracks[1].title)
        assertEquals("FGBhQbmPwH8", list.tracks[1].videoId)
        assertNull(list.tracks[1].durationMs)
        assertEquals("Some Song" to "Some Artist", list.tracks[2].title to list.tracks[2].artist)
    }

    @Test
    fun freeText() {
        val rows = SongListParser.parseText(
            """
            1. The Weeknd - Blinding Lights
            02) Dua Lipa – Levitating
            • Bohemian Rhapsody by Queen
            - Hey Jude
            Song Title	Tab Artist

            # a comment
            https://youtu.be/dQw4w9WgXcQ
            https://example.com/not-a-song
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Blinding Lights" to "The Weeknd",
                "Levitating" to "Dua Lipa",
                "Bohemian Rhapsody" to "Queen",
                "Hey Jude" to "",
                "Song Title" to "Tab Artist",
                "" to "",
            ),
            rows.map { it.title to it.artist },
        )
        assertEquals("dQw4w9WgXcQ", rows.last().videoId)
    }

    @Test
    fun formatDetection() {
        assertEquals(Format.CSV, SongListParser.formatOf("a.CSV", ""))
        assertEquals(Format.M3U, SongListParser.formatOf("a.m3u", ""))
        assertEquals(Format.M3U, SongListParser.formatOf(null, "#EXTM3U\n#EXTINF:1,a - b"))
        assertEquals(Format.TEXT, SongListParser.formatOf("songs.txt", "a - b"))
    }

    @Test
    fun semicolonCsvAndQuotedNewlines() {
        val (_, rows) = SongListParser.parseCsv("Title;Artist\n\"Two\nLines\";Someone\nPlain;Other\n")
        assertEquals(listOf("Two\nLines", "Plain"), rows.map { it.title })
        assertEquals("Other", rows[1].artist)
    }
}
