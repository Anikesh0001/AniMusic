package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One GitHub release carries the APKs and every desktop package (see CLAUDE.md). */
class DesktopUpdateCheckerTest {

    private val release = listOf(
        "AniMusic-1.0.4-arm64-v8a.apk",
        "AniMusic-1.0.4-universal.apk",
        "AniMusic-1.0.4-macos-arm64.dmg",
        "AniMusic-1.0.4-macos-x64.dmg",
        "AniMusic-1.0.4-windows-x64-setup.exe",
        "AniMusic-1.0.4-windows-x64.msi",
        "AniMusic-1.0.4-windows-x64-portable.zip",
        "AniMusic-1.0.4-linux-x86_64.AppImage",
        "AniMusic-1.0.4-linux-amd64.deb",
        "AniMusic-1.0.4-linux-x86_64.rpm",
    ).map { it to "https://dl/$it" }

    private fun pick(assets: List<Pair<String, String>>, os: String, arch: String) =
        DesktopUpdateChecker.pickInstaller(assets, os, arch)?.substringAfterLast('/')

    @Test
    fun eachPlatformGetsItsInstaller() {
        assertEquals("AniMusic-1.0.4-macos-arm64.dmg", pick(release, "macos", "aarch64"))
        assertEquals("AniMusic-1.0.4-macos-x64.dmg", pick(release, "macos", "x86_64"))
        assertEquals("AniMusic-1.0.4-windows-x64-setup.exe", pick(release, "windows", "amd64"))
        assertEquals("AniMusic-1.0.4-linux-x86_64.AppImage", pick(release, "linux", "amd64"))
    }

    @Test
    fun fallbacks() {
        val noArmDmg = release.filterNot { it.first.endsWith("macos-arm64.dmg") }
        assertEquals("AniMusic-1.0.4-macos-x64.dmg", pick(noArmDmg, "macos", "arm64"))
        // An Intel Mac never gets the Apple Silicon build.
        val armOnly = release.filterNot { it.first.endsWith("macos-x64.dmg") }
        assertNull(pick(armOnly, "macos", "x86_64"))
        val noSetup = release.filterNot { it.first.endsWith("setup.exe") }
        assertEquals("AniMusic-1.0.4-windows-x64.msi", pick(noSetup, "windows", "amd64"))
        val noAppImage = release.filterNot { it.first.endsWith(".AppImage") }
        assertEquals("AniMusic-1.0.4-linux-amd64.deb", pick(noAppImage, "linux", "amd64"))
    }

    @Test
    fun anApkIsNeverOffered() {
        val apksOnly = release.filter { it.first.endsWith(".apk") }
        for ((os, arch) in listOf("macos" to "aarch64", "windows" to "amd64", "linux" to "amd64")) {
            assertNull(pick(apksOnly, os, arch), "$os/$arch")
        }
    }

    @Test
    fun anotherBrandsFilesAreIgnored() {
        assertTrue(DesktopBrand.isAniMusic)
        val upstream = listOf("BitChord-1.9-linux-x86_64.AppImage" to "https://dl/BitChord-1.9-linux-x86_64.AppImage")
        assertNull(pick(upstream, "linux", "amd64"))
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(DesktopUpdateChecker.isNewer("1.0.10", "1.0.9"))
        assertTrue(!DesktopUpdateChecker.isNewer("1.0.3", "1.0.3"))
    }

    @Test
    fun brandText() {
        assertEquals("AniMusic", DesktopBrand.name)
        assertEquals("Anikesh0001/AniMusic", DesktopBrand.updateRepo)
        assertEquals("com.anikesh.animusic.desktop", DesktopBrand.preferencesNode)
        assertEquals("Open AniMusic", DesktopBrand.rename("Open BitChord"))
        assertEquals("AniMusic 1.0.4 is available to download.", DesktopBrand.rename("bitchord 1.0.4 is available to download."))
        // Links and dotted ids stay as they are.
        assertEquals("bitchord://party/X", DesktopBrand.rename("bitchord://party/X"))
        assertEquals(
            "This party is hosted on the official BitChord server.",
            DesktopBrand.rename("This party is hosted on the official BitChord server.", "listen_together_official_server_dialog_message"),
        )
        assertEquals("AniMusic by Anikesh Kumar · Based on BitChord by Kushagra Singh · GPLv3", DesktopBrand.ANIMUSIC_CREDIT)
    }
}
