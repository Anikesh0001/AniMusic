package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.TrackResolver
import com.music.bitchord.data.importer.YouTubeMusicImporter
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicImporterTest {

    @Test
    fun playlistAndAlbumLinks() {
        mapOf(
            "https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs" to "VLRDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs",
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&si=abc" to "VLPLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            "https://m.youtube.com/playlist?list=PL123" to "VLPL123",
            "https://music.youtube.com/watch?list=OLAK5uy_abc" to "VLOLAK5uy_abc",
            "https://music.youtube.com/browse/MPREb_abc123" to "MPREb_abc123",
            "https://music.youtube.com/browse/VLPL999" to "VLPL999",
        ).forEach { (url, want) -> assertEquals(url, want, YouTubeMusicImporter.browseIdOf(url)) }
    }

    @Test
    fun songsArtistsAndOtherSitesAreNotPlaylists() {
        listOf(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://music.youtube.com/channel/UC123",
            "https://music.youtube.com/browse/UC123",
            "https://music.youtube.com/playlist",
            "https://example.com/playlist?list=PL1",
        ).forEach { assertNull(it, YouTubeMusicImporter.browseIdOf(it)) }
        assertFalse(YouTubeMusicImporter.canHandle("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun registryRoutesPlaylistsHere() {
        assertEquals(
            ImportService.YOUTUBE_MUSIC,
            ImporterRegistry.find("https://music.youtube.com/playlist?list=PL1")?.service,
        )
    }

    @Test
    fun rowsThatAreAlreadySongsAreKeptWithoutSearching() = runTest {
        var searched = 0
        val resolver = TrackResolver(search = { searched++; emptyList() })
        val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, durationText = "3:33")
        val row = resolver.match(YouTubeMusicImporter.trackOf(song))
        assertEquals(song, row.song)
        assertTrue(row.confident)
        assertEquals(0, searched)
        assertEquals(213_000L, YouTubeMusicImporter.trackOf(song).durationMs)
    }
}
