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

## Service log

| Service | Status | How | Example to test |
|---|---|---|---|
| Spotify playlists | working | embed `__NEXT_DATA__`, plus pathfinder paging past 100 (live: 150/150 tracks) | https://open.spotify.com/playlist/37i9dQZF1DX4o1oenSJRJd |
| Spotify albums | working | `/embed/album/<id>`, with album name and per-track duration | https://open.spotify.com/album/2noRn2Aes5aoNVsU6iWThc |
| Spotify tracks | working | `/embed/track/<id>` (single song) | https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT |
| Deezer playlists, albums, tracks | working | public `api.deezer.com` JSON, paging through `/tracks` `next`; ISRC, duration and album on every row. `deezer.page.link` / `link.deezer.com` short links are followed by redirect. Errors (HTTP 200 + `error.code`) map to not-found / rate-limited. | https://www.deezer.com/en/playlist/908622995 |
| Apple Music playlists, albums, songs | working | page `serialized-server-data` (`trackLockup` rows with artist, album, runtime; `songDetailHeader` for /song/), JSON-LD as fallback. `?i=<id>` on an album link imports just that song. (live: 70/70 on a large editorial playlist) | https://music.apple.com/us/album/discovery/697194953 |
| YouTube / YouTube Music links in the import box | working | Not scraped: `MusicLink.parse()` turns them into the existing `LinkRequest.Page` / `Track`, and the composition opens or plays them natively. | https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs |
| JioSaavn playlists, albums, songs | working | new `JioSaavnService.getByToken()` (`__call=webapi.get`, `token`, `type=playlist\|album\|song`, paged with `p`/`n`). HTML entities in titles are decoded. An unknown token answers `"list": ""`, which reads as "not found". Live API checked with curl, and the 20 real rows of "Dumdaar Hits" matched 20/20 confident. | https://www.jiosaavn.com/featured/dumdaar-hits/8MT-LQlP35c_ |
| SoundCloud sets and tracks | working | page `window.__sc_hydration`: the set with its first ~5 rows described and the rest as ids. The anonymous `client_id` is in the same hydration (`apiClient`), so the rest come from `api-v2.soundcloud.com/tracks?ids=` 50 at a time (live: 290/290). If that step fails, the import is **partial**: described rows import and the rest are reported as missing. `full_duration` beats `duration` (a 30 s preview on restricted tracks). "Artist w/ Guest - Title" uploads are split. | https://soundcloud.com/lofi-hip-hop-music/sets/lofi-lofi |
| Audiomack albums/playlists | working (generic) | JSON-LD `MusicAlbum` with every track, ISRC and duration (live: 18/18) | https://audiomack.com/eminem/album/the-marshall-mathers-lp |
| Qobuz albums | working (dedicated) | JSON-LD names the album but not the tracks, so the generic parser fails. The store page lists every row in `div.track` (name, `mm:ss`, a credits line marking `MainArtist`). Its `data-duration` attribute is *not* the runtime, and using it broke matching. | https://www.qobuz.com/us-en/album/discovery-daft-punk/0724384960650 |
| Gaana albums, songs | working (generic) | JSON-LD `MusicAlbum`/`MusicPlaylist`/`MusicRecording` (live: album 11/11, single song). | https://gaana.com/album/hanuman-ansh-original-motion-picture-soundtrack |
| Gaana playlists over 50 | partial | Both the JSON-LD and the page's `preloaded-state` stop at 50 rows. `numTracks` reports the full count, so the rest show as missing. The site's `apiv2` refuses plain requests (HTML for GET, 400 for POST). | https://gaana.com/playlist/gaana-dj-bollywood-top-50-1 |
| Wynk Music | skipped | 3 attempts: every playlist/package link redirects to `wynk.in/music`, and `content.wynk.in/music/v4/content` returns `{"total":0}` for every id, even from an Indian IP. Wynk Music has shut down, so there's nothing left to read. | https://wynk.in/music/playlist/feel-the-chill/bb_1449131194711 |
| Tidal tracks | working (generic) | Track pages carry a JSON-LD `MusicRecording` with ISRC and duration. | https://tidal.com/track/1550549 |
| Tidal albums | working (via song.link) | Album pages have no tracks, so the link goes to song.link, which lists the same album on Deezer; the Deezer importer reads it (live: 14/14), still labelled and synced as the Tidal link. Works for any album song.link has resolved on a service with an importer. | https://tidal.com/album/1550545 |
| Tidal playlists | skipped | No tracks in the page, and song.link doesn't cover playlists; the app API needs a client token. | https://tidal.com/playlist/abd35446-6a67-4f2b-9f92-0636742c63cb |
| Amazon Music | skipped | The page is an empty client-side shell (no JSON-LD, no og tags); the tracklist needs the app API. | https://music.amazon.com/albums/B00GN0NZNY |
| Anghami | skipped | `play.anghami.com` answers HTTP 406 to non-app clients, even with full browser headers. | https://play.anghami.com/playlist/44294315 |
| Boomplay | skipped | Behind a Cloudflare JS challenge (HTTP 403 "Just a moment..."). | https://www.boomplay.com/albums/1011541 |
| Hungama | skipped | Client-side app: no JSON-LD, empty og tags. | https://www.hungama.com/album/kabir-singh/49421224/ |
| Napster | skipped | The music service is gone; napster.com now redirects to an unrelated AI product. | n/a |
| Single songs from any app (Odesli) | working (song.link page) | `api.song.link/v1-alpha.1` now answers `401 PUBLIC_API_ACCESS_DEPRECATED` without a key, so the resolver reads the public `song.link/<url>` page's `__NEXT_DATA__` instead: title, artist, duration, other-platform links and a YouTube id when present (the id is played directly). Asked once, only for a link nothing else could read, never per playlist row; 429 shows "service is busy". `song.link`/`album.link` links themselves are handled too. | https://album.link/https://www.deezer.com/album/302127 |
| Any other page | generic fallback | JSON-LD `MusicPlaylist`/`MusicAlbum`/`MusicRecording` (incl. `@graph`, `ItemList`), then `og:type=music.song`, then `music:song` tags. Nothing found shows "This link isn't supported yet". | n/a |
