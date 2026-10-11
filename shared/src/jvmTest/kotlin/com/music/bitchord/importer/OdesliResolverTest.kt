package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportTrack
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.OdesliResolver
import com.music.bitchord.data.importer.TrackResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdesliResolverTest {

    @Test
    fun songPage() {
        val entity = OdesliResolver.parse(Fixtures.read("songlink_song.html"))!!
        assertEquals("song", entity.type)
        assertEquals("Harder, Better, Faster, Stronger", entity.track.title)
        assertEquals("Daft Punk", entity.track.artist)
        assertEquals(226_000L, entity.track.durationMs)
        assertTrue(entity.links.any { it.contains("tidal.com/track/1550549") })
    }

    @Test
    fun albumPageListsItsOtherLinks() {
        val entity = OdesliResolver.parse(Fixtures.read("songlink_album.html"))!!
        assertEquals("album", entity.type)
        assertEquals("Discovery", entity.track.title)
        // Deezer is one the page resolved, and Deezer has an importer.
        val deezer = entity.links.first { it.contains("deezer.com/album/302127") }
        assertEquals(ImportService.DEEZER, ImporterRegistry.findDedicated(deezer)?.service)
    }

    @Test
    fun videoIds() {
        assertEquals("dQw4w9WgXcQ", OdesliResolver.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", OdesliResolver.videoIdOf("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=x"))
        assertEquals("dQw4w9WgXcQ", OdesliResolver.videoIdOf("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(OdesliResolver.videoIdOf("https://www.youtube.com/channel/UC123"))
    }

    @Test
    fun songLinkHosts() {
        assertTrue(OdesliResolver.canHandle("https://song.link/t/1550549"))
        assertTrue(OdesliResolver.canHandle("https://album.link/d/302127"))
        assertTrue(OdesliResolver.canHandle("https://odesli.co/abc"))
        assertEquals(ImportService.ODESLI, ImporterRegistry.find("https://song.link/s/xyz")?.service)
    }

    @Test
    fun aKnownVideoIdIsPlayedWithoutSearching() = runTest {
        var searched = false
        val resolver = TrackResolver(
            search = { searched = true; emptyList() },
            lookup = { id -> Song(videoId = id, title = "T", artist = "A", thumbnailUrl = null) },
        )
        val row = resolver.match(ImportTrack("T", "A", videoId = "dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", row.song?.videoId)
        assertTrue(row.confident)
        assertTrue(!searched)
    }
}
