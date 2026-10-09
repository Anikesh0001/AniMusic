# AniMusic: notes for Claude Code

AniMusic is the `animusic` product flavor of this codebase (a fork of BitChord). How it is built, signed and released is in [ANIMUSIC.md](ANIMUSIC.md). Read it before touching anything release-related.

## The update contract (never break this)

Every installed copy finds and installs updates through `AppUpdateChecker`. If any of the following changes, phones in the wild stop updating or refuse the update. Changing one is a migration that needs a plan, not an edit.

- **Repository:** `BuildConfig.UPDATE_REPO = "Anikesh0001/AniMusic"`. The app reads `GET /repos/Anikesh0001/AniMusic/releases/latest`, which ignores drafts and pre-releases.
- **Tags:** `vX.Y.Z`, always increasing. The app compares the tag (without the `v`) with its own `versionName` numerically. A tag that isn't newer is never offered. `versionCode` must also increase every release, or Android refuses the install.
- **Assets:** `AniMusic-<version>-<abi>.apk` (at least `arm64-v8a`) plus `AniMusic-<version>-universal.apk`. The app picks the asset whose name contains the phone's ABI, then `universal`.
- **Keystore:** the same `animusic-release.jks` (alias `animusic`) for every release. A differently signed APK cannot install over the existing app. The keystore lives outside git (see ANIMUSIC.md); never regenerate it.
- **Package:** `applicationId = "com.anikesh.animusic"`. Changing it makes a different app.
- **Release notes:** the GitHub release *body* is the release notes. `AppUpdateChecker` reads `release["body"]` and `UpdateAvailableDialog` renders it as Markdown. Write it for users.

Release with `scripts/release-animusic.sh` (pre-release first, then `scripts/promote-release.sh`; see ANIMUSIC.md). It enforces most of the contract.

## Working rules

- **Flavor-scoped changes:** AniMusic-only changes go in `app/src/animusic/`, in the `animusic { }` flavor block of `app/build.gradle.kts`, or behind a `BuildConfig` field whose default keeps dev/prod unchanged. Keep `src/main` close to upstream BitChord.
- **No secrets in git:** never commit `keystore.properties`, `*.jks` / `*.keystore`, `local.properties`, GitHub tokens, or Worker secrets. They are gitignored; keep it that way, and never put a token in a remote URL.
- **`NowPlayingScreen`:** don't add parameters to `sharedUi/.../player/NowPlayingScreen.kt`. It has caused a D8 VerifyError before.
- **Before any release, all of these must pass:**
  ```
  ./gradlew assembleAnimusicDebug
  ./gradlew testDevDebugUnitTest
  ./gradlew assembleAnimusicRelease
  ```
  On this machine Gradle needs `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` and `-Porg.gradle.java.installations.paths=$HOME/.jdks/jdk-21.0.12.1+1`. R8 needs about 3 GB of heap.

## Smoke test before every release

Install the pre-release on a real phone (over the current release) and check:

- [ ] The app launches (no crash on first start after updating).
- [ ] Search for a song and play it.
- [ ] Background playback: lock the screen, and use the notification's play/pause/next.
- [ ] Import a Spotify link and a Deezer link (Library → Import playlist), and both save.
- [ ] Open a local (on-device) playlist.
- [ ] Downloads: download a song, and it plays offline.
- [ ] The update dialog appears when a newer release exists. (Before promoting, the pre-release itself must *not* show up as an update on a phone running the previous release.)

Only then promote the release.
