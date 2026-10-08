package com.music.bitchord.importer

import com.music.bitchord.data.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateApkPickTest {

    private val release = listOf(
        "AniMusic-1.0.2-universal.apk" to "u",
        "AniMusic-1.0.2-arm64-v8a.apk" to "arm64",
        "AniMusic-1.0.2-armeabi-v7a.apk" to "v7",
    )

    @Test
    fun picksTheDevicesAbiThenUniversal() {
        assertEquals("arm64", AppUpdateChecker.pickApk(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("v7", AppUpdateChecker.pickApk(release, listOf("armeabi-v7a", "armeabi")))
        assertEquals("u", AppUpdateChecker.pickApk(release, listOf("x86_64", "x86")))
    }

    @Test
    fun aSingleApkReleaseStillWorks() {
        assertEquals("only", AppUpdateChecker.pickApk(listOf("BitChord-v1.9.apk" to "only"), listOf("arm64-v8a")))
        assertNull(AppUpdateChecker.pickApk(emptyList(), listOf("arm64-v8a")))
    }
}
