package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.QobuzImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QobuzImporterTest {

    @Test
    fun linkShapes() {
        assertTrue(QobuzImporter.canHandle("https://www.qobuz.com/us-en/album/discovery-daft-punk/0724384960650"))
        assertTrue(QobuzImporter.canHandle("https://www.qobuz.com/gb-en/playlists/new-releases/1234"))
        assertTrue(QobuzImporter.canHandle("https://qobuz.com/album/x/123?utm=1"))
        assertFalse(QobuzImporter.canHandle("https://www.qobuz.com/us-en/interpreter/daft-punk/1"))
        assertFalse(QobuzImporter.canHandle("https://open.qobuz.com.evil.example/album/x/1"))
        assertEquals(ImportService.QOBUZ, ImporterRegistry.find("https://www.qobuz.com/fr-fr/album/x/1")?.service)
    }

    @Test
    fun parsesAnAlbumPage() {
        val page = QobuzImporter.parse(
            Fixtures.read("qobuz_album.html"),
            "https://www.qobuz.com/us-en/album/discovery-daft-punk/0724384960650",
        )!!
        assertEquals("Discovery", page.title)
        assertEquals("Daft Punk", page.description)
        assertEquals(2, page.tracks.size)
        val first = page.tracks.first()
        assertEquals("One More Time", first.title)
        assertEquals("Daft Punk", first.artist)
        assertEquals("Discovery", first.album)
        assertEquals(320_000L, first.durationMs)
    }

    @Test
    fun creditsAndClock() {
        assertEquals(
            "Daft Punk",
            QobuzImporter.mainArtists("Daft Punk, MainArtist - Romanthony, Vocals - Thomas Bangalter, Producer, Writer"),
        )
        assertEquals("A, B", QobuzImporter.mainArtists("A, MainArtist - B, FeaturedArtist, Vocals - C, Writer"))
        assertNull(QobuzImporter.mainArtists("C, Writer"))
        assertEquals(320_000L, QobuzImporter.clockMs("00:05:20"))
        assertEquals(65_000L, QobuzImporter.clockMs("1:05"))
        assertNull(QobuzImporter.clockMs("abc"))
    }
}
