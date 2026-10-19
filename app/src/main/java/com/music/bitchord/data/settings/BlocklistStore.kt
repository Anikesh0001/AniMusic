package com.music.bitchord.data.settings

import android.content.Context
import com.music.bitchord.data.Blocklist
import org.json.JSONObject

/** Keeps [Blocklist] in its own preferences file across launches. */
object BlocklistStore {
    private const val PREFS = "bitchord_blocklist"
    private const val KEY = "entries"

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { raw -> runCatching { Blocklist.load(decode(raw)) } }
        Blocklist.onChange = { entries -> prefs.edit().putString(KEY, encode(entries)).apply() }
    }

    internal fun encode(e: Blocklist.Entries): String = JSONObject()
        .put("songs", JSONObject(e.songs))
        .put("artists", JSONObject(e.artists))
        .toString()

    internal fun decode(raw: String): Blocklist.Entries {
        val o = JSONObject(raw)
        fun map(key: String): Map<String, String> {
            val m = o.optJSONObject(key) ?: return emptyMap()
            return m.keys().asSequence().associateWith { m.optString(it) }
        }
        return Blocklist.Entries(songs = map("songs"), artists = map("artists"))
    }
}
