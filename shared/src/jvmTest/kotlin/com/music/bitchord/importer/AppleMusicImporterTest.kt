package com.music.bitchord.importer

import com.music.bitchord.data.importer.AppleMusicImporter
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicImporterTest {

    private val albumUrl = "https://music.apple.com/us/album/discovery/697194953"

    @Test
    fun linkShapes() {
        listOf(
            albumUrl,
            "$albumUrl?i=697195462",
            "https://music.apple.com/gb/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb",
            "https://music.apple.com/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb?l=en",
            "https://music.apple.com/in/song/one-more-time/697195462",
            "https://itunes.apple.com/us/album/discovery/697194953",
        ).forEach { assertTrue(it, AppleMusicImporter.canHandle(it)) }
        assertFalse(AppleMusicImporter.canHandle("https://music.apple.com/us/artist/daft-punk/5468295"))
        assertFalse(AppleMusicImporter.canHandle("https://apple.com/us/album/x/1"))
        assertEquals(ImportService.APPLE_MUSIC, ImporterRegistry.find(albumUrl)?.service)
    }

    @Test
    fun album() {
        val page = AppleMusicImporter.parse(Fixtures.read("apple_album.html"), albumUrl)!!
        assertEquals("Discovery", page.title)
        assertEquals("Daft Punk", page.description)
        assertEquals(listOf("One More Time", "Aerodynamic", "Digital Love"), page.tracks.map { it.title })
        val first = page.tracks.first()
        assertEquals("Daft Punk", first.artist)
        assertEquals("Discovery", first.album)
        assertEquals(320_357L, first.durationMs)
        assertTrue(page.coverUrl!!.endsWith("600x600bb.jpg"))
    }

    @Test
    fun albumLinkNamingOneSongImportsThatSong() {
        val page = AppleMusicImporter.parse(Fixtures.read("apple_album.html"), "$albumUrl?i=697195570")!!
        assertEquals("Aerodynamic", page.tracks.single().title)
        assertEquals("Aerodynamic", page.title)
    }

    @Test
    fun playlistRowsCarryTheirOwnAlbum() {
        val page = AppleMusicImporter.parse(
            Fixtures.read("apple_playlist.html"),
            "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb",
        )!!
        assertEquals("Today’s Hits", page.title)
        val first = page.tracks.first()
        assertEquals("Solar Eclipse", first.title)
        assertEquals("Drake & Don Toliver", first.artist)
        assertEquals("HABIBTI (FOMO)", first.album)
    }

    @Test
    fun songPage() {
        val page = AppleMusicImporter.parse(
            Fixtures.read("apple_song.html"),
            "https://music.apple.com/us/song/one-more-time/697195462",
        )!!
        val song = page.tracks.single()
        assertEquals("One More Time", song.title)
        assertEquals("Daft Punk", song.artist)
        assertEquals("Discovery", song.album)
        assertEquals(320_000L, song.durationMs)
    }

    @Test
    fun fallsBackToJsonLdWhenTheAppDataMoves() {
        val html = Fixtures.read("apple_playlist.html")
            .replace("serialized-server-data", "something-renamed")
        val page = AppleMusicImporter.parse(html, "https://music.apple.com/us/playlist/x/pl.1")
        assertNotNull(page)
        assertEquals(ImportService.APPLE_MUSIC, page!!.service)
        assertEquals("Solar Eclipse", page.tracks.first().title)
        assertEquals(218_000L, page.tracks.first().durationMs)
    }
}
