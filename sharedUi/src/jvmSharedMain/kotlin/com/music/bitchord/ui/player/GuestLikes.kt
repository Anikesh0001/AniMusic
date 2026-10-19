package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the player's heart is shown to somebody not signed in to YouTube
 * Music, because the host keeps their likes on the device instead. Off by
 * default; a CompositionLocal rather than a parameter so the player screens'
 * signatures stay as they are (see the note on NowPlayingScreen in CLAUDE.md).
 */
val LocalGuestLikes = staticCompositionLocalOf { false }
