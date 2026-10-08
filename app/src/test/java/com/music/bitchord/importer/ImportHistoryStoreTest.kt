package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportHistoryStore
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.ImportedCollection
import com.music.bitchord.data.importer.ResolveResult
import com.music.bitchord.data.importer.ResolvedTrack
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportHistoryStoreTest {

    private val collection = ImportedCollection(
        service = ImportService.DEEZER,
        sourceUrl = "https://www.deezer.com/playlist/1",
        title = "Mix",
        tracks = listOf(ImportTrack("A", "X"), ImportTrack("B", "Y"), ImportTrack("a ", "x")),
        missingCount = 2,
    )
    private val result = ResolveResult(
        listOf(ResolvedTrack(collection.tracks[0], Song("v1", "A", "X", null), true)),
    )

    @Test
    fun recordsAndFindsByEitherBrowseIdSpelling() {
        val record = ImportHistoryStore.record(collection, result, "VLPL123", savedLocally = false, now = 42)
        assertEquals("DEEZER", record.service)
        assertEquals("Deezer", record.serviceLabel)
        assertEquals(1, record.matched)
        assertEquals(5, record.total)
        // "a " / "x" is the same row as "A" / "X".
        assertEquals(2, record.sourceKeys.size)
        assertNotNull(ImportHistoryStore.forBrowseId("PL123"))
        assertNotNull(ImportHistoryStore.forBrowseId("VLPL123"))
        ImportHistoryStore.remove(record.id)
    }

    @Test
    fun survivesARoundTripAndOlderShapes() {
        val record = ImportHistoryStore.record(collection, result, "local:playlist:sp_local_1", savedLocally = true, now = 7)
        val decoded = ImportHistoryStore.decode(ImportHistoryStore.encode(listOf(record)))
        assertEquals(listOf(record), decoded)
        // A record written without the optional fields still loads.
        val minimal = ImportHistoryStore.decode(
            """[{"id":"x","service":"SPOTIFY","sourceUrl":"u","title":"t","importedAt":1,"matched":1,"total":1}]""",
        )
        assertEquals(1, minimal.size)
        assertTrue(minimal.single().sourceKeys.isEmpty())
        assertTrue(ImportHistoryStore.decode("not json").isEmpty())
        ImportHistoryStore.remove(record.id)
    }
}
