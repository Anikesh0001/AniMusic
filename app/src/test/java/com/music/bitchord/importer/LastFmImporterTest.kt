package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportException
import com.music.bitchord.data.importer.LastFmImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LastFmImporterTest {

    @Test
    fun lastFmLovedPage() {
        val (rows, pages) = LastFmImporter.parsePage(Fixtures.read("lastfm_loved.json"), LastFmImporter.Kind.LOVED)
        assertEquals(3, pages)
        assertEquals(listOf("Believe" to "Cher", "Around the World" to "Daft Punk"), rows.map { it.title to it.artist })
    }

    @Test
    fun lastFmSingleRowPageIsAnObject() {
        val (rows, pages) = LastFmImporter.parsePage(Fixtures.read("lastfm_top_single.json"), LastFmImporter.Kind.TOP)
        assertEquals(1, pages)
        assertEquals(320_000L, rows.single().durationMs)
    }

    @Test
    fun lastFmErrors() {
        try {
            LastFmImporter.parsePage("""{"error":6,"message":"User not found"}""", LastFmImporter.Kind.LOVED)
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.NOT_FOUND, e.reason)
        }
    }
}
