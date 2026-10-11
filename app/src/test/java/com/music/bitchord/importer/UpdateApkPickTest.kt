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

    /** One release carries the APKs and every desktop package (CLAUDE.md). */
    private val mixedRelease = listOf(
        "AniMusic-1.0.4-macos-arm64.dmg" to "mac-arm",
        "AniMusic-1.0.4-macos-x64.dmg" to "mac-x64",
        "AniMusic-1.0.4-windows-x64-setup.exe" to "win",
        "AniMusic-1.0.4-windows-x64.msi" to "msi",
        "AniMusic-1.0.4-windows-x64-portable.zip" to "zip",
        "AniMusic-1.0.4-linux-x86_64.AppImage" to "appimage",
        "AniMusic-1.0.4-linux-amd64.deb" to "deb",
        "AniMusic-1.0.4-linux-x86_64.rpm" to "rpm",
        "AniMusic-1.0.4-universal.apk" to "u",
        "AniMusic-1.0.4-arm64-v8a.apk" to "arm64",
    )

    @Test
    fun desktopFilesInTheSameReleaseAreNeverPicked() {
        assertEquals("arm64", AppUpdateChecker.pickApk(mixedRelease, listOf("arm64-v8a", "armeabi-v7a")))
        // "x86_64" is in the AppImage and rpm names: an x86_64 device must still get an APK.
        assertEquals("u", AppUpdateChecker.pickApk(mixedRelease, listOf("x86_64", "x86")))
        assertEquals("u", AppUpdateChecker.pickApk(mixedRelease, listOf("armeabi-v7a")))
        // A release whose APKs are missing offers nothing, rather than a desktop file.
        assertNull(AppUpdateChecker.pickApk(mixedRelease.filterNot { it.first.endsWith(".apk") }, listOf("arm64-v8a")))
    }

    @Test
    fun aSingleApkReleaseStillWorks() {
        assertEquals("only", AppUpdateChecker.pickApk(listOf("BitChord-v1.9.apk" to "only"), listOf("arm64-v8a")))
        assertNull(AppUpdateChecker.pickApk(emptyList(), listOf("arm64-v8a")))
    }
}
