package com.music.bitchord.data.stats

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A smart playlist the user made: a name and the [SmartRule] it was built from. */
data class CustomSmartRule(val id: String, val name: String, val rule: SmartRule) {
    /** The id of the playlist it is written as. */
    val playlistId: String get() = SmartPlaylists.CUSTOM_PREFIX + id
}

/**
 * The user's own smart playlists' rules, kept in preferences as a small JSON list.
 * Only the rules are kept here; the playlists themselves live with the other local
 * playlists and are rewritten from these by [SmartPlaylists].
 */
object CustomSmartRules {

    private const val PREFS = "smart_playlists"
    private const val KEY = "custom"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<CustomSmartRule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return decode(raw)
    }

    fun save(context: Context, rules: List<CustomSmartRule>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(rules)).apply()
    }

    @Serializable
    private data class StoredCondition(val field: String, val min: Double? = null, val max: Double? = null)

    @Serializable
    private data class StoredRule(
        val id: String,
        val name: String,
        val sort: String,
        val desc: Boolean = true,
        val limit: Int = 50,
        val conditions: List<StoredCondition> = emptyList(),
    )

    internal fun encode(rules: List<CustomSmartRule>): String = json.encodeToString(
        ListSerializer(StoredRule.serializer()),
        rules.map { c ->
            StoredRule(
                id = c.id,
                name = c.name,
                sort = c.rule.sortBy.name,
                desc = c.rule.descending,
                limit = c.rule.limit,
                conditions = c.rule.conditions.map { StoredCondition(it.field.name, it.min, it.max) },
            )
        },
    )

    /** What [encode] wrote; a rule that cannot be read is left out rather than failing the rest. */
    internal fun decode(raw: String): List<CustomSmartRule> {
        val stored = runCatching { json.decodeFromString(ListSerializer(StoredRule.serializer()), raw) }
            .getOrDefault(emptyList())
        return stored.mapNotNull { r ->
            runCatching {
                CustomSmartRule(
                    id = r.id,
                    name = r.name,
                    rule = SmartRule(
                        key = "custom_${r.id}",
                        conditions = r.conditions.map { Condition(SmartField.valueOf(it.field), it.min, it.max) },
                        sortBy = SmartField.valueOf(r.sort),
                        descending = r.desc,
                        limit = r.limit,
                        minTracks = 1,
                    ),
                )
            }.getOrNull()
        }
    }
}
