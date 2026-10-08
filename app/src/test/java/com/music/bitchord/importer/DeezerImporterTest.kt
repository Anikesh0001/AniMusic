package com.music.bitchord.importer

import com.music.bitchord.data.importer.DeezerImporter
import com.music.bitchord.data.importer.DeezerImporter.Kind
import com.music.bitchord.data.importer.ImportException
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeezerImporterTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun linkShapes() {
        mapOf(
            "https://www.deezer.com/en/playlist/908622995" to (Kind.PLAYLIST to "908622995"),
            "https://www.deezer.com/playlist/908622995?utm_source=share" to (Kind.PLAYLIST to "908622995"),
            "https://deezer.com/fr/album/302127" to (Kind.ALBUM to "302127"),
            "http://www.deezer.com/us/track/3135556/" to (Kind.TRACK to "3135556"),
            "https://m.deezer.com/pt-br/album/302127" to (Kind.ALBUM to "302127"),
        ).forEach { (url, want) -> assertEquals(url, want, DeezerImporter.extract(url)) }
        assertNull(DeezerImporter.extract("https://www.deezer.com/en/artist/27"))
        assertNull(DeezerImporter.extract("https://www.deezer.com/en/playlist/abc"))
        assertNull(DeezerImporter.extract("https://example.com/playlist/1"))
    }

    @Test
    fun registryRoutesDeezerAndKnowsItsShortLinks() {
        assertEquals(ImportService.DEEZER, ImporterRegistry.find("https://www.deezer.com/en/album/302127")?.service)
        assertTrue(ImporterRegistry.isShortLink("https://deezer.page.link/abc123"))
        assertTrue(ImporterRegistry.isShortLink("https://link.deezer.com/s/30abc"))
    }

    @Test
    fun parsesATracksPage() {
        val (rows, next) = DeezerImporter.parseTracksPage(json(Fixtures.read("deezer_playlist_tracks.json")))
        assertEquals(3, rows.size)
        val first = rows.first()
        assertEquals("Hey Jude (Remastered 2015)", first.title)
        assertEquals("The Beatles", first.artist)
        assertEquals(429_000L, first.durationMs)
        assertEquals("GBUM71505902", first.isrc)
        assertEquals("https://api.deezer.com/playlist/908622995/tracks?limit=25&index=25", next)
    }

    @Test
    fun albumTitleOverridesRowAlbum() {
        val (rows, _) = DeezerImporter.parseTracksPage(json(Fixtures.read("deezer_playlist_tracks.json")), album = "X")
        assertTrue(rows.all { it.album == "X" })
    }

    @Test
    fun parsesASingleTrack() {
        val row = DeezerImporter.parseTrack(json(Fixtures.read("deezer_track.json")))!!
        assertEquals("Harder, Better, Faster, Stronger", row.title)
        assertEquals("Daft Punk", row.artist)
        assertEquals("Discovery", row.album)
        assertEquals(226_000L, row.durationMs)
    }

    @Test
    fun errorsBecomeReasons() {
        fun reasonOf(body: String) = try {
            DeezerImporter.checkError(json(body)); null
        } catch (e: ImportException) {
            e.reason
        }
        assertEquals(ImportException.Reason.NOT_FOUND, reasonOf("""{"error":{"type":"DataException","message":"no data","code":800}}"""))
        assertEquals(ImportException.Reason.RATE_LIMITED, reasonOf("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}"""))
        assertNull(reasonOf("""{"id":1}"""))
        try {
            DeezerImporter.checkError(json("""{"error":{"code":800}}"""))
            fail()
        } catch (_: ImportException) {
        }
    }
}
