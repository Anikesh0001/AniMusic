package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.ListenBrainzImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzImporterTest {

    private val mbid = "39ec44ca-7585-4524-82bc-e4a5d3877390"

    @Test
    fun listenBrainzLinks() {
        assertEquals(mbid, ListenBrainzImporter.playlistId("https://listenbrainz.org/playlist/$mbid"))
        assertEquals(mbid, ListenBrainzImporter.playlistId("https://listenbrainz.org/playlist/$mbid/?x=1"))
        assertNull(ListenBrainzImporter.playlistId("https://listenbrainz.org/user/rob/"))
        assertNull(ListenBrainzImporter.playlistId("https://listenbrainz.org/playlist/not-an-mbid"))
        assertEquals(ImportService.LISTENBRAINZ, ImporterRegistry.find("https://listenbrainz.org/playlist/$mbid")?.service)
    }

    @Test
    fun listenBrainzUserPlaylists() {
        val list = ListenBrainzImporter.parsePlaylistList(Fixtures.read("listenbrainz_playlists.json"), createdFor = true)
        assertEquals(2, list.size)
        assertTrue(list.all { it.createdFor && it.mbid.length == 36 })
    }

    @Test
    fun listenBrainzJspf() {
        val playlist = ListenBrainzImporter.parsePlaylist(Fixtures.read("listenbrainz_playlist.json"), mbid)!!
        assertEquals("https://listenbrainz.org/playlist/$mbid", playlist.sourceUrl)
        val first = playlist.tracks.first()
        assertEquals("Seeker", first.title)
        assertEquals("Carbon Based Lifeforms", first.artist)
        assertEquals("Seeker", first.album)
        assertEquals(450_000L, first.durationMs)
        assertNull(ListenBrainzImporter.parsePlaylist("""{"code":404,"error":"Cannot find playlist"}""", mbid))
    }
}
