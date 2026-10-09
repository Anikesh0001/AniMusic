package com.music.bitchord.data.stats

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The anonymous active-user count: at most one tiny POST per UTC day, made in
 * the background when the app starts.
 *
 * It sends exactly `{"id", "appVersion", "androidSdk"}`. The id is a random
 * UUID made on first launch and kept in this class's own preferences file.
 * It is tied to nothing (no account, no device identifier), and on purpose
 * lives outside [AppSettings], so a restored backup cannot make two phones
 * share one. Nothing is sent when:
 *  - the build has no endpoint ([BuildConfig.STATS_URL] empty: dev, prod, and
 *    AniMusic built without `ANIMUSIC_STATS_URL`),
 *  - the listener turned "Send anonymous usage count" off, or
 *  - incognito listening is on.
 *
 * Sending never blocks anything and can never fail the app: it runs on its own
 * scope, times out after five seconds, and every error is swallowed.
 */
class UsagePing(
    private val store: Store,
    private val url: String,
    private val appVersion: String,
    private val androidSdk: Int,
    private val incognito: () -> Boolean,
    private val today: () -> String = { LocalDate.now(ZoneOffset.UTC).toString() },
    /** POSTs [body] to [url] and returns the HTTP status; throws on a network failure. */
    private val post: suspend (url: String, body: String) -> Int,
) {
    /** Where the id, the last ping's day and the on/off switch live. */
    interface Store {
        var installId: String?
        var lastPingDay: String?
        val enabled: Boolean
    }

    enum class Outcome { SENT, ALREADY_TODAY, OFF, INCOGNITO, NO_ENDPOINT, FAILED }

    /** Whether today's ping is still owed. */
    fun isDue(): Boolean = when {
        url.isBlank() -> false
        !store.enabled -> false
        incognito() -> false
        else -> store.lastPingDay != today()
    }

    /** Sends today's ping if one is owed. Never throws (except cancellation). */
    suspend fun pingIfDue(): Outcome {
        if (url.isBlank()) return Outcome.NO_ENDPOINT
        if (!store.enabled) return Outcome.OFF
        if (incognito()) return Outcome.INCOGNITO
        val day = today()
        if (store.lastPingDay == day) return Outcome.ALREADY_TODAY
        return try {
            post(url, body(installId()))
            // Any HTTP answer settles today, even an error: the server heard
            // from us, and retrying on every start would not help. Only a
            // network failure (below) leaves the day open for the next start.
            store.lastPingDay = day
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Outcome.FAILED
        }
    }

    /** The install's random id, made the first time it is needed. */
    fun installId(): String =
        store.installId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also { store.installId = it }

    internal fun body(id: String): String =
        """{"id":"$id","appVersion":"${appVersion.filter { it.isLetterOrDigit() || it in ".+_-" }.take(20)}","androidSdk":$androidSdk}"""

    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private val JSON = "application/json".toMediaType()

        /**
         * Fire-and-forget from [BitChordApplication.onCreate][com.music.bitchord.BitChordApplication],
         * after [AppSettings.init]. Returns at once.
         */
        fun start(context: Context) {
            if (BuildConfig.STATS_URL.isBlank()) return
            UsageStats.init(context)
            val ping = UsagePing(
                store = UsageStats,
                url = BuildConfig.STATS_URL,
                appVersion = BuildConfig.VERSION_NAME,
                androidSdk = Build.VERSION.SDK_INT,
                incognito = { AppSettings.incognitoMode.value },
                post = ::httpPost,
            )
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { ping.pingIfDue() }
            }
        }

        private val client by lazy {
            Http.client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        private suspend fun httpPost(url: String, body: String): Int = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
            // An IOException (offline, timeout) propagates to pingIfDue, which swallows it.
            client.newCall(request).execute().use { it.code }
        }
    }
}

/** [UsagePing.Store] in its own preferences file, plus the switch Settings shows. */
object UsageStats : UsagePing.Store {
    private const val PREF_NAME = "animusic_usage"
    private const val KEY_ID = "install_id"
    private const val KEY_LAST_DAY = "last_ping_day"
    private const val KEY_ENABLED = "send_usage_count"

    private var prefs: SharedPreferences? = null
    private val _enabledFlow = MutableStateFlow(true)

    /** "Send anonymous usage count" (on unless turned off). */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    /** Whether this build can send the count at all; Settings hides the switch otherwise. */
    val available: Boolean get() = BuildConfig.STATS_URL.isNotBlank()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        _enabledFlow.value = p.getBoolean(KEY_ENABLED, true)
    }

    override var installId: String?
        get() = prefs?.getString(KEY_ID, null)
        set(value) {
            prefs?.edit()?.putString(KEY_ID, value)?.apply()
        }

    override var lastPingDay: String?
        get() = prefs?.getString(KEY_LAST_DAY, null)
        set(value) {
            prefs?.edit()?.putString(KEY_LAST_DAY, value)?.apply()
        }

    override val enabled: Boolean get() = _enabledFlow.value

    fun setEnabled(value: Boolean) {
        _enabledFlow.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }
}
