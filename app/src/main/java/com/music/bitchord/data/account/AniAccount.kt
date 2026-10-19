package com.music.bitchord.data.account

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.BuildConfig
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An AniMusic account: email + password on AniMusic's own server
 * ([BuildConfig.ACCOUNT_URL], the cloudflare/worker), with no verification
 * step. It is not a YouTube Music sign-in and gives no YouTube library; it is a
 * name for Listen Together and a place to keep this device's own data (see
 * [AccountSync]) so it follows the user to another phone or computer.
 *
 * Builds with an empty [BuildConfig.ACCOUNT_URL] (dev, prod) have no accounts:
 * [available] is false and nothing here touches the network.
 */
object AniAccount {

    data class Account(val id: String, val email: String, val name: String)

    /** What went wrong, for the screen to say in the user's language. */
    enum class Failure { EMAIL_TAKEN, INVALID_EMAIL, PASSWORD_TOO_SHORT, WRONG_EMAIL_OR_PASSWORD, TOO_MANY_ATTEMPTS, OFFLINE, SERVER }

    class AccountException(val failure: Failure) : Exception(failure.name)

    val available: Boolean get() = BuildConfig.ACCOUNT_URL.isNotBlank()

    private val _current = MutableStateFlow<Account?>(null)

    /** The signed-in account, or null. */
    val current: StateFlow<Account?> = _current.asStateFlow()

    private var prefs: SharedPreferences? = null

    internal val token: String? get() = prefs?.getString(KEY_TOKEN, null)

    fun init(context: Context) {
        if (!available || prefs != null) return
        val p = EncryptedPrefs.open(context, "animusic_account", "animusic_account_plain")
        prefs = p
        val id = p.getString(KEY_ID, null)
        val email = p.getString(KEY_EMAIL, null)
        if (id != null && email != null && p.getString(KEY_TOKEN, null) != null) {
            _current.value = Account(id, email, p.getString(KEY_NAME, null).orEmpty())
        }
    }

    suspend fun register(email: String, password: String, name: String): Account =
        signIn("/account/register", buildJsonObject {
            put("email", email.trim())
            put("password", password)
            put("name", name.trim())
        })

    suspend fun login(email: String, password: String): Account =
        signIn("/account/login", buildJsonObject {
            put("email", email.trim())
            put("password", password)
        })

    /** Signs this device out. The server forgets the session; data stays on both. */
    suspend fun logout() {
        val t = token
        clear()
        if (t != null) runCatching { request("POST", "/account/logout", null, t) }
    }

    /** Deletes the account, its sessions and its synced data on the server. */
    suspend fun deleteAccount(password: String) {
        val t = token ?: throw AccountException(Failure.WRONG_EMAIL_OR_PASSWORD)
        request("DELETE", "/account", buildJsonObject { put("password", password) }, t)
        clear()
    }

    private suspend fun signIn(path: String, body: JsonObject): Account {
        val response = request("POST", path, body, null)
        val token = response["token"]?.jsonPrimitive?.contentOrNull ?: throw AccountException(Failure.SERVER)
        val a = response["account"]?.jsonObject ?: throw AccountException(Failure.SERVER)
        val account = Account(
            id = a.string("id") ?: throw AccountException(Failure.SERVER),
            email = a.string("email").orEmpty(),
            name = a.string("name").orEmpty(),
        )
        // A different account on this device must not inherit the last one's sync point.
        val previous = prefs?.getString(KEY_ID, null)
        prefs?.edit()?.apply {
            putString(KEY_TOKEN, token)
            putString(KEY_ID, account.id)
            putString(KEY_EMAIL, account.email)
            putString(KEY_NAME, account.name)
            if (previous != account.id) remove(KEY_SYNCED_AT)
        }?.apply()
        _current.value = account
        return account
    }

    private fun clear() {
        prefs?.edit()?.clear()?.apply()
        _current.value = null
    }

    /** The server's version of the synced data this device last saw. */
    internal var syncedAt: Long
        get() = prefs?.getLong(KEY_SYNCED_AT, 0L) ?: 0L
        set(value) { prefs?.edit()?.putLong(KEY_SYNCED_AT, value)?.apply() }

    /** Called when the server no longer knows this device's session. */
    internal fun onSignedOutByServer() = clear()

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One call to the account server. Returns the JSON body (empty for 204),
     * throws [AccountException] for anything the user should be told about, and
     * [ConflictException] for a sync write another device beat.
     */
    internal suspend fun request(method: String, path: String, body: JsonElement?, token: String?): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(BuildConfig.ACCOUNT_URL + path)
            val payload = body?.toString()?.toRequestBody(JSON_TYPE)
            builder.method(method, payload ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON_TYPE))
            if (token != null) builder.header("Authorization", "Bearer $token")
            val (code, text) = try {
                client.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw AccountException(Failure.OFFLINE)
            }
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            when {
                code in 200..299 -> json
                code == 409 && json.string("error") == "conflict" ->
                    throw ConflictException(json["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L)
                code == 401 && token != null && path != "/account" -> {
                    onSignedOutByServer()
                    throw AccountException(Failure.WRONG_EMAIL_OR_PASSWORD)
                }
                else -> throw AccountException(
                    when (json.string("error")) {
                        "email_taken" -> Failure.EMAIL_TAKEN
                        "invalid_email" -> Failure.INVALID_EMAIL
                        "password_too_short" -> Failure.PASSWORD_TOO_SHORT
                        "wrong_email_or_password" -> Failure.WRONG_EMAIL_OR_PASSWORD
                        "too_many_attempts" -> Failure.TOO_MANY_ATTEMPTS
                        else -> Failure.SERVER
                    },
                )
            }
        }

    class ConflictException(val updatedAt: Long) : Exception("sync conflict")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private val JSON_TYPE = "application/json".toMediaType()
    private const val KEY_TOKEN = "token"
    private const val KEY_ID = "id"
    private const val KEY_EMAIL = "email"
    private const val KEY_NAME = "name"
    private const val KEY_SYNCED_AT = "synced_at"
}
