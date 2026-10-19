package com.music.bitchord.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergeTest {

    private fun merge(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>) =
        SyncMerge.merge(base, local, remote)

    @Test
    fun firstSyncKeepsBothSidesPlaylists() {
        val out = merge(emptyMap(), mapOf("a" to "A"), mapOf("b" to "B"))
        assertEquals(listOf("a", "b"), out.keys.toList())
    }

    @Test
    fun editOnOneSideWins() {
        val base = mapOf("a" to "A1")
        assertEquals(mapOf("a" to "A2"), merge(base, mapOf("a" to "A1"), mapOf("a" to "A2")))
        assertEquals(mapOf("a" to "A3"), merge(base, mapOf("a" to "A3"), mapOf("a" to "A1")))
    }

    @Test
    fun deletionGoesThroughWhenTheOtherSideDidNotTouchIt() {
        val base = mapOf("a" to "A", "b" to "B")
        assertEquals(mapOf("b" to "B"), merge(base, mapOf("b" to "B"), base))
        assertEquals(mapOf("a" to "A"), merge(base, base, mapOf("a" to "A")))
    }

    @Test
    fun bothChangedThisDeviceWinsAndAnEditBeatsADeletion() {
        val base = mapOf("a" to "A")
        assertEquals(mapOf("a" to "L"), merge(base, mapOf("a" to "L"), mapOf("a" to "R")))
        assertEquals(mapOf("a" to "R"), merge(base, emptyMap(), mapOf("a" to "R")))
        assertEquals(mapOf("a" to "L"), merge(base, mapOf("a" to "L"), emptyMap()))
    }
}
