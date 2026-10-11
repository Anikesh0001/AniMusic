package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looks at the repo's "latest release" on GitHub once per launch and says whether it is newer
 * than the running build. The installer is not run from here: the dialog opens the asset in the
 * browser, so the user installs it the way they installed the first one.
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The installer for this platform, or null when the release has none. */
        val downloadUrl: String?,
        val notes: String?,
    )

    /**
     * This brand's feed. For AniMusic it is the same GitHub release the Android APKs are in
     * (Anikesh0001/AniMusic, tag vX.Y.Z): the desktop packages are added to that release by
     * .github/workflows/animusic-desktop.yml, never published as a release of their own.
     */
    private val LATEST_RELEASE_URL =
        "https://api.github.com/repos/${DesktopBrand.updateRepo}/releases/latest"

    val currentVersion: String = System.getProperty("bitchord.version") ?: "1.8-beta1"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun check(): UpdateInfo? = runCatching {
        val response = http.get(LATEST_RELEASE_URL) {
            header("Accept", "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) return@runCatching null
        val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val latest = tag.removePrefix("v")
        if (!isNewer(latest, currentVersion)) return@runCatching null
        UpdateInfo(
            version = latest,
            releaseUrl = url,
            downloadUrl = installerUrl(release),
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    /** The installer for the running OS and CPU, from [release]'s uploaded assets. */
    private fun installerUrl(release: JsonObject): String? {
        val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { it["state"]?.jsonPrimitive?.contentOrNull == "uploaded" }
            .mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                name to url
            }
        val os = when {
            DesktopPlatform.isWindows -> "windows"
            DesktopPlatform.isMac -> "macos"
            DesktopPlatform.isLinux -> "linux"
            else -> return null
        }
        return pickInstaller(assets, os, System.getProperty("os.arch").orEmpty())
    }

    /**
     * Which of a release's files ([name] to [url]) this machine should install.
     *
     * Release files are `<Brand>-<version>-<platform>` (see animusic-desktop.yml); only this
     * brand's desktop installers are ever considered, so an APK (or another brand's file) in the
     * same release is never offered. In order of preference:
     *  - macOS: the dmg for this chip; an Apple Silicon Mac falls back to the Intel dmg, which runs
     *    under Rosetta, but an Intel Mac never gets the arm64 one.
     *  - Windows: the setup.exe, then the msi.
     *  - Linux: the AppImage (any distribution), then the deb, then the rpm.
     */
    internal fun pickInstaller(assets: List<Pair<String, String>>, os: String, arch: String): String? {
        val arm = arch.lowercase().let { it == "aarch64" || it == "arm64" }
        val suffixes = when (os) {
            "macos" -> if (arm) listOf("-macos-arm64.dmg", "-macos-x64.dmg") else listOf("-macos-x64.dmg")
            "windows" -> listOf("-windows-x64-setup.exe", "-windows-x64.msi")
            "linux" -> listOf("-linux-x86_64.AppImage", "-linux-amd64.deb", "-linux-x86_64.rpm")
            else -> return null
        }
        val ours = assets.filter { (name, _) -> name.startsWith("${DesktopBrand.assetPrefix}-") }
        for (suffix in suffixes) {
            ours.firstOrNull { (name, _) -> name.endsWith(suffix, ignoreCase = true) }?.let { return it.second }
        }
        return null
    }

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
