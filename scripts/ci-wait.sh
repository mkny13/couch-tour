#!/usr/bin/env bash
# Waits for a PR's checks to finish and, on failure, pulls the failing job's log tail — the
# poll-then-diagnose loop a coding session otherwise does turn by turn (`gh pr checks`, wait,
# `gh pr checks` again, then `gh run view` once something fails). One call here does the whole
# thing, since `gh pr checks --watch` already blocks until all checks report in.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <pr-number> [--fail-fast]" >&2
    exit 1
fi

pr="$1"
shift
watch_flags=(--watch)
[ "${1:-}" = "--fail-fast" ] && watch_flags+=(--fail-fast)

echo "Watching checks for PR #$pr..."
if gh pr checks "$pr" "${watch_flags[@]}"; then
    echo "All checks passed."
    exit 0
fi

status=$?
# Exit code 8 means checks are still pending (only possible without --watch, but handle it
# defensively); anything else here means at least one check failed.
if [ "$status" -eq 8 ]; then
    echo "Checks still pending — try again." >&2
    exit 8
fi

echo
echo "=== Failing checks ==="
gh pr checks "$pr" --json name,bucket,link -q '.[] | select(.bucket == "fail") | "\(.name)\t\(.link)"' \
    | while IFS=$'\t' read -r name link; do
        run_id="$(grep -oE '/runs/[0-9]+' <<<"$link" | head -1 | cut -d/ -f3)"
        echo
        echo "--- $name (run $run_id) ---"
        if [ -n "$run_id" ]; then
            gh run view "$run_id" --log-failed 2>&1 | tail -80
        else
            echo "Couldn't parse a run ID from: $link"
        fi
    done

exit "$status"
