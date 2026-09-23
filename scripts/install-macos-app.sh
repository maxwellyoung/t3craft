#!/bin/bash
# Installs "T3 Craft.app" into /Applications (or $1) pointing at this checkout.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-/Applications}/T3 Craft.app"

rm -rf "$DEST"
mkdir -p "$DEST/Contents/MacOS" "$DEST/Contents/Resources"

cat > "$DEST/Contents/MacOS/T3Craft" <<LAUNCH
#!/bin/bash
exec "$REPO/scripts/launch-macos.sh"
LAUNCH
chmod +x "$DEST/Contents/MacOS/T3Craft"

cat > "$DEST/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key><string>T3 Craft</string>
	<key>CFBundleDisplayName</key><string>T3 Craft</string>
	<key>CFBundleIdentifier</key><string>dev.maxwellyoung.t3craft.launcher</string>
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

cp "$REPO/scripts/AppIcon.icns" "$DEST/Contents/Resources/AppIcon.icns"
# Ad-hoc signature: a local, unsigned bundle can stall on its first launch otherwise.
codesign --force --deep --sign - "$DEST" >/dev/null 2>&1 || true
touch "$DEST"
echo "Installed $DEST"
