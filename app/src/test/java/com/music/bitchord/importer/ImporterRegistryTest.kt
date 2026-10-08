package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImportUrls
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.SpotifyPlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterRegistryTest {

    @Test
    fun firstUrlFindsTheLinkInSharedProse() {
        assertEquals(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc",
            ImportUrls.firstUrl("Check this out! https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc."),
        )
        assertEquals("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", ImportUrls.firstUrl("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(ImportUrls.firstUrl("no link here"))
    }

    @Test
    fun hostDropsWwwAndMobilePrefixes() {
        assertEquals("deezer.com", ImportUrls.host("https://www.deezer.com/en/playlist/1"))
        assertEquals("soundcloud.com", ImportUrls.host("https://m.soundcloud.com/a/sets/b"))
    }

    @Test
    fun localeSegmentIsSkipped() {
        assertEquals(listOf("playlist", "123"), ImportUrls.segmentsWithoutLocale("https://www.deezer.com/fr/playlist/123"))
        assertEquals(listOf("album", "abc"), ImportUrls.segmentsWithoutLocale("https://open.spotify.com/intl-de/album/abc"))
        assertEquals(listOf("playlist", "123"), ImportUrls.segmentsWithoutLocale("https://www.deezer.com/playlist/123"))
    }

    @Test
    fun spotifyPlaylistIds() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        listOf(
            "https://open.spotify.com/playlist/$id",
            "https://open.spotify.com/playlist/$id?si=1a2b3c",
            "https://open.spotify.com/intl-de/playlist/$id",
            "http://open.spotify.com/playlist/$id/",
            "spotify:playlist:$id",
            "  https://open.spotify.com/embed/playlist/$id  ",
        ).forEach { assertEquals(it, id, SpotifyPlaylistImporter.extractPlaylistId(it)) }
        assertNull(SpotifyPlaylistImporter.extractPlaylistId("https://open.spotify.com/playlist/short"))
        assertNull(SpotifyPlaylistImporter.extractPlaylistId("https://example.com/playlist/$id"))
        assertNull(SpotifyPlaylistImporter.extractPlaylistId(""))
    }

    @Test
    fun registryFindsSpotify() {
        assertEquals(
            ImportService.SPOTIFY,
            ImporterRegistry.find("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")?.service,
        )
    }

    @Test
    fun shortLinksAreRecognised() {
        assertTrue(ImporterRegistry.isShortLink("https://spotify.link/AbCdEf"))
        assertTrue(ImporterRegistry.isShortLink("https://deezer.page.link/xyz"))
        assertTrue(ImporterRegistry.isShortLink("https://on.soundcloud.com/abc"))
        assertTrue(ImporterRegistry.isShortLink("https://gaana.com/s/abc"))
        assertTrue(ImporterRegistry.isShortLink("https://wynk.in/u/abc"))
        assertFalse(ImporterRegistry.isShortLink("https://gaana.com/playlist/abc"))
        assertFalse(ImporterRegistry.isShortLink("https://open.spotify.com/playlist/abc"))
    }

    @Test
    fun serviceDetectionForTheDialog() {
        assertEquals(ImportService.SPOTIFY, ImportService.detect("https://open.spotify.com/album/x"))
        assertEquals(ImportService.DEEZER, ImportService.detect("look https://www.deezer.com/en/playlist/1"))
        assertEquals(ImportService.APPLE_MUSIC, ImportService.detect("https://music.apple.com/us/album/x/1"))
        assertEquals(ImportService.AMAZON_MUSIC, ImportService.detect("https://music.amazon.in/playlists/B0"))
        assertNull(ImportService.detect("hello"))
    }
}

class YouTubeDetectionTest {
    @org.junit.Test
    fun youTubeLinksAreDetected() {
        listOf(
            "https://music.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=x",
        ).forEach {
            org.junit.Assert.assertEquals(it, com.music.bitchord.data.importer.ImportService.YOUTUBE_MUSIC,
                com.music.bitchord.data.importer.ImportService.detect(it))
        }
    }
}

class AllUrlsTest {
    @org.junit.Test
    fun splitsGluedAndSeparatedLinks() {
        val a = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val b = "https://www.deezer.com/en/album/302127"
        org.junit.Assert.assertEquals(listOf(a, b), com.music.bitchord.data.importer.ImportUrls.allUrls("$a\n$b"))
        org.junit.Assert.assertEquals(listOf(a, b), com.music.bitchord.data.importer.ImportUrls.allUrls("$a$b"))
        org.junit.Assert.assertEquals(listOf(a, b), com.music.bitchord.data.importer.ImportUrls.allUrls("first: $a, then $b."))
        org.junit.Assert.assertEquals(emptyList<String>(), com.music.bitchord.data.importer.ImportUrls.allUrls("nothing here"))
    }
}
