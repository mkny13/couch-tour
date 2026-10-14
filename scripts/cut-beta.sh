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
watch=1
while [ $# -gt 0 ]; do
    case "$1" in
        -t|--tag) tag="$2"; shift 2 ;;
        --no-watch) watch=0; shift ;;
        -h|--help)
            echo "Usage: $0 [-t vX.Y] [--no-watch] <release notes>" >&2
            exit 0
            ;;
        *) notes="$1"; shift ;;
    esac
done

if [ -z "$notes" ]; then
    echo "Usage: $0 [-t vX.Y] [--no-watch] <release notes>" >&2
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

# Resolve the run `gh workflow run` just dispatched. Newer gh prints its URL, which is exact;
# taking the newest run from `gh run list` instead can pick up someone else's concurrent dispatch,
# so that stays only as the fallback for when no URL was printed.
resolve_run() {
    local workflow="$1" dispatch_out="$2" url
    url="$(printf '%s\n' "$dispatch_out" | grep -Eo 'https://[^ ]+/actions/runs/[0-9]+' | head -1 || true)"
    if [ -n "$url" ]; then
        run_url="$url"
        run_id="${url##*/}"
        return
    fi
    run_url=""
    run_id=""
    for _ in 1 2 3 4 5; do
        sleep 2
        run_url="$(gh run list --workflow="$workflow" --event=workflow_dispatch \
            --limit 1 --json url -q '.[0].url' 2>/dev/null || true)"
        run_id="$(gh run list --workflow="$workflow" --event=workflow_dispatch \
            --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)"
        [ -n "$run_url" ] && break
    done
    return 0  # a failed last `[ -n ]` must not trip set -e; callers handle the empty case
}

echo "Cutting beta $tag (Android: side_install + prerelease; macOS: is_beta)..."

# Android
android_dispatch="$(gh workflow run build-debug-apk.yml \
    -f release_tag="$tag" \
    -f release_notes="$notes" \
    -f prerelease=true \
    -f side_install=true 2>&1)"

resolve_run build-debug-apk.yml "$android_dispatch"
android_url="$run_url"
android_id="$run_id"

# macOS — runs against main branch; the workflow uses the release_tag input to determine
# what to build and where to upload the zip.
macos_dispatch="$(gh workflow run macos-release.yml --ref main \
    -f release_tag="$tag" \
    -f is_beta=true 2>&1)"

resolve_run macos-release.yml "$macos_dispatch"
macos_url="$run_url"
macos_id="$run_id"

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
echo ""
echo "Next step: a smoke report must be committed at smoke-reports/$tag.md before promoting to production."
if [ -f "scripts/smoke/run-smoke.sh" ]; then
    echo "Run smoke: scripts/smoke/run-smoke.sh --tag $tag"
fi
echo "Promote:   scripts/promote-beta.sh $tag <next-tag> \"<release notes>\""

# A dispatched run that later fails used to go unnoticed (v0.85-v0.87 shipped Android-only),
# so block on each run and make a failure impossible to miss.
failed=0
check_missing() {
    local label="$1" id="$2" url="$3"
    if [ -z "$id" ]; then
        echo "" >&2
        echo "!!! $label BUILD UNVERIFIED: run id not found, cannot watch it. Check: ${url:-gh run list}" >&2
        failed=1
        return
    fi
}

check_missing "ANDROID" "$android_id" "$android_url"
check_missing "MAC" "$macos_id" "$macos_url"

if [ "$watch" != 1 ]; then
    if [ "$failed" != 0 ]; then
        echo "" >&2
        echo "Beta $tag is NOT fully shipped; see the failure above." >&2
    fi
    exit 0
fi

watch_run() {
    local label="$1" id="$2" url="$3"
    echo ""
    echo "Watching $label run $id..."
    if gh run watch "$id" --exit-status >/dev/null 2>&1; then
        echo "$label build succeeded."
        return
    fi
    failed=1
    {
        echo ""
        echo "=========================================================="
        echo "$label BUILD FAILED for $tag: $url"
        echo "----------------------- failed log tail ------------------"
        gh run view "$id" --log-failed 2>&1 | tail -40 || true
        echo "=========================================================="
    } >&2
}

watch_run "ANDROID" "$android_id" "$android_url"
watch_run "MAC" "$macos_id" "$macos_url"

if [ "$failed" != 0 ]; then
    echo "" >&2
    echo "Beta $tag is NOT fully shipped; see the failure above." >&2
    exit 1
fi
echo ""
echo "Beta $tag shipped on Android and macOS."
echo "Next step: commit smoke-reports/$tag.md before promotion (scripts/promote-beta.sh)."
if [ -f "scripts/smoke/run-smoke.sh" ]; then
    echo "Run smoke: scripts/smoke/run-smoke.sh --tag $tag"
fi
