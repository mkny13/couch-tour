#!/usr/bin/env bash
# Cuts a beta release the way AGENTS.md's "Working through open issues" section describes:
# side_install + prerelease on build-debug-apk.yml, then is_beta on macos-release.yml,
# tag bumped by one. Wraps the couple of `git tag`/`gh workflow run`/`gh run list` calls a
# session otherwise runs by hand every batch, and computes the next vX.Y tag instead of
# asking the model to eyeball git tag output.
#
# Does NOT promote anything to production — see promote-beta.sh for that, which stays a
# separate, explicitly-invoked step per AGENTS.md ("Mike's call, never automatic").
set -euo pipefail

notes=""
tag=""
while [ $# -gt 0 ]; do
    case "$1" in
        -t|--tag) tag="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [-t vX.Y] <release notes>" >&2
            exit 0
            ;;
        *) notes="$1"; shift ;;
    esac
done

if [ -z "$notes" ]; then
    echo "Usage: $0 [-t vX.Y] <release notes>" >&2
    exit 1
fi

git fetch --tags -q

if [ -z "$tag" ]; then
    # Only consider clean vX.Y tags (no suffix) to avoid "v0.58-beta" confusing the bump.
    latest="$(git tag --list 'v[0-9]*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+$' | head -1)"
    if [ -z "$latest" ]; then
        # Fall back to any vX.Y tag, stripping any suffix first.
        latest="$(git tag --list 'v[0-9]*' --sort=-v:refname | head -1)"
        latest="${latest%%-*}"  # strip e.g. "-beta"
    fi
    if [ -z "$latest" ]; then
        echo "No existing vX.Y tags found — pass one explicitly with -t." >&2
        exit 1
    fi
    major="${latest%.*}"   # e.g. "v0"
    minor="${latest##*.}"  # e.g. "58"
    tag="${major}.$((minor + 1))"
fi

echo "Cutting beta $tag (Android: side_install + prerelease; macOS: is_beta)..."

# Android
gh workflow run build-debug-apk.yml \
    -f release_tag="$tag" \
    -f release_notes="$notes" \
    -f prerelease=true \
    -f side_install=true

echo "Waiting for Android run to register..."
android_url=""
for _ in 1 2 3 4 5; do
    sleep 2
    android_url="$(gh run list --workflow=build-debug-apk.yml --event=workflow_dispatch \
        --limit 1 --json url,createdAt -q '.[0].url' 2>/dev/null || true)"
    [ -n "$android_url" ] && break
done

# macOS — runs against main branch; the workflow uses the release_tag input to determine
# what to build and where to upload the zip.
gh workflow run macos-release.yml --ref main \
    -f release_tag="$tag" \
    -f is_beta=true

echo "Waiting for macOS run to register..."
macos_url=""
for _ in 1 2 3 4 5; do
    sleep 2
    macos_url="$(gh run list --workflow=macos-release.yml --event=workflow_dispatch \
        --limit 1 --json url,createdAt -q '.[0].url' 2>/dev/null || true)"
    [ -n "$macos_url" ] && break
done

echo ""
echo "Tag: $tag"
if [ -n "$android_url" ]; then
    echo "Android run: $android_url"
else
    echo "Android: dispatched but run URL not found — check: gh run list --workflow=build-debug-apk.yml"
fi
if [ -n "$macos_url" ]; then
    echo "macOS run:   $macos_url"
else
    echo "macOS: dispatched but run URL not found — check: gh run list --workflow=macos-release.yml"
fi
