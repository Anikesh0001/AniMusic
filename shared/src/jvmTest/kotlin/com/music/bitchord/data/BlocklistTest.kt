package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    private fun song(id: String, artist: String) = Song(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = null)

    @After
    fun reset() {
        Blocklist.onChange = null
        Blocklist.load(Blocklist.Entries())
    }

    @Test
    fun blocksASongById() {
        Blocklist.blockSong(song("aaaaaaaaaaa", "Someone"))
        assertTrue(Blocklist.blocks(song("aaaaaaaaaaa", "Someone")))
        assertFalse(Blocklist.blocks(song("bbbbbbbbbbb", "Someone")))
    }

    @Test
    fun blocksAnArtistWhereverTheyAreCredited() {
        Blocklist.blockArtist("Arijit Singh")
        assertTrue(Blocklist.blocks(song("1", "arijit singh")))
        assertTrue(Blocklist.blocks(song("2", "Shreya Ghoshal & Arijit Singh")))
        assertTrue(Blocklist.blocks(song("3", "Pritam feat. Arijit Singh")))
        assertFalse(Blocklist.blocks(song("4", "Arijit Singhania")))
    }

    @Test
    fun autoplaySkipsBlockedTracks() {
        Blocklist.blockArtist("Blocked")
        val out = QueueBuilder.extend(
            existing = listOf(song("seed", "Seed")),
            candidates = listOf(song("x1", "Blocked"), song("x2", "Fine")),
            limit = 10,
        )
        assertEquals(listOf("x2"), out.map { it.videoId })
    }

    @Test
    fun changesAreReportedAndUnblockWorks() {
        var saved: Blocklist.Entries? = null
        Blocklist.onChange = { saved = it }
        Blocklist.blockArtist("Someone")
        assertEquals(mapOf("someone" to "Someone"), saved?.artists)
        Blocklist.unblockArtist("someone")
        assertTrue(saved!!.artists.isEmpty())
    }
}
