package com.music.bitchord.data.importer

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Link parsing for the importers.
 *
 * OkHttp's [HttpUrl] rather than `android.net.Uri`: every `canHandle()` is
 * pure string work, and the unit tests run against the Android stub jar where
 * `Uri` answers null to everything.
 */
object ImportUrls {

    private val URL_IN_TEXT = Regex("""(https?://\S+|spotify:[a-z]+:[A-Za-z0-9]+)""")

    /**
     * The first link in [text], with trailing sentence punctuation taken off.
     * Share sheets wrap links in prose ("Check out this playlist! https://…").
     */
    fun firstUrl(text: String): String? =
        URL_IN_TEXT.find(text.trim())?.value?.trimEnd('.', ',', ')', ']', '!', '?', '"', '\'')

    fun parse(url: String): HttpUrl? = url.trim().toHttpUrlOrNull()

    /** Lower-cased host with `www.` and `m.` taken off. */
    fun host(url: String): String? =
        parse(url)?.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")

    fun hostIs(url: String, vararg domains: String): Boolean {
        val host = host(url) ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    /** Path segments without empty ones (a trailing slash leaves an empty one). */
    fun segments(url: String): List<String> =
        parse(url)?.pathSegments.orEmpty().filter { it.isNotEmpty() }

    /** Segments with a leading two-letter or `xx-yy` locale segment removed (`/en/`, `/intl-de/`, `/us/`). */
    fun segmentsWithoutLocale(url: String): List<String> {
        val all = segments(url)
        val first = all.firstOrNull() ?: return all
        return if (LOCALE.matches(first)) all.drop(1) else all
    }

    private val LOCALE = Regex("""(intl-)?[a-z]{2}([-_][a-zA-Z]{2,4})?""")
}
