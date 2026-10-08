package com.music.bitchord.data.importer

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException

/** The shared HTTP plumbing every importer reads its source through. */
internal object ImportHttp {

    /** The same desktop Chrome the Spotify importer has always presented. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * The body of [url], or an [ImportException] naming why not. Network and
     * HTTP failures become [ImportException.Reason.NETWORK] (or
     * [ImportException.Reason.RATE_LIMITED] / [ImportException.Reason.NOT_FOUND])
     * so the dialog never shows an OkHttp message.
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                Http.client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    when {
                        response.code == 429 -> throw ImportException(ImportException.Reason.RATE_LIMITED)
                        response.code == 404 || response.code == 410 ->
                            throw ImportException(ImportException.Reason.NOT_FOUND)
                        !response.isSuccessful || body.isBlank() ->
                            throw ImportException(ImportException.Reason.NETWORK)
                    }
                    body
                }
            } catch (e: IOException) {
                throw ImportException(ImportException.Reason.NETWORK, e)
            }
        }

    /**
     * Where [url] ends up after redirects. OkHttp follows them itself; this
     * only reads the final request's URL. A GET, not a HEAD — several share
     * shorteners (Firebase dynamic links among them) answer HEAD with 405.
     */
    suspend fun finalUrl(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            Http.client.newCall(request).execute().use { it.request.url.toString() }
        } catch (e: IOException) {
            throw ImportException(ImportException.Reason.NETWORK, e)
        }
    }
}
