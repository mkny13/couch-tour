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

echo "Fetching latest tags from origin..."
git -C "$macos_dir" fetch --tags -q 2>/dev/null || true

# CouchTour.xcodeproj is generated, not committed (D103),
# so this has to run on every install or a new/removed .swift file silently doesn't reach the
# build.
if ! command -v xcodegen &>/dev/null; then
    echo "xcodegen not found. Install it with: brew install xcodegen" >&2
    exit 1
fi
echo "Regenerating Xcode project..."
(cd "$macos_dir" && xcodegen generate)

latest_tag=$(git -C "$macos_dir" tag -l 'v*' 2>/dev/null | sort -V | tail -n 1)
latest_tag="${latest_tag:-$(git -C "$macos_dir" describe --tags --abbrev=0 2>/dev/null || echo "0.51")}"
raw_version="${1:-${VERSION:-$latest_tag}}"
version="${raw_version#v}"

echo "Building Release ($version)..."
xcodebuild -project "$macos_dir/CouchTour.xcodeproj" -scheme CouchTour \
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
