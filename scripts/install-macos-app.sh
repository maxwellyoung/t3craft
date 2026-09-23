#!/bin/bash
# Installs "T3 Craft.app" into /Applications (or the given folder) pointing at this checkout.
#   install-macos-app.sh [folder] [--name "App Name"] [--profile file] [--icon file.icns]
# --profile gives the app its own launcher profile (see launch-macos.sh), e.g. a second world.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FOLDER=/Applications
NAME="T3 Craft"
PROFILE=""
ICON="$REPO/scripts/AppIcon.icns"
while [ $# -gt 0 ]; do
	case "$1" in
		--name) NAME="$2"; shift 2 ;;
		--profile) PROFILE="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2 ;;
		--icon) ICON="$2"; shift 2 ;;
		*) FOLDER="$1"; shift ;;
	esac
done
DEST="$FOLDER/$NAME.app"
ID="$(printf '%s' "$NAME" | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]')"

rm -rf "$DEST"
mkdir -p "$DEST/Contents/MacOS" "$DEST/Contents/Resources"

cat > "$DEST/Contents/MacOS/T3Craft" <<LAUNCH
#!/bin/bash
${PROFILE:+export T3CRAFT_PROFILE="$PROFILE"}
exec "$REPO/scripts/launch-macos.sh"
LAUNCH
chmod +x "$DEST/Contents/MacOS/T3Craft"

cat > "$DEST/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key><string>$NAME</string>
	<key>CFBundleDisplayName</key><string>$NAME</string>
	<key>CFBundleIdentifier</key><string>dev.maxwellyoung.t3craft.launcher.$ID</string>
	<key>CFBundleExecutable</key><string>T3Craft</string>
	<key>CFBundleIconFile</key><string>AppIcon</string>
	<key>CFBundlePackageType</key><string>APPL</string>
	<key>CFBundleShortVersionString</key><string>0.1</string>
	<key>LSMinimumSystemVersion</key><string>12.0</string>
	<!-- The launcher is invisible; the game window gets its own Dock icon. -->
	<key>LSUIElement</key><true/>
</dict>
</plist>
PLIST

cp "$ICON" "$DEST/Contents/Resources/AppIcon.icns"
# Ad-hoc signature: a local, unsigned bundle can stall on its first launch otherwise.
codesign --force --deep --sign - "$DEST" >/dev/null 2>&1 || true
touch "$DEST"
echo "Installed $DEST"
