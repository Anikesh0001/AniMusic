package com.music.bitchord.importer

import com.music.bitchord.data.stats.UsagePing
import com.music.bitchord.data.stats.UsagePing.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UsagePingTest {

    private class MemoryStore(override var enabled: Boolean = true) : UsagePing.Store {
        override var installId: String? = null
        override var lastPingDay: String? = null
    }

    private val sent = mutableListOf<String>()
    private var day = "2026-10-09"
    private var incognito = false

    private fun ping(
        store: UsagePing.Store,
        url: String = "https://stats.example/ping",
        post: suspend (String, String) -> Int = { _, body -> sent += body; 204 },
    ) = UsagePing(
        store = store,
        url = url,
        appVersion = "1.0.3",
        androidSdk = 34,
        incognito = { incognito },
        today = { day },
        post = post,
    )

    @Test
    fun atMostOncePerUtcDay() = runTest {
        val store = MemoryStore()
        val p = ping(store)
        assertTrue(p.isDue())
        assertEquals(Outcome.SENT, p.pingIfDue())
        assertFalse(p.isDue())
        assertEquals(Outcome.ALREADY_TODAY, p.pingIfDue())
        assertEquals(1, sent.size)
        day = "2026-10-10"
        assertEquals(Outcome.SENT, p.pingIfDue())
        assertEquals(2, sent.size)
    }

    @Test
    fun theSwitchTurnsItOff() = runTest {
        val store = MemoryStore(enabled = false)
        val p = ping(store)
        assertFalse(p.isDue())
        assertEquals(Outcome.OFF, p.pingIfDue())
        assertTrue(sent.isEmpty())
        // No id is even made while it is off.
        assertNull(store.installId)
    }

    @Test
    fun noEndpointMeansNothingIsSent() = runTest {
        val p = ping(MemoryStore(), url = "")
        assertFalse(p.isDue())
        assertEquals(Outcome.NO_ENDPOINT, p.pingIfDue())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun incognitoSkipsAndLeavesTheDayOpen() = runTest {
        val store = MemoryStore()
        val p = ping(store)
        incognito = true
        assertEquals(Outcome.INCOGNITO, p.pingIfDue())
        assertTrue(sent.isEmpty())
        assertNull(store.lastPingDay)
        incognito = false
        assertEquals(Outcome.SENT, p.pingIfDue())
    }

    @Test
    fun failuresAreSwallowedAndRetriedNextStart() = runTest {
        val store = MemoryStore()
        val offline = ping(store) { _, _ -> throw IOException("no network") }
        assertEquals(Outcome.FAILED, offline.pingIfDue())
        assertNull(store.lastPingDay)
        val broken = ping(store) { _, _ -> throw IllegalStateException("boom") }
        assertEquals(Outcome.FAILED, broken.pingIfDue())
        // Any HTTP answer, even an error, settles the day.
        val serverError = ping(store) { _, _ -> 500 }
        assertEquals(Outcome.SENT, serverError.pingIfDue())
        assertEquals("2026-10-09", store.lastPingDay)
    }

    @Test
    fun sendsOnlyARandomStableIdTheVersionAndTheSdk() = runTest {
        val store = MemoryStore()
        val p = ping(store)
        p.pingIfDue()
        val id = store.installId!!
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertEquals("""{"id":"$id","appVersion":"1.0.3","androidSdk":34}""", sent.single())
        assertEquals(id, p.installId())
    }
}
