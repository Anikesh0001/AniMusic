package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.JioSaavnImporter
import com.music.bitchord.data.importer.JioSaavnImporter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JioSaavnImporterTest {

    @Test
    fun linkShapes() {
        mapOf(
            "https://www.jiosaavn.com/featured/dumdaar-hits/8MT-LQlP35c_" to (Kind.PLAYLIST to "8MT-LQlP35c_"),
            "https://jiosaavn.com/featured/dumdaar-hits/8MT-LQlP35c_?referrer=share" to (Kind.PLAYLIST to "8MT-LQlP35c_"),
            "https://www.jiosaavn.com/s/playlist/2279d4a1b2/my-mix/abcDEF12_" to (Kind.PLAYLIST to "abcDEF12_"),
            "https://www.jiosaavn.com/album/paagal-from-bhoomi-2026/ap3z2BCLQH4_" to (Kind.ALBUM to "ap3z2BCLQH4_"),
            "https://www.jiosaavn.com/song/paagal-from-bhoomi-2026/HhodABgCfwo" to (Kind.SONG to "HhodABgCfwo"),
            "https://www.saavn.com/album/x/ap3z2BCLQH4_" to (Kind.ALBUM to "ap3z2BCLQH4_"),
        ).forEach { (url, want) -> assertEquals(url, want, JioSaavnImporter.extract(url)) }
        assertNull(JioSaavnImporter.extract("https://www.jiosaavn.com/artist/arijit-singh-songs/LlRWpHzy3Hk_"))
        assertNull(JioSaavnImporter.extract("https://www.jiosaavn.com/featured/"))
        assertNull(JioSaavnImporter.extract("https://www.jiosaavn.com/album/ap3z2BCLQH4_"))
        assertEquals(ImportService.JIOSAAVN, ImporterRegistry.find("https://www.jiosaavn.com/album/x/ap3z2BCLQH4_")?.service)
        assertTrue(ImporterRegistry.isShortLink("https://jiosaavn.page.link/abc"))
    }

    @Test
    fun playlistPage() {
        val page = JioSaavnImporter.parseCollection(Fixtures.read("jiosaavn_playlist.json"))!!
        assertEquals("Dumdaar Hits", page.title)
        assertEquals(20, page.total)
        assertEquals(3, page.tracks.size)
        val first = page.tracks.first()
        assertEquals("Paagal (From \"Bhoomi 2026\")", first.title)
        assertEquals("Salim-Sulaiman, Sonu Nigam, Shraddha Pandit", first.artist)
        assertEquals("Paagal (From \"Bhoomi 2026\")", first.album)
        assertEquals(183_000L, first.durationMs)
    }

    @Test
    fun songAnswer() {
        val song = JioSaavnImporter.parseSongs(Fixtures.read("jiosaavn_song.json")).single()
        assertEquals("Paagal (From \"Bhoomi 2026\")", song.title)
        assertTrue(song.artist.contains("Sonu Nigam"))
    }

    @Test
    fun unknownTokenIsAnEmptyPage() {
        val page = JioSaavnImporter.parseCollection(
            """{"id":"","title":"","type":"playlist","list_count":"0","list":""}""",
        )!!
        assertTrue(page.tracks.isEmpty())
        assertEquals(0, page.total)
        assertNull(JioSaavnImporter.parseCollection("<html>not json</html>"))
    }
}
