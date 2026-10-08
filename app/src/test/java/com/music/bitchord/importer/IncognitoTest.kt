package com.music.bitchord.importer

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test

/** Incognito stops a listen before anything is sent: no network, no token use. */
class IncognitoTest {

    private val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, durationText = "3:33")

    @After
    fun reset() {
        AppSettings.incognitoMode.value = false
    }

    @Test
    fun listenBrainzSendsNothingWhileIncognito() = runTest {
        AppSettings.incognitoMode.value = true
        assertFalse(ListenBrainzManager.submitPlayingNow("token", song, 0))
        assertFalse(ListenBrainzManager.submitFinished("token", song, 1_700_000_000_000, 1_700_000_200_000))
    }
}
