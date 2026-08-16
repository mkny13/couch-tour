#!/usr/bin/env bash
# Builds the Release configuration and installs it to /Applications, replacing whatever's
# there. Ad-hoc signed (no paid Apple Developer account configured — see D93/the install
# notes), which is why this works with no Gatekeeper prompt: only files downloaded from the
# internet get quarantined, and a locally built app never does.
set -euo pipefail

macos_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_name="Couch Tour.app"
dest="/Applications/$app_name"

echo "Quitting any running instance..."
pkill -f "$app_name/Contents/MacOS/Couch Tour" 2>/dev/null || true
sleep 1

echo "Building Release..."
xcodebuild -project "$macos_dir/CouchTour.xcodeproj" -scheme CouchTour \
    -configuration Release -destination 'platform=macOS' build

built_app=$(find "$HOME/Library/Developer/Xcode/DerivedData" \
    -path "*/CouchTour-*/Build/Products/Release/$app_name" -maxdepth 5 2>/dev/null | head -1)

if [ -z "$built_app" ]; then
    echo "Couldn't find the built app under DerivedData." >&2
    exit 1
fi

echo "Installing to $dest..."
rm -rf "$dest"
cp -R "$built_app" "$dest"

echo "Launching..."
open "$dest"

echo "Done. Installed from: $built_app"
