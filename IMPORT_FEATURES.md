# Universal playlist import

## How the import pipeline worked before this branch

1. Library → "Import Spotify" sets `showSpotifyImportDialog` in `MainActivity.kt`, which shows `SpotifyImportAlert`.
2. `SpotifyImporter.extractPlaylistId()` accepts `open.spotify.com/…/playlist/<22-char id>` or `spotify:playlist:<id>`; anything else is "not a Spotify playlist link".
3. `fetchPlaylistTracks()` GETs `open.spotify.com/embed/playlist/<id>` and reads `props.pageProps.state.data.entity` from `__NEXT_DATA__` (title plus `trackList[].title/subtitle`).
4. Past 100 tracks it pages through `api-partner.spotify.com/pathfinder` `fetchPlaylist` with the embed's anonymous `accessToken`.
5. `resolveToSongs()` searched YouTube Music (`SearchFilter.SONGS`) with concurrency 4 and took the **first** result, with no `TrackMatcher` check.
6. `onImported` calls `MainViewModel.createPlaylistWithVideoIds()`, which creates a YouTube Music playlist when signed in (first 50 ids, then the rest).
7. When signed out, or if that fails, `LocalPlaylistStore.savePlaylist()` stores it as JSON in SharedPreferences with browse id `local:playlist:sp_local_<ms>`.
8. Songs that matched nothing are listed in the dialog after import; nothing else remembers them.
9. Separately, the logged-in Spotify library (`SpotifyLibrary` plus `MainViewModel` around line 2658) matches row by row with `SpotifyImporter.matchTrack()`, which does use `TrackMatcher.best()`, and marks rows with `sp:` / `sp-miss:` ids.
10. Shared links reach `MusicLink.consume()`, which only understands YouTube and YT Music hosts; every other link is dropped.

## Environment notes

- JDK 21 is needed as a Gradle toolchain by `:desktopApp`, even for Android tasks. It was installed portably at `~/.jdks/jdk-21.0.12.1+1` and passed with `-Porg.gradle.java.installations.paths=…`.
- AGP 8.10.1 can't read the SDK's `platforms/android-37.0` (v4 package.xml), so a compat copy was made at `platforms/android-37`.
- On this network Gradle needs `-Djava.net.preferIPv4Stack=true` (via `JAVA_TOOL_OPTIONS`).
- AGP auto-installed NDK 27.0.12077973 and Platform 36.

## Pre-existing test failures

None. On unmodified `main` (2c599a6), `./gradlew testDevDebugUnitTest` ran 972 tests: 0 failures, 0 errors, 0 skipped.
