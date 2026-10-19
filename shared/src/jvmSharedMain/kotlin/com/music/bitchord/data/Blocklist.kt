package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Songs and artists the listener asked never to be played to them unasked:
 * kept out of autoplay and radio ([com.music.bitchord.playback.QueueBuilder]),
 * the "Because you listened to" rows and the Daily Mixes. Playing one on
 * purpose (search, a playlist) still works; this is about recommendations.
 *
 * Shared so every recommender filters the same way. Empty until a host loads
 * it ([load]) and saves through [onChange]; the desktop does neither, so there
 * it blocks nothing.
 */
object Blocklist {

    data class Entries(
        /** videoId to the title shown in the list. */
        val songs: Map<String, String> = emptyMap(),
        /** Lower-cased artist name to the name as shown. */
        val artists: Map<String, String> = emptyMap(),
    )

    private val _entries = MutableStateFlow(Entries())
    val entries: StateFlow<Entries> = _entries.asStateFlow()

    /** Set by the host to persist every change. */
    var onChange: ((Entries) -> Unit)? = null

    fun load(entries: Entries) {
        _entries.value = entries
    }

    fun blockSong(song: Song) = update { it.copy(songs = it.songs + (song.videoId to song.title)) }

    fun blockArtist(name: String) {
        val key = artistKey(name)
        if (key.isNotEmpty()) update { it.copy(artists = it.artists + (key to name.trim())) }
    }

    fun unblockSong(videoId: String) = update { it.copy(songs = it.songs - videoId) }

    fun unblockArtist(key: String) = update { it.copy(artists = it.artists - key) }

    private fun update(change: (Entries) -> Entries) {
        val next = change(_entries.value)
        if (next == _entries.value) return
        _entries.value = next
        onChange?.invoke(next)
    }

    fun blocks(song: Song): Boolean = blocks(song.videoId, song.artist)

    fun blocks(videoId: String, artist: String): Boolean {
        val e = _entries.value
        if (videoId in e.songs) return true
        if (e.artists.isEmpty()) return false
        return artists(artist).any { it in e.artists }
    }

    /** The first-listed artist of a credit line such as "A, B & C", for the menu. */
    fun leadArtist(credit: String): String? = credit.split(SEPARATORS).map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private fun artists(credit: String): List<String> = credit.split(SEPARATORS).map(::artistKey).filter { it.isNotEmpty() }

    private fun artistKey(name: String) = name.trim().lowercase()

    private val SEPARATORS = Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?|\bx\b|\band\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
}
