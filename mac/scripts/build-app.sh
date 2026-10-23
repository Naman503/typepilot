#!/usr/bin/env bash
# Builds TypePilot.app, signs it, and installs it to ~/Applications.
#
#   ./scripts/build-app.sh            # release build, sign, install, launch
#   INSTALL=0 ./scripts/build-app.sh  # just build build/TypePilot.app
#   SIGN_IDENTITY="Developer ID Application: …" ./scripts/build-app.sh
#
# A stable signing identity matters: macOS ties the Accessibility permission to the
# app's signature, so ad-hoc signing would ask for permission again after every rebuild.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="${CONFIG:-release}"
echo "▸ swift build -c $CONFIG"
swift build -c "$CONFIG"
BIN="$(swift build -c "$CONFIG" --show-bin-path)/TypePilot"

[ -f Resources/AppIcon.icns ] || node scripts/make-icon.mjs

APP="build/TypePilot.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/TypePilot"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

IDENTITY="${SIGN_IDENTITY:-$(security find-identity -v -p codesigning | grep -m1 -E '"(Developer ID Application|Apple Development)' | sed -E 's/.*"(.*)"/\1/' || true)}"
if [ -n "$IDENTITY" ]; then
  echo "▸ signing with: $IDENTITY"
  codesign --force --options runtime --timestamp=none --sign "$IDENTITY" "$APP"
else
  echo "▸ no signing identity found, signing ad-hoc (you'll need to re-grant Accessibility after each rebuild)"
  codesign --force --sign - "$APP"
fi
codesign --verify --strict "$APP"

if [ "${INSTALL:-1}" = "1" ]; then
  DEST="$HOME/Applications/TypePilot.app"
  pkill -x TypePilot 2>/dev/null || true
  mkdir -p "$HOME/Applications"
  rm -rf "$DEST"
  cp -R "$APP" "$DEST"
  echo "▸ installed to $DEST"
  [ "${LAUNCH:-1}" = "1" ] && open "$DEST"
fi
echo "✓ done"
