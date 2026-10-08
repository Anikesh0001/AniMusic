package com.music.bitchord.importer

import com.music.bitchord.data.importer.ImportService
import com.music.bitchord.data.importer.ImporterRegistry
import com.music.bitchord.data.importer.SoundCloudImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCloudImporterTest {

    @Test
    fun linkShapes() {
        listOf(
            "https://soundcloud.com/lofi-hip-hop-music/sets/lofi-lofi",
            "https://m.soundcloud.com/lofi-hip-hop-music/sets/lofi-lofi?si=abc&utm_source=clipboard",
            "https://www.soundcloud.com/noahkahan/stick-season",
            "https://soundcloud.com/noahkahan/stick-season/",
        ).forEach { assertTrue(it, SoundCloudImporter.canHandle(it)) }
        listOf(
            "https://soundcloud.com/noahkahan",
            "https://soundcloud.com/noahkahan/sets",
            "https://soundcloud.com/noahkahan/likes",
            "https://soundcloud.com/discover/sets/charts-top:all-music",
            "https://soundcloud.com/search/sounds?q=x",
            "https://on.soundcloud.com/AbCdE",
        ).forEach { assertFalse(it, SoundCloudImporter.canHandle(it)) }
        assertTrue(ImporterRegistry.isShortLink("https://on.soundcloud.com/AbCdE"))
        assertEquals(ImportService.SOUNDCLOUD, ImporterRegistry.find("https://soundcloud.com/a/sets/b")?.service)
    }

    @Test
    fun setWithDescribedAndBareRows() {
        val page = SoundCloudImporter.parse(Fixtures.read("soundcloud_set.html"), "https://soundcloud.com/x/sets/y")!!
        assertEquals("TEST_CLIENT", page.clientId)
        assertEquals(5, page.order.size)
        assertEquals(3, page.missingIds.size)
        val tracks = page.collection.tracks
        assertEquals(2, tracks.size)
        // Label metadata names the artist; the "Artist - " prefix comes off the title.
        assertEquals("Dreamin'", tracks[0].title)
        assertEquals("Sweet Medicine", tracks[0].artist)
        assertEquals("DEQ022005723", tracks[0].isrc)
        assertTrue(page.collection.coverUrl!!.contains("t500x500"))
    }

    @Test
    fun singleTrackPrefersFullDuration() {
        val page = SoundCloudImporter.parse(Fixtures.read("soundcloud_track.html"), "https://soundcloud.com/noahkahan/stick-season")!!
        val track = page.collection.tracks.single()
        assertEquals("Stick Season", track.title)
        assertEquals("Noah Kahan", track.artist)
        assertEquals("Stick Season", track.album)
        // `duration` is a 30-second preview here; `full_duration` is the song.
        assertEquals(182_387L, track.durationMs)
        assertTrue(page.missingIds.isEmpty())
    }

    @Test
    fun batchAnswer() {
        val batch = SoundCloudImporter.parseTrackBatch(Fixtures.read("soundcloud_tracks_batch.json"))
        assertEquals(2, batch.size)
        assertTrue(batch.values.any { it.title == "Waiting" && it.artist == "Sweet Medicine" })
    }

    @Test
    fun uploaderTitleConventions() {
        fun track(json: String) = SoundCloudImporter.parseTrack(Json.parseToJsonElement(json).jsonObject)!!
        val split = track("""{"title":"Artist Name - Song Name","user":{"username":"uploader"}}""")
        assertEquals("Song Name" to "Artist Name", split.title to split.artist)
        val guest = track("""{"title":"Sweet Medicine w/ ØDYSSEE - Breezin'","publisher_metadata":{"artist":"Sweet Medicine"}}""")
        assertEquals("Breezin'" to "Sweet Medicine w/ ØDYSSEE", guest.title to guest.artist)
        val plain = track("""{"title":"Song Name","user":{"username":"uploader"}}""")
        assertEquals("Song Name" to "uploader", plain.title to plain.artist)
    }
}
