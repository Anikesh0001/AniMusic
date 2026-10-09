<div align="center">

<br/>

<img src="app/src/animusic/art/animusic-icon.png" alt="AniMusic app icon" width="180" />

# AniMusic

### A beautiful YouTube Music player for Android, by Anikesh Kumar

<br/>

[![Latest release](https://img.shields.io/github/v/release/Anikesh0001/AniMusic?style=for-the-badge&labelColor=0d1117)](https://github.com/Anikesh0001/AniMusic/releases/latest)
[![License](https://img.shields.io/github/license/Anikesh0001/AniMusic?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/Anikesh0001/AniMusic/total?style=for-the-badge&labelColor=0d1117)](https://github.com/Anikesh0001/AniMusic/releases)

[**Download**](#download) · [**Features**](#features) · [**Build**](#build-it-yourself) · [**Privacy**](#privacy) · [**Credits**](#credits) · [**Disclaimer**](#disclaimer)

</div>

> [!IMPORTANT]
> AniMusic is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

<h2 id="download">Download</h2>

Get the latest APK from **[Releases](https://github.com/Anikesh0001/AniMusic/releases/latest)**:

- **`AniMusic-<version>-arm64-v8a.apk`**: for almost every phone from the last few years.
- **`AniMusic-<version>-universal.apk`**: works on any device, if you're not sure.

Allow "Install unknown apps" for your browser or file manager when Android asks. After that, AniMusic tells you when a new version is out and installs it in a couple of taps.

AniMusic installs as its own app (`com.anikesh.animusic`) and needs Android 8.0 or newer.

---

<h2 id="features">Features</h2>

#### Import from any music app
- **Paste or share a link** from Spotify, Apple Music, Deezer, YouTube / YT Music, JioSaavn, SoundCloud, Tidal, Qobuz, Audiomack, Gaana, ListenBrainz or song.link. Playlists, albums and songs become a playlist in AniMusic.
- **Smart matching** checks every song by title, artist, version and length, and lets you **review** unsure matches before saving.
- **Import files**: CSV (Exportify, TuneMyMusic, Google Takeout), M3U/M3U8 or TXT. You can also **paste a list of songs**.
- **Sync from source** adds new songs from the original playlist, and there's an **import history**.
- **Copy a link** in another app and AniMusic offers to import it when you come back.
- **Export** any playlist as M3U, CSV or text.

#### Playback
- **Search, browse and play** anything available on YouTube Music.
- **Hi-Res lossless audio**: FLAC/ALAC from a configured module source, with YouTube Music as fallback.
- **Gapless playback with true crossfade**, adjustable from 0 to 12 s.
- **Automix (beta)**: DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** with embedded metadata, plus your **local music library**.
- **Background playback** through a proper media session, with home-screen widgets.

#### Connectivity and accounts
- **Sign in with Google** for your own library and recommendations.
- **Spotify integration**: play your Spotify playlists and Liked Songs.
- **Listen Together**: listen in sync with friends.
- **Discord Rich Presence**, plus **scrobbling** to Last.fm and ListenBrainz.
- **Pluggable sources**: add, test and health-check module sources.

#### Experience
- **Word-synced lyrics** from many providers, with Apple-style animation.
- **Animated album canvas** on the now-playing screen.
- **Dynamic theming** from the album art, with a frosted-glass Material 3 UI.
- **Per-network audio quality**, playback speed, skip silence, a sleep timer, the system equalizer and "stats for nerds".
- **16 languages.**

---

<h2 id="build-it-yourself">Build it yourself</h2>

You need JDK 17, the Android SDK (platform 37, NDK, CMake 3.22.1) and a JDK 21 for the Gradle toolchain.

```bash
./gradlew assembleAnimusicDebug          # a debug build
./gradlew assembleAnimusicRelease        # a release build, signed with keystore.properties
```

Maintainers publish a release with `scripts/release-animusic.sh <version> "notes"`. See [ANIMUSIC.md](ANIMUSIC.md) for how the app is put together, signing and the release flow.

---

<h2 id="privacy">Privacy</h2>

AniMusic has no account of its own and shows no ads.

**Anonymous usage count.** When the app starts, at most once a day, it sends this to AniMusic's counter (a Cloudflare Worker, [source in `cloudflare/worker`](cloudflare/worker)):

```json
{"id": "a random ID made on this phone", "appVersion": "1.0.3", "androidSdk": 34}
```

- **The ID** is a random UUID the app generates on first launch. It isn't your Google account, a phone identifier, or anything else that identifies you, and it isn't included in backups.
- **Nothing else is sent:** nothing about you, your library or what you play. The server stores one row per ID per day and deletes it after 90 days. It doesn't store IP addresses.
- **Why:** so the developer knows roughly how many people use AniMusic each day and month, and which versions are still in use.
- **How to turn it off:** Settings → Your data → **Send anonymous usage count**. It's also never sent while **Incognito listening** is on, or by builds made without a counter address.

**Other connections.** These are the services the app talks to so its features work:
- **Playback and search:** YouTube Music.
- **Lyrics:** the lyrics providers listed under Credits.
- **Imports:** the music service of whatever link you import.
- **Updates:** GitHub, to check this repository for new releases.
- **Optional, only if you turn them on:** Last.fm, ListenBrainz, Discord and Listen Together. Listen Together currently runs on BitChord's party server.

AniMusic also still contains BitChord's "apps open right now" ping. While the app is open, it sends a random install ID (separate from the one above) to BitChord's server, `api.bitchord.kushagrasingh.in`, every few minutes so BitChord can show a live user count. It carries nothing else, and there's currently no switch for it.

---

<h2 id="credits">Credits</h2>

AniMusic is built on **[BitChord](https://github.com/kushagrasinghx/BitChord)** by Kushagra Singh and its contributors, and is released under the same GPLv3 license.

Lyrics come from [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com). The lyrics animation is inspired by [binimum/am-lyrics](https://github.com/binimum/am-lyrics).

---

<h2 id="disclaimer">Disclaimer</h2>

AniMusic is an independent third-party music player. It is **not** associated with Google LLC, YouTube Music, Spotify, Apple, Deezer or any of their parent companies.

- **No media hosting:** AniMusic does not host, upload or store copyrighted music. It plays local files and streams directly from public or user-authenticated services.
- **Your responsibility:** you are responsible for using it in line with your local laws and the terms of the services you use.
- **Free software:** AniMusic is licensed under the [GNU GPL v3](LICENSE). Anyone may share or modify it, as long as they pass the source on under the same license.
