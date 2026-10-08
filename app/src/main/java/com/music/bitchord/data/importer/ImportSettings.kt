package com.music.bitchord.data.importer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The importer's own preferences, kept apart from [AppSettings][com.music.bitchord.data.settings.AppSettings]. */
object ImportSettings {
    private const val PREF_NAME = "bitchord_import"
    private const val KEY_CLIPBOARD = "clipboard_detection"
    private const val KEY_AUTO_SYNC = "auto_sync_daily"
    private const val KEY_LAST_AUTO_SYNC = "last_auto_sync"

    private var prefs: SharedPreferences? = null

    private val _clipboardDetection = MutableStateFlow(true)
    /** Offer to import a music link found on the clipboard when the app comes back. */
    val clipboardDetection: StateFlow<Boolean> = _clipboardDetection.asStateFlow()

    private val _autoSyncDaily = MutableStateFlow(false)
    /** Re-read imported playlists from their source on app open, at most once a day. */
    val autoSyncDaily: StateFlow<Boolean> = _autoSyncDaily.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        _clipboardDetection.value = p.getBoolean(KEY_CLIPBOARD, true)
        _autoSyncDaily.value = p.getBoolean(KEY_AUTO_SYNC, false)
    }

    fun setClipboardDetection(value: Boolean) {
        _clipboardDetection.value = value
        prefs?.edit()?.putBoolean(KEY_CLIPBOARD, value)?.apply()
    }

    fun setAutoSyncDaily(value: Boolean) {
        _autoSyncDaily.value = value
        prefs?.edit()?.putBoolean(KEY_AUTO_SYNC, value)?.apply()
    }

    var lastAutoSync: Long
        get() = prefs?.getLong(KEY_LAST_AUTO_SYNC, 0L) ?: 0L
        set(value) {
            prefs?.edit()?.putLong(KEY_LAST_AUTO_SYNC, value)?.apply()
        }
}
