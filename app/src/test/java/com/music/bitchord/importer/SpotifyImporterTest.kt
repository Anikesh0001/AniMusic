package com.music.bitchord.importer

import com.music.bitchord.data.importer.SpotifyPlaylistImporter
import com.music.bitchord.data.importer.SpotifyPlaylistImporter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyImporterTest {

    private val album = "2noRn2Aes5aoNVsU6iWThc"
    private val track = "4cOdK2wGLETKBW3PvgPWqT"

    @Test
    fun albumAndTrackLinks() {
        listOf(
            "https://open.spotify.com/album/$album",
            "https://open.spotify.com/intl-fr/album/$album?si=abc",
            "https://open.spotify.com/embed/album/$album",
            "spotify:album:$album",
        ).forEach { assertEquals(it, Kind.ALBUM to album, SpotifyPlaylistImporter.extract(it)) }
        listOf(
            "https://open.spotify.com/track/$track",
            "https://open.spotify.com/intl-ja/track/$track?si=x&context=y",
            "spotify:track:$track",
        ).forEach { assertEquals(it, Kind.TRACK to track, SpotifyPlaylistImporter.extract(it)) }
        assertNull(SpotifyPlaylistImporter.extract("https://open.spotify.com/artist/$album"))
        assertNull(SpotifyPlaylistImporter.extract("spotify:artist:$album"))
        assertFalse(SpotifyPlaylistImporter.canHandle("https://open.spotify.com/show/$album"))
        assertTrue(SpotifyPlaylistImporter.canHandle("https://open.spotify.com/album/$album"))
        // A playlist link is still only a playlist.
        assertNull(SpotifyPlaylistImporter.extractPlaylistId("https://open.spotify.com/album/$album"))
    }

    @Test
    fun parsesAnAlbumEmbed() {
        val embed = SpotifyPlaylistImporter.parseEmbed(Fixtures.read("spotify_embed_album.html"))
        assertEquals("Discovery", embed.title)
        assertEquals("Daft Punk", embed.subtitle)
        assertEquals(14, embed.tracks.size)
        val first = embed.tracks.first()
        assertEquals("One More Time", first.title)
        assertEquals("Daft Punk", first.artist)
        assertEquals("Discovery", first.album)
        assertEquals(320357L, first.durationMs)
        assertNotNull(embed.coverUrl)
    }

    @Test
    fun parsesATrackEmbed() {
        val embed = SpotifyPlaylistImporter.parseEmbed(Fixtures.read("spotify_embed_track.html"))
        val row = embed.tracks.single()
        assertEquals("Never Gonna Give You Up", row.title)
        assertEquals("Rick Astley", row.artist)
        assertEquals(213573L, row.durationMs)
    }

    @Test
    fun parsesAPlaylistEmbed() {
        val embed = SpotifyPlaylistImporter.parseEmbed(Fixtures.read("spotify_embed_playlist.html"))
        assertEquals(5, embed.tracks.size)
        assertNull(embed.tracks.first().album)
        assertEquals("TEST_TOKEN", embed.accessToken)
        assertNotNull(embed.coverUrl)
    }
}
