package com.music.bitchord.data.spotify

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song

/**
 * The heart for listeners who are not signed in to YouTube Music.
 *
 * Liked songs are kept as one more on-device playlist ([ID]), so they show up
 * under "On device" in the Library, play like any other playlist, and travel
 * with an AniMusic account's sync without any of that needing to know about
 * them. Signed in, YouTube's own Liked Music is used instead and this is idle.
 */
object LocalLikes {
    const val ID = "liked_local"

    private fun songs(): List<Song> = LocalPlaylistStore.getPlaylist(ID)?.songs.orEmpty()

    fun isLiked(videoId: String): Boolean = songs().any { it.videoId == videoId }

    /** Puts every locally liked song into [LikeState], so hearts read as filled. */
    fun seed() {
        LikeState.seedLiked(songs().mapTo(HashSet()) { it.videoId })
    }

    /** Likes or un-likes [song] here on the device; [title] names the playlist when it is first made. */
    fun set(song: Song, liked: Boolean, title: String) {
        val current = songs()
        val updated = if (liked) {
            if (current.any { it.videoId == song.videoId }) return
            listOf(song) + current
        } else {
            current.filterNot { it.videoId == song.videoId }
        }
        val existing = LocalPlaylistStore.getPlaylist(ID)
        LocalPlaylistStore.upsert(ID, existing?.title ?: title, updated)
        LikeState.set(song.videoId, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
    }
}
