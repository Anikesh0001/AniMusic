package com.music.bitchord.importer

import com.music.bitchord.data.importer.GenericPageImporter
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GenericPageImporterTest {

    @Test
    fun gaanaPlaylistFromJsonLd() {
        val page = GenericPageImporter.parse(Fixtures.read("gaana_playlist.html"), "https://gaana.com/playlist/x")!!
        assertEquals("Hindi Top 50", page.title)
        assertEquals(3, page.tracks.size)
        val first = page.tracks.first()
        assertEquals("Tera Mera Rishta -  New Version (From Awarapan 2)", first.title)
        assertEquals("Mithoon,Saaj Bhatt,Sayeed Quadri,Mustafa Zahid,Subodhh Sharma,Pritam", first.artist)
        assertEquals("Tera Mera Rishta - New Version (From Awarapan 2)", first.album)
        assertEquals(366_000L, first.durationMs)
        // numTracks says 100, only 3 rows are in the fixture.
        assertEquals(97, page.missingCount)
    }

    @Test
    fun audiomackAlbumFromJsonLd() {
        val page = GenericPageImporter.parse(Fixtures.read("audiomack_album.html"), "https://audiomack.com/eminem/album/x")!!
        assertEquals("The Marshall Mathers LP", page.title)
        val first = page.tracks.first()
        assertEquals("Public Service Announcement 2000", first.title)
        assertEquals("Eminem", first.artist)
        assertEquals("The Marshall Mathers LP", first.album)
        assertEquals(28_000L, first.durationMs)
        assertEquals("USIR10000579", first.isrc)
    }

    @Test
    fun graphItemListAndArtistArrays() {
        val page = GenericPageImporter.parse(Fixtures.read("ld_graph_itemlist.html"), "https://example.com/p")!!
        assertEquals("Road Trip & Chill", page.title)
        assertEquals("https://example.com/p.jpg", page.coverUrl)
        assertEquals(listOf("Song A", "Song B"), page.tracks.map { it.title })
        assertEquals("Band One, Guest", page.tracks[0].artist)
        assertEquals("Album A", page.tracks[0].album)
        assertEquals("Solo Artist", page.tracks[1].artist)
        assertEquals(185_000L, page.tracks[0].durationMs)
        assertEquals(3_723_500L, page.tracks[1].durationMs)
        assertEquals(2, page.missingCount)
    }

    @Test
    fun openGraphSongPage() {
        val page = GenericPageImporter.parse(Fixtures.read("og_song.html"), "https://example.com/s")!!
        val song = page.tracks.single()
        assertEquals("Blinding Lights", song.title)
        assertEquals("The Weeknd", song.artist)
        assertEquals(200_000L, song.durationMs)
    }

    @Test
    fun musicSongTagsAsLastResort() {
        val page = GenericPageImporter.parse(Fixtures.read("music_song_tags.html"), "https://example.com/a")!!
        assertEquals(listOf("Public Service Announcement 2000", "Kill You", "Stan"), page.tracks.map { it.title })
        assertEquals("Eminem", page.tracks.first().artist)
    }

    @Test
    fun aPageWithNoMusicIsNull() {
        assertNull(GenericPageImporter.parse("<html><head><title>Hi</title></head></html>", "https://example.com"))
    }

    @Test
    fun splitTitleArtistHeuristics() {
        assertEquals("Song" to "Artist", GenericPageImporter.splitTitleArtist("Song - Artist", null))
        assertEquals("Song" to "Artist", GenericPageImporter.splitTitleArtist("Song by Artist | Service", null))
        assertEquals("Song" to "A, B", GenericPageImporter.splitTitleArtist("Song · A, B", null))
        assertEquals("Lonely" to "Someone", GenericPageImporter.splitTitleArtist("Lonely", "A song by Someone."))
    }

    @Test
    fun isoDurations() {
        assertEquals(320_000L, GenericPageImporter.isoDurationMs("PT5M20S"))
        assertEquals(366_000L, GenericPageImporter.isoDurationMs("PT06M06S"))
        assertEquals(180_000L, GenericPageImporter.isoDurationMs("P0DT3M"))
        assertNull(GenericPageImporter.isoDurationMs("3:20"))
        assertNull(GenericPageImporter.isoDurationMs("PT0S"))
    }

    @Test
    fun genericIsTheLastResort() {
        assertSame(GenericPageImporter, ImporterRegistry.find("https://audiomack.com/eminem/album/x"))
        assertEquals(ImportService.DEEZER, ImporterRegistry.find("https://www.deezer.com/album/1")?.service)
        assertNull(ImporterRegistry.findDedicated("https://audiomack.com/eminem/album/x"))
    }
}
