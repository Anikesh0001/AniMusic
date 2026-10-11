package com.music.bitchord.desktop

/**
 * Which app this desktop build is: AniMusic (the default) or upstream BitChord.
 *
 * Chosen at build time by the `desktop.brand` Gradle property and handed to the
 * running app as the `bitchord.brand` system property (see desktopApp/build.gradle.kts),
 * so the same sources build either. Everything a user or the operating system sees
 * by name comes from here: the window and tray titles, the update feed, the credit
 * line, the folders and the settings store. The Kotlin package names stay
 * `com.music.bitchord` either way.
 *
 * The two brands keep separate settings, caches and download folders, so AniMusic
 * and BitChord can be installed side by side, as the Android apps are.
 */
object DesktopBrand {

    val isAniMusic: Boolean = (System.getProperty("bitchord.brand") ?: "animusic").lowercase() != "bitchord"

    /** Display name. */
    val name: String = if (isAniMusic) "AniMusic" else "BitChord"

    /** Lower-case id for folders, D-Bus and keyring entries, and the Linux `.desktop` file. */
    val id: String = if (isAniMusic) "animusic" else "bitchord"

    /** The java.util.prefs node every desktop setting lives under. */
    val preferencesNode: String = if (isAniMusic) "com.anikesh.animusic.desktop" else "com.music.bitchord.desktop"

    /** GitHub repository whose latest release is the update feed ("owner/name"). */
    val updateRepo: String = if (isAniMusic) "Anikesh0001/AniMusic" else "kushagrasinghx/BitChord"

    /** Release files are `<assetPrefix>-<version>-<platform>.<ext>`. */
    val assetPrefix: String = name

    /** Where this build's source is published (GPLv3). */
    val sourceUrl: String = "https://github.com/$updateRepo"

    /** The credit line the settings footer carries in an AniMusic build, as on Android. */
    const val ANIMUSIC_CREDIT = "AniMusic by Anikesh Kumar · Based on BitChord by Kushagra Singh · GPLv3"

    const val UPSTREAM_URL = "https://github.com/kushagrasinghx/BitChord"
    const val UPSTREAM_AUTHOR_URL = "https://github.com/kushagrasinghx"
    const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    /**
     * Strings keys whose text is about upstream BitChord itself and stays true as written
     * (the same exception the Android overlay makes).
     */
    private val KEEP_AS_WRITTEN = setOf("listen_together_official_server_dialog_message")

    private val LOWERCASE_NAME = Regex("""(?<![A-Za-z/.])bitchord(?![A-Za-z:/._])""")

    /**
     * [text] with the app's name in it swapped for this build's: "BitChord" and the
     * lower-case "bitchord" some catalogue strings use. Links (`bitchord://`) and
     * dotted ids are left alone.
     */
    fun rename(text: String, key: String? = null): String {
        if (!isAniMusic || (key != null && key in KEEP_AS_WRITTEN)) return text
        if (!text.contains("itchord", ignoreCase = true)) return text
        return LOWERCASE_NAME.replace(text.replace("BitChord", name), name)
    }
}
