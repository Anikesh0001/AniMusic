#!/usr/bin/env bash
#
# Wraps the app image jpackage produced into an AppImage, the one Linux format
# Gradle cannot make itself.
#
# Usage: desktopApp/packaging/appimage.sh <app-image-dir> <out.AppImage> [appimagetool] [name] [id] [icon.png]
# name/id/icon are the brand: BitChord's by default; AniMusic's build passes
#   AniMusic animusic desktopApp/packaging/icons/animusic/AppIcon.png
set -euo pipefail

USAGE="usage: appimage.sh <app-image-dir> <out.AppImage> [appimagetool] [name] [id] [icon.png]"
APP_DIR_SRC=${1:?$USAGE}
OUT=${2:?$USAGE}
TOOL=${3:-appimagetool}
NAME=${4:-BitChord}
ID=${5:-bitchord}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ICON=${6:-$ROOT/desktopApp/packaging/icons/AppIcon.png}

[ -x "$APP_DIR_SRC/bin/$NAME" ] || { echo "no launcher in $APP_DIR_SRC" >&2; exit 1; }
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
APPDIR="$STAGE/$NAME.AppDir"

mkdir -p "$APPDIR/usr"
cp -a "$APP_DIR_SRC/." "$APPDIR/usr/"

# The mount point differs every run, so nothing may be hard-coded.
cat > "$APPDIR/AppRun" <<APPRUN
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/usr/bin/$NAME" "\$@"
APPRUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/$ID.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$NAME
GenericName=Music Player
Comment=YouTube Music player
Exec=$NAME
Icon=$ID
Categories=AudioVideo;Audio;Player;
Terminal=false
StartupWMClass=com-music-bitchord-desktop-MainKt
DESKTOP

# appimagetool reads the icon by the desktop entry; the desktop reads .DirIcon.
cp "$ICON" "$APPDIR/$ID.png"
cp "$ICON" "$APPDIR/.DirIcon"

mkdir -p "$(dirname "$OUT")"
# APPIMAGE_EXTRACT_AND_RUN rather than the flag of the same name: it is read by the AppImage
# runtime, so it covers a CI runner with no FUSE, and a natively built appimagetool — which has
# no such flag and would refuse to start — simply ignores it.
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$APPDIR" "$OUT"
echo "appimage: $OUT"
