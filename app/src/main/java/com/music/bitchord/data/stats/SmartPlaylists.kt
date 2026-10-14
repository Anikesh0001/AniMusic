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

    /** The ids of playlists made from a user's own rule. */
    const val CUSTOM_PREFIX = "smart_custom_"
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

        // The user's own rules. One whose playlist was deleted is a rule they no
        // longer want, so it goes too, rather than staying behind unseen.
        var customs = CustomSmartRules.load(context)
        val before = customs.size
        customs = customs.filter { it.playlistId !in dismissed }
        if (customs.size != before) CustomSmartRules.save(context, customs)

        if (!enabled) {
            // Takes down the user's own playlists too, but keeps their rules, so switching
            // it back on brings them back.
            created.forEach { LocalPlaylistStore.deletePlaylist(it) }
            created.clear()
            dismissed.clear()
            save(prefs, created, dismissed)
            return@withContext
        }

        val month = YearMonth.now()
        val histories = histories(month)

        val wanted = SmartRules.BUILT_IN.map { Triple(PREFIX + it.key, title(context, it.key), it) } +
            customs.map { Triple(it.playlistId, it.name, it.rule) }
        for ((id, name, rule) in wanted) {
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
            LocalPlaylistStore.upsert(id, name, picked.map { it.toSong() })
            created += id
        }
        save(prefs, created, dismissed)
    }

    private suspend fun histories(month: YearMonth): List<TrackHistory> {
        TasteStore.awaitLoaded()
        return SmartRules.histories(ListeningStats.exportAll(), month) { TasteStore.entry(it)?.skips ?: 0 }
    }

    /**
     * Makes the user's own smart playlist from [rule], now, and keeps it.
     *
     * Returns how many songs it holds, or 0 — and keeps nothing — when the
     * rule matches no song, so the form can say so rather than make an empty
     * playlist.
     */
    suspend fun create(context: Context, name: String, rule: SmartRule): Int = withContext(Dispatchers.Default) {
        val month = YearMonth.now()
        val picked = SmartRules.evaluate(rule, histories(month), System.currentTimeMillis(), month)
        if (picked.isEmpty()) return@withContext 0

        val id = java.util.UUID.randomUUID().toString().take(8)
        val custom = CustomSmartRule(id, name.trim(), rule.copy(key = "custom_$id"))
        CustomSmartRules.save(context, CustomSmartRules.load(context) + custom)
        LocalPlaylistStore.upsert(custom.playlistId, custom.name, picked.map { it.toSong() })

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val created = prefs.getStringSet(KEY_CREATED, emptySet()).orEmpty() + custom.playlistId
        prefs.edit().putStringSet(KEY_CREATED, created).apply()
        picked.size
    }

    private fun save(prefs: SharedPreferences, created: Set<String>, dismissed: Set<String>) {
        prefs.edit().putStringSet(KEY_CREATED, created).putStringSet(KEY_DISMISSED, dismissed).apply()
    }
}
