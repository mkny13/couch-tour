#!/usr/bin/env bash
# Promotes an already-confirmed beta to production, per CLAUDE.md's "Promoting a beta to
# production is Mike's call, never automatic": rebuilds from that beta's *own* tag (not
# whatever's on main now — later betas may have shipped since) as a non-beta, non-prerelease
# release. This script only wraps the mechanics; the decision to run it is unchanged — invoke
# it only after Mike has explicitly confirmed a specific beta tag.
set -euo pipefail

if [ $# -lt 3 ]; then
    echo "Usage: $0 <confirmed-beta-tag> <next-tag> <release notes>" >&2
    echo "Example: $0 v0.31 v0.32 \"Promoting v0.31 after Mike confirmed it on device.\"" >&2
    exit 1
fi

confirmed_ref="$1"
next_tag="$2"
notes="$3"

git fetch --tags -q
if ! git rev-parse "$confirmed_ref" >/dev/null 2>&1; then
    echo "Tag $confirmed_ref not found locally or on origin — check the tag name." >&2
    exit 1
fi

echo "Promoting $confirmed_ref -> $next_tag (regular release, updates the daily-driver install)..."
gh workflow run build-debug-apk.yml \
    --ref "$confirmed_ref" \
    -f release_tag="$next_tag" \
    -f release_notes="$notes" \
    -f prerelease=false \
    -f side_install=false

echo "Dispatched from ref $confirmed_ref. Check: gh run list --workflow=build-debug-apk.yml --limit 3"
