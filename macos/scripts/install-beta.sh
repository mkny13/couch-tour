#!/usr/bin/env bash
# Builds the side-installable CouchTourBeta target (D137's macOS counterpart — see
# project.yml) and installs it to /Applications alongside the regular app rather than
# replacing it: distinct bundle id, so this is a genuinely separate install, not an update.
# Ad-hoc signed, same reasoning as install.sh — no Gatekeeper prompt for a locally built app.
set -euo pipefail

macos_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_name="Couch Tour Beta.app"
dest="/Applications/$app_name"

echo "Quitting any running instance..."
pkill -f "$app_name/Contents/MacOS/Couch Tour Beta" 2>/dev/null || true
sleep 1

echo "Building Release..."
xcodebuild -project "$macos_dir/CouchTour.xcodeproj" -scheme CouchTourBeta \
    -configuration Release -destination 'platform=macOS' build

# DerivedData is keyed off the .xcodeproj name (CouchTour), not the scheme, so both targets'
# builds land under the same "CouchTour-*" folder — see install.sh's own note on why this is
# sorted by mtime rather than just taking the first match.
built_app=$(find "$HOME/Library/Developer/Xcode/DerivedData" \
    -path "*/CouchTour-*/Build/Products/Release/$app_name" -maxdepth 5 -print0 2>/dev/null \
    | xargs -0 stat -f '%m %N' \
    | sort -rn \
    | head -1 \
    | cut -d' ' -f2-)

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
