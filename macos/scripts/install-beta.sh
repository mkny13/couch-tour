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

# See install.sh's matching comment: CouchTour.xcodeproj is generated, not committed (D103),
# so this has to run on every install or a new/removed .swift file silently doesn't reach the
# build.
if ! command -v xcodegen &>/dev/null; then
    echo "xcodegen not found. Install it with: brew install xcodegen" >&2
    exit 1
fi
echo "Regenerating Xcode project..."
(cd "$macos_dir" && xcodegen generate)

raw_version="${1:-${VERSION:-$(git -C "$macos_dir" describe --tags --abbrev=0 2>/dev/null || echo "0.48")}}"
version="${raw_version#v}-beta"

echo "Building Release ($version)..."
xcodebuild -project "$macos_dir/CouchTour.xcodeproj" -scheme CouchTourBeta \
    -configuration Release -destination 'platform=macOS' \
    -derivedDataPath "$macos_dir/build" \
    MARKETING_VERSION="$version" build

built_app="$macos_dir/build/Build/Products/Release/$app_name"

if [ ! -d "$built_app" ]; then
    echo "Couldn't find the built app at $built_app." >&2
    exit 1
fi

echo "Installing to $dest..."
rm -rf "$dest"
cp -R "$built_app" "$dest"

echo "Launching..."
open "$dest"

echo "Done. Installed from: $built_app"
