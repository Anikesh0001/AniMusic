# AniMusic

AniMusic is a rebranded build of [BitChord](https://github.com/kushagrasinghx/BitChord) by Anikesh Kumar. It is GPLv3, like BitChord.

It is the `animusic` product flavor (dimension `env`), next to `dev` and `prod`:

| | dev | prod | animusic |
|---|---|---|---|
| applicationId | `com.dev.bitchord` | `com.music.bitchord` | `com.anikesh.animusic` |
| app name | BitChord Dev | BitChord | AniMusic |
| version | 1.8 (26) | 1.8 (26) | 1.0.0 (1) |

Kotlin packages and the namespace stay `com.music.bitchord`, and `src/main` keeps BitChord's text and art. Everything AniMusic changes is either flavor-scoped or switched by a `BuildConfig` field, so upstream merges stay mechanical.

## Publishing an update

Ship every update in two steps: a pre-release that only you install, then a promotion that sends it to everyone.

**1. Publish a pre-release**

```
scripts/release-animusic.sh --prerelease 1.0.3 "What changed (shown to users as the update notes)"
```

The script bumps the animusic `versionName`/`versionCode`, builds and verifies the signed release, commits, and tags `v1.0.3`. It pushes `main` plus the tag to the `animusic` remote, then creates GitHub release `v1.0.3` marked **pre-release**, with `AniMusic-1.0.3-arm64-v8a.apk` and `-universal.apk` attached.

Installed apps don't see it. They ask `GET /releases/latest`, which only ever returns "the most recent non-prerelease, non-draft release", so v1.0.2 stays the latest. On a pre-release the script also sends `make_latest: false`.

**2. Install it on your phone and smoke-test it**

Download the arm64 APK from the pre-release's page on GitHub and install it over your current AniMusic. Then run through the checklist in [CLAUDE.md](CLAUDE.md#smoke-test-before-every-release).

**3. Promote it**

```
scripts/promote-release.sh 1.0.3
```

This marks `v1.0.3` as a full release and the repository's latest, then checks that `/releases/latest` really returns it. From the next start, every installed copy shows the update dialog with the release notes. The script refuses if an APK is missing, if the release isn't a pre-release, or if the version isn't newer than the current latest.

If the smoke test fails, don't promote. Fix the problem and publish `1.0.4` as a new pre-release; version numbers are never reused. Delete the broken pre-release on GitHub if you like, since no installed app ever saw it.

Without `--prerelease`, `release-animusic.sh` publishes straight to everyone. Keep that for emergencies only.

Every release must be signed with the same keystore, or Android refuses to install it over the existing app. The full update contract is in [CLAUDE.md](CLAUDE.md).

The GitHub token comes from `$GITHUB_TOKEN`, or from `~/.config/animusic/git-credentials` (an owner-only file, used only for this checkout's `github.com/Anikesh0001` remotes). Both scripts read it through `scripts/lib/github-token.sh`. It is never stored in the repo.

## Build

```
./gradlew assembleAnimusicRelease   # R8, signed with the key in keystore.properties
```

Output: `app/build/outputs/apk/animusic/release/app-animusic-{arm64-v8a,armeabi-v7a,x86_64,universal}-release.apk`.

On a 16 GB machine, R8 needs the lower-memory settings in a local `gradle.properties` (`-Xmx2g`, `org.gradle.workers.max=2`, `kotlin.daemon.jvm.options=-Xmx1g`).

## What the flavor changes

- **Strings:** `src/animusic/res/values*/strings.xml` (all 16 locales) overrides only the strings that name the app, with BitChord replaced by AniMusic. These files are generated from `src/main/res`, so regenerate them after an upstream merge that touches those strings.
- **Text built in code:** `BuildConfig.BRAND_NAME`. This covers the Replay poster and story, the lyrics share card, the `Music/<brand>` download folder and `Pictures/<brand>`, the suggested backup file name, and backup/addon error messages. The shared Home header and Replay card read `LocalAppBrandName` / `LocalAppLogo`, which `MainActivity` provides.
- **Icon:** `src/animusic/res`. This holds the adaptive launcher icon (with a monochrome layer for themed icons; the Android 12+ splash uses it too), legacy webp renders, `ic_notification_logo`, and the in-app mark `ic_logo`. The master art is `src/animusic/art/animusic-icon.svg`.
- **Credits:** the settings colophon reads "AniMusic by Anikesh Kumar · Based on BitChord by Kushagra Singh · GPLv3", with links. `BuildConfig.SOURCE_URL` is the "Source code" link. It points at upstream for now; change it to this fork once its source is published (GPLv3 §6 asks that the modified source be offered).
- **Updates:** `BuildConfig.UPDATE_REPO = "Anikesh0001/AniMusic"`. On every app start, the in-app updater reads that repo's latest GitHub release. If its tag (e.g. `v1.0.2`) is newer than the installed version, it shows an update dialog that downloads the APK matching the phone (arm64-v8a, armeabi-v7a, or universal) and hands it to the installer. It never looks at BitChord's releases.
- **Permissions:** `src/animusic/AndroidManifest.xml` removes `READ_PHONE_STATE`, which the merger implies from `:shared` and nothing uses.

## Deliberately still "BitChord"

- **Code identifiers:** Kotlin packages, the namespace, log tags, intent action strings (`com.music.bitchord.*`), the internal `bitchord://source` / `bitchord://watch` URIs, and the `BitChordSpotifyTokenBridge` JS bridge name. None of these is user-visible, and renaming them would break upstream merges.
- **Network identifiers:** the User-Agents (`BitChord/<version>` for addons and artist facts, `BitChord (github…)` for Last.fm) and ListenBrainz's `submission_client`. These are identifiers servers see, kept as instructed.
- **File tags:** the FLAC vendor string `BitChord` and the MP4 freeform namespace `com.music.bitchord`. Existing downloads and other players read these tags.
- **Backups:** the internal backup tag `"app": "bitchord"`, so a BitChord backup imports into AniMusic and the other way round. Import never looked at the file name.
- **Listen Together:** the official party server, `https://bitchord.kushagrasingh.in/invite/…` invite links, the `bitchord://party` scheme, and the "official BitChord server" dialog. These are upstream's service and domain; the dialog is literally about that server.
- **Discord Rich Presence:** it uses upstream's Discord application ID (`DiscordRPC.APPLICATION_ID`) and the "Visit BitChord" button linking the upstream repo.
- **Credit text:** "Based on BitChord" in the colophon, and `LICENSE` / copyright headers. Required.

## Signing key

`keystore.properties` and `animusic-release.jks` in the project root are gitignored (`keystore.properties`, `*.jks`), and a copy is in `~/animusic-keys/`. The key is RSA 4096, alias `animusic`, `CN=Anikesh Kumar, L=Mysore, C=IN`, valid until 2054.

**Losing this keystore or its password means no future AniMusic build can install over an existing one.** Users would have to uninstall and lose their data. Keep at least two offline backups.

Because the same `keystore.properties` mechanism serves every flavor, `prodRelease` and `devRelease` builds on this machine are also signed with this key.

Release APKs are signed with APK Signature Scheme **v2 + v3** (`enableV3Signing`; v1 is off because minSdk is 26). Check with:

```
apksigner verify --verbose --print-certs app/build/outputs/apk/animusic/release/app-animusic-arm64-v8a-release.apk
```

## Permissions (animusic release, merged manifest)

| Permission | Why |
|---|---|
| `INTERNET` | Streaming, search, lyrics, Listen Together, imports. |
| `ACCESS_NETWORK_STATE` | Wi-Fi vs mobile-data quality rules; reconnecting Listen Together. |
| `WAKE_LOCK` | Keeps the CPU awake while music plays with the screen off. |
| `FOREGROUND_SERVICE` | Playback and downloads run as foreground services. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The type Android 14+ requires for the playback service. |
| `FOREGROUND_SERVICE_DATA_SYNC` | The type for the download / WebDAV upload service. |
| `POST_NOTIFICATIONS` | The media notification and download progress (Android 13+ asks). |
| `VIBRATE` | Haptic feedback; the system haptics switch still applies. |
| `BLUETOOTH_CONNECT` | Asked only when the output picker opens, to name paired headsets (Android 12+). |
| `READ_MEDIA_AUDIO` | Playing local music files (Android 13+). |
| `READ_EXTERNAL_STORAGE` (≤ API 32) | The same, before Android 13. |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | Saving downloads to Music/ on Android 9 and older. |
| `REQUEST_INSTALL_PACKAGES` | The in-app updater hands AniMusic's own release APK to the system installer. You still approve "install unknown apps" once. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx-core's own signature permission that protects the app's non-exported receivers. |

Removed in this flavor: `READ_PHONE_STATE` (implied by the merger from `:shared`, unused).

## GitHub repository

`github.com/Anikesh0001/AniMusic`, remote `animusic` in this checkout (`origin` is still upstream BitChord).

The upstream workflows in `.github/workflows/` (CI builds, desktop `release.yml`, contributors bots, backend deploy) are **disabled in the repo's Actions settings**. The files stay, so upstream merges are clean. They would otherwise rebuild BitChord on every push and attach BitChord desktop installers to AniMusic's releases. Re-enable any of them under Actions → the workflow → "Enable workflow".
