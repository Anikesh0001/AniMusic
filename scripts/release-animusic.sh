#!/usr/bin/env bash
# Publish an AniMusic update that installed copies will offer to install.
#
#   scripts/release-animusic.sh <version> ["release notes"]
#   e.g. scripts/release-animusic.sh 1.0.2 "Fixes YouTube playlist import"
#
# What it does:
#   1. sets the animusic flavor's versionName to <version> and bumps versionCode
#   2. builds assembleAnimusicRelease (R8, signed with keystore.properties)
#   3. verifies the signatures (v2 + v3, same certificate as before)
#   4. commits "release: AniMusic <version>", tags v<version>
#   5. pushes the branch and tag to the `animusic` remote
#   6. creates the GitHub release v<version> on Anikesh0001/AniMusic and
#      uploads AniMusic-<version>-arm64-v8a.apk and -universal.apk
#
# The app's update checker (BuildConfig.UPDATE_REPO) reads that repo's latest
# release, compares its tag with the installed versionName, and offers the APK
# that matches the phone. Android only installs it over the old one if it is
# signed with the same key, so never sign a release with any other keystore.
#
# Token: $GITHUB_TOKEN, or the one stored for this repo in
# ~/.config/animusic/git-credentials. It is never written into the repo.
set -euo pipefail

VERSION="${1:?usage: $0 <version> [\"release notes\"]}"
NOTES="${2:-AniMusic $VERSION}"
REPO="Anikesh0001/AniMusic"
REMOTE="animusic"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
cd "$ROOT"

[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || { echo "version must look like 1.0.2" >&2; exit 1; }
[[ -f keystore.properties ]] || { echo "keystore.properties is missing: releases must be signed with the AniMusic key" >&2; exit 1; }
if git rev-parse "v$VERSION" >/dev/null 2>&1; then echo "tag v$VERSION already exists" >&2; exit 1; fi
# Tracked changes other than the local gradle.properties tuning would end up in the release commit.
if git status --porcelain --untracked-files=no | grep -v ' gradle.properties$' | grep -q .; then
    echo "commit or stash your changes first" >&2; exit 1
fi

TOKEN="${GITHUB_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$HOME/.config/animusic/git-credentials" ]]; then
    TOKEN="$(sed -E 's#https://[^:]+:([^@]+)@.*#\1#' "$HOME/.config/animusic/git-credentials" | head -1)"
fi
[[ -n "$TOKEN" ]] || { echo "no GitHub token: set GITHUB_TOKEN" >&2; exit 1; }

# 1. Version bump, inside the animusic flavor block only.
python3 - "$GRADLE_FILE" "$VERSION" <<'PY'
import re, sys
path, version = sys.argv[1], sys.argv[2]
s = open(path, encoding="utf-8").read()
start = s.index('create("animusic")')
end = s.index("\n        }", start)
block = s[start:end]
code = int(re.search(r"versionCode = (\d+)", block).group(1)) + 1
block = re.sub(r"versionCode = \d+", f"versionCode = {code}", block)
block = re.sub(r'versionName = "[^"]*"', f'versionName = "{version}"', block)
open(path, "w", encoding="utf-8").write(s[:start] + block + s[end:])
print(f"animusic -> versionName {version}, versionCode {code}")
PY

# 2. Build. This machine's JVM needs IPv4, and :desktopApp needs a JDK 21 toolchain.
JDK21="${JDK21:-$HOME/.jdks/jdk-21.0.12.1+1}"
JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true ${JAVA_TOOL_OPTIONS:-}" \
    ./gradlew --console=plain -Porg.gradle.java.installations.paths="$JDK21" assembleAnimusicRelease

# 3. Verify.
OUT="app/build/outputs/apk/animusic/release"
APKSIGNER="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)/apksigner"
mkdir -p dist
for abi in arm64-v8a universal; do
    src="$OUT/app-animusic-$abi-release.apk"
    dst="dist/AniMusic-$VERSION-$abi.apk"
    "$APKSIGNER" verify --verbose "$src" | grep -q "v3 scheme (APK Signature Scheme v3): true" \
        || { echo "$src is not v3-signed" >&2; exit 1; }
    cp "$src" "$dst"
    echo "$dst  sha256 $(sha256sum "$dst" | cut -d' ' -f1)"
done
"$APKSIGNER" verify --print-certs "dist/AniMusic-$VERSION-arm64-v8a.apk" | grep "certificate SHA-256"

# 4–5. Commit, tag, push.
git add "$GRADLE_FILE"
git commit -q -m "release: AniMusic $VERSION"
git tag -a "v$VERSION" -m "AniMusic $VERSION"
git push "$REMOTE" "HEAD:main" "v$VERSION"

# 6. GitHub release with the APKs.
API="https://api.github.com/repos/$REPO"
BODY="$(python3 -c 'import json,sys; print(json.dumps({"tag_name": "v"+sys.argv[1], "name": "AniMusic "+sys.argv[1], "body": sys.argv[2], "draft": False, "prerelease": False}))' "$VERSION" "$NOTES")"
RELEASE="$(curl -4 -fsS -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$API/releases" -d "$BODY")"
UPLOAD="$(printf '%s' "$RELEASE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["upload_url"].split("{")[0])')"
for abi in arm64-v8a universal; do
    f="dist/AniMusic-$VERSION-$abi.apk"
    curl -4 -fsS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary @"$f" "$UPLOAD?name=$(basename "$f")" >/dev/null
    echo "uploaded $(basename "$f")"
done
echo "Published https://github.com/$REPO/releases/tag/v$VERSION"
