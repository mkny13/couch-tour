#!/usr/bin/env bash
# scripts/smoke/weekly-smoke.sh
# Weekly scheduled smoke runner for the Mac mini (issue #358, DECISIONS D342).
#
# Rules:
# - Runs between 00:00 and 06:00 local time on the mini through Mahler's desktop gate (mkny13/mahler#779).
# - If the gate is closed (someone using the Mac or outside window), retries later in the window.
#   If it stays closed all night, skips that week's run.
# - If app code changed (app/, macos/, sync/) since the latest vX.Y tag, cuts a beta first
#   via scripts/cut-beta.sh and tests the new tag.
# - If no app code changed, tests the existing latest beta.
# - Never promotes. Promotion stays owner-only via scripts/promote-beta.sh.
set -euo pipefail

dry_run=0
use_gate=1
retry=1
retry_interval=900
end_hour=6
explicit_tag=""

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)
            dry_run=1
            shift
            ;;
        --no-gate)
            use_gate=0
            shift
            ;;
        --no-retry)
            retry=0
            shift
            ;;
        --retry-interval)
            retry_interval="${2:-900}"
            shift 2
            ;;
        --end-hour)
            end_hour="${2:-6}"
            shift 2
            ;;
        -t|--tag)
            explicit_tag="${2:-}"
            shift 2
            ;;
        -h|--help)
            cat << 'EOF'
Usage: scripts/smoke/weekly-smoke.sh [OPTIONS]

Options:
  --dry-run             Print planned actions without cutting beta or running smoke
  --no-gate             Bypass the mahler desktop gate wrapper
  --no-retry            Do not retry if the desktop gate is closed
  --retry-interval SEC  Seconds to wait between gate retries (default: 900 / 15m)
  --end-hour H          Local 24h hour when the night window ends (default: 6)
  -t, --tag TAG         Smoke-test an explicit tag instead of latest / new cut
  -h, --help            Show this help message
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# Handle desktop gate if enabled and mahler command is available
if [ "$use_gate" -eq 1 ] && command -v mahler >/dev/null 2>&1; then
    while true; do
        current_hour="$(date +"%H" | sed 's/^0//')"
        current_hour="${current_hour:-0}"

        # If we have reached or passed end_hour and were retrying into the morning
        if [ "$current_hour" -ge "$end_hour" ] && [ "$current_hour" -lt 12 ]; then
            echo "Desktop gate stayed closed through 00:00-06:00 window; skipping this week's smoke run."
            exit 0
        fi

        gate_rc=0
        mahler desktop run --scheduled -- "$0" --no-gate \
            $([ "$dry_run" -eq 1 ] && echo "--dry-run") \
            $([ -n "$explicit_tag" ] && echo "--tag $explicit_tag") || gate_rc=$?

        if [ "$gate_rc" -eq 0 ]; then
            exit 0
        fi

        # Exit 75 = mahler desktop EX_DEFERRED (gate closed, Mac busy, or outside window)
        if [ "$gate_rc" -eq 75 ]; then
            if [ "$retry" -ne 1 ]; then
                echo "mahler desktop gate is closed (exit 75); --no-retry set, exiting." >&2
                exit 75
            fi
            echo "mahler desktop gate is closed (exit 75). Retrying in ${retry_interval}s..."
            sleep "$retry_interval"
            continue
        fi

        # Any other failure from the child command
        exit "$gate_rc"
    done
fi

# Inner execution (either gated via mahler desktop run, or --no-gate)
git fetch --tags -q 2>/dev/null || true

if [ -n "$explicit_tag" ]; then
    target_tag="$explicit_tag"
    echo "Using explicit tag: $target_tag"
else
    # Find latest clean vX.Y tag
    latest="$(git tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | grep -E '^v[0-9]+\.[0-9]+$' | head -1 || true)"
    if [ -z "$latest" ]; then
        latest="$(git tag --list 'v*' --sort=-v:refname 2>/dev/null | head -1 || true)"
    fi
    if [ -z "$latest" ]; then
        echo "ERROR: No existing v* tags found." >&2
        exit 1
    fi

    # Check whether app code changed in app/, macos/, or sync/ since latest tag
    app_changes="$(git diff --name-only "$latest"..HEAD -- app/ macos/ sync/ 2>/dev/null || true)"

    if [ -n "$app_changes" ]; then
        echo "App code changed since $latest in app/, macos/, or sync/:"
        echo "$app_changes" | sed 's/^/  /'
        if [ "$dry_run" -eq 1 ]; then
            echo "[dry-run] Would cut new beta via scripts/cut-beta.sh and run smoke tests against it."
            exit 0
        fi
        echo "Cutting beta for weekly smoke run..."
        ./scripts/cut-beta.sh "Weekly smoke automated beta cut"
        new_tag="$(git tag --list 'v[0-9]*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+$' | head -1)"
        target_tag="$new_tag"
    else
        echo "No app code changes since $latest in app/, macos/, or sync/. Smoke testing existing beta $latest."
        target_tag="$latest"
    fi
fi

if [ "$dry_run" -eq 1 ]; then
    echo "[dry-run] Would run: scripts/smoke/run-smoke.sh --tag $target_tag --platform both --allow-focus --avd phishin_test"
    exit 0
fi

echo "Running smoke tests for $target_tag..."
exec ./scripts/smoke/run-smoke.sh --tag "$target_tag" --platform both --allow-focus --avd phishin_test
