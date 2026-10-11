package com.music.bitchord.desktop

import com.music.bitchord.data.importer.ImportException
import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.ResolveResult
import com.music.bitchord.data.importer.ResolvedTrack
import com.music.bitchord.data.model.Song
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopImportTest {

    @Test
    fun `a pasted list becomes a named collection`() {
        val collection = DesktopImport.fromText("  Road trip ", "Daft Punk - One More Time\nAround the World by Daft Punk\n\n")
        assertEquals("Road trip", collection.title)
        assertEquals(ImportService.TEXT, collection.service)
        assertEquals(2, collection.tracks.size)
    }

    @Test
    fun `a pasted list without a name gets the default one`() {
        val title = DesktopImport.fromText(" ", "Cher - Believe").title
        assertTrue(title.isNotBlank() && title == DesktopStrings["import_text_default_name", "Pasted songs"])
    }

    @Test
    fun `an M3U file is read`() {
        val file = File.createTempFile("import", ".m3u").apply {
            deleteOnExit()
            writeText("#EXTM3U\n#EXTINF:215,Cher - Believe\nbelieve.mp3\n")
        }
        val collection = DesktopImport.fromFile(file)
        assertEquals(1, collection.tracks.size)
        assertEquals("Believe", collection.tracks.single().title)
    }

    @Test
    fun `a file with no songs is reported, not saved empty`() {
        val file = File.createTempFile("import", ".txt").apply {
            deleteOnExit()
            writeText("\n\n")
        }
        val error = assertFailsWith<ImportException> { DesktopImport.fromFile(file) }
        assertEquals(ImportException.Reason.NOT_FOUND, error.reason)
    }

    @Test
    fun `a file over the limit is refused before it is read`() {
        val file = File.createTempFile("import", ".csv").apply {
            deleteOnExit()
            writeBytes(ByteArray((DesktopImport.MAX_FILE_BYTES + 1).toInt()))
        }
        assertFailsWith<DesktopImport.FileProblem> { DesktopImport.fromFile(file) }
    }

    @Test
    fun `the saved playlist keeps every matched song once, in order`() {
        fun song(id: String) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null, fromAutoplay = false)
        val rows = listOf(
            ResolvedTrack(ImportTrack("a", "A"), song("v1"), confident = true),
            ResolvedTrack(ImportTrack("b", "A"), null, confident = false),
            ResolvedTrack(ImportTrack("c", "A"), song("v2"), confident = false),
            ResolvedTrack(ImportTrack("a again", "A"), song("v1"), confident = true),
        )
        val playlist = DesktopImport.toPlaylist(" Mix ", ResolveResult(rows))
        assertEquals("Mix", playlist.title)
        assertEquals(listOf("v1", "v2"), playlist.songs.map { it.videoId })
    }

    @Test
    fun `every failure reads as a sentence`() {
        ImportException.Reason.entries.forEach { reason ->
            val text = DesktopImport.errorText(ImportException(reason))
            assertTrue(text.length > 10 && !text.contains("_"), "$reason -> $text")
        }
        assertTrue(DesktopImport.errorText(RuntimeException("boom")).isNotBlank())
    }

    @Test
    fun `the supported list names the services and not the internal sources`() {
        val list = DesktopImport.supportedServices
        assertTrue("Spotify" in list && "Deezer" in list && "Apple Music" in list)
        assertTrue("Web page" !in list && "File" !in list && "Last.fm" !in list)
    }
}
