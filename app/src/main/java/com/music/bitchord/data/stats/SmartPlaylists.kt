package com.music.bitchord.data.stats

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.SmartRules.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * Keeps the built-in smart playlists ([SmartRules.BUILT_IN]) written into the
 * user's local playlists.
 *
 * ## Ordinary playlists, rewritten
 *
 * A smart playlist is saved as a normal local playlist under a fixed id, so it
 * shows in the Library, opens, plays, shuffles and downloads with no code of
 * its own. What makes it smart is that this rewrites its songs when the
 * history changes.
 *
 * ## A deleted one stays deleted
 *
 * The ids this has made are remembered. One that is gone from the playlists
 * the next time round was deleted by the user, so it is not made again. Turning
 * the setting off removes them all and forgets that, so turning it back on
 * brings every one back. A rename lasts only until the next rewrite.
 */
object SmartPlaylists {

    private const val PREFIX = "smart_"
    private const val PREFS = "smart_playlists"
    private const val KEY_CREATED = "created"
    private const val KEY_DISMISSED = "dismissed"

    /** Reading every month from disk is not free, and the answer moves slowly. */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    private var lastRun = 0L
    private var lastEnabled: Boolean? = null

    private fun title(context: Context, key: String): String = context.getString(
        when (key) {
            SmartRules.ON_REPEAT.key -> R.string.smart_pl_on_repeat
            SmartRules.NEW_FAVORITES.key -> R.string.smart_pl_new_favorites
            SmartRules.FAVORITES.key -> R.string.smart_pl_favorites
            else -> R.string.smart_pl_rediscover
        },
    )

    /** Brings the playlists in line with the history and the setting. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.Default) {
        val enabled = AppSettings.smartPlaylists.value
        val now = System.currentTimeMillis()
        if (enabled == lastEnabled && now - lastRun < MIN_INTERVAL_MS) return@withContext
        lastEnabled = enabled
        lastRun = now

        val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val created = prefs.getStringSet(KEY_CREATED, emptySet()).orEmpty().toMutableSet()
        val dismissed = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toMutableSet()
        val present = LocalPlaylistStore.playlists.value.mapTo(HashSet()) { it.id }

        // Made before, gone now: the user took it away.
        created.filter { it !in present }.forEach {
            dismissed += it
            created -= it
        }

        if (!enabled) {
            created.forEach { LocalPlaylistStore.deletePlaylist(it) }
            created.clear()
            dismissed.clear()
            save(prefs, created, dismissed)
            return@withContext
        }

        TasteStore.awaitLoaded()
        val buckets = ListeningStats.exportAll()
        val month = YearMonth.now()
        val histories = SmartRules.histories(buckets, month) { TasteStore.entry(it)?.skips ?: 0 }

        for (rule in SmartRules.BUILT_IN) {
            val id = PREFIX + rule.key
            if (id in dismissed) continue
            val picked = SmartRules.evaluate(rule, histories, now, month)
            if (picked.isEmpty()) {
                // No longer enough to fill it: take it down rather than show a stale list.
                if (id in created) {
                    LocalPlaylistStore.deletePlaylist(id)
                    created -= id
                }
                continue
            }
            LocalPlaylistStore.upsert(id, title(context, rule.key), picked.map { it.toSong() })
            created += id
        }
        save(prefs, created, dismissed)
    }

    private fun save(prefs: SharedPreferences, created: Set<String>, dismissed: Set<String>) {
        prefs.edit().putStringSet(KEY_CREATED, created).putStringSet(KEY_DISMISSED, dismissed).apply()
    }
}
