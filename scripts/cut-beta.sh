#!/usr/bin/env bash
# Cuts a beta release the way CLAUDE.md's "Working through open issues" section describes:
# side_install + prerelease on build-debug-apk.yml, tag bumped by one. Wraps the couple of
# `git tag`/`gh workflow run`/`gh run list` calls a session otherwise runs by hand every batch,
# and computes the next vX.Y tag instead of asking the model to eyeball git tag output.
#
# Does NOT promote anything to production — see promote-beta.sh for that, which stays a
# separate, explicitly-invoked step per CLAUDE.md ("Mike's call, never automatic").
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
    latest="$(git tag --list 'v*' --sort=-v:refname | head -1)"
    if [ -z "$latest" ]; then
        echo "No existing vX.Y tags found — pass one explicitly with -t." >&2
        exit 1
    fi
    major="${latest%.*}"
    minor="${latest##*.}"
    tag="${major}.$((minor + 1))"
fi

echo "Cutting beta $tag (side_install, prerelease)..."
gh workflow run build-debug-apk.yml \
    -f release_tag="$tag" \
    -f release_notes="$notes" \
    -f prerelease=true \
    -f side_install=true

echo "Dispatched. Locating the run..."
for _ in 1 2 3 4 5; do
    sleep 2
    run_url="$(gh run list --workflow=build-debug-apk.yml --event=workflow_dispatch \
        --limit 1 --json url,createdAt -q '.[0].url' 2>/dev/null || true)"
    [ -n "$run_url" ] && break
done

if [ -n "$run_url" ]; then
    echo "Run: $run_url"
else
    echo "Dispatched but couldn't locate the run — check: gh run list --workflow=build-debug-apk.yml"
fi

echo "Tag: $tag"
