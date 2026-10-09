#!/usr/bin/env bash
# Ship a smoke-tested AniMusic pre-release to everyone.
#
#   scripts/promote-release.sh <version>      e.g. scripts/promote-release.sh 1.0.3
#
# Marks release v<version> (made with release-animusic.sh --prerelease) as a
# full release and as the repository's latest. GET /releases/latest then
# returns it, so installed copies offer it as an update on their next start.
#
# Refuses to: promote a release that is missing an APK, promote one that isn't
# a pre-release, or promote a version not newer than the current latest (that
# would hide the newer one from GET /releases/latest).
set -euo pipefail

VERSION="${1:?usage: $0 <version>}"
REPO="Anikesh0001/AniMusic"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API="https://api.github.com/repos/$REPO"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || { echo "version must look like 1.0.3" >&2; exit 1; }
# shellcheck source=lib/github-token.sh
source "$ROOT/scripts/lib/github-token.sh"

gh_api() { curl -4 -fsS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

RELEASE="$(gh_api "$API/releases/tags/v$VERSION")" || { echo "no release tagged v$VERSION" >&2; exit 1; }
LATEST_TAG="$(gh_api "$API/releases/latest" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tag_name",""))' || true)"

ID="$(printf '%s' "$RELEASE" | python3 -c '
import json, sys
version, latest = sys.argv[1], sys.argv[2].lstrip("v")
r = json.load(sys.stdin)
names = {a["name"] for a in r["assets"] if a.get("state") == "uploaded"}
need = {f"AniMusic-{version}-arm64-v8a.apk", f"AniMusic-{version}-universal.apk"}
if not need <= names:
    sys.exit("missing assets: " + ", ".join(sorted(need - names)))
if not r["prerelease"]:
    sys.exit(f"v{version} is already a full release")
num = lambda v: [int(x) for x in v.split(".")]
if latest and num(version) <= num(latest):
    sys.exit(f"v{version} is not newer than the current latest v{latest}")
print(r["id"])
' "$VERSION" "$LATEST_TAG")"

gh_api -X PATCH "$API/releases/$ID" -d '{"prerelease": false, "make_latest": "true"}' >/dev/null

NOW="$(gh_api "$API/releases/latest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')"
[[ "$NOW" == "v$VERSION" ]] || { echo "promoted, but /releases/latest still says $NOW" >&2; exit 1; }
echo "v$VERSION is now the latest release: installed AniMusic apps will offer it on their next start."
echo "https://github.com/$REPO/releases/tag/v$VERSION"
