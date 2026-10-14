#!/usr/bin/env bash
# Promotes an already-confirmed beta to production, per CLAUDE.md's "Promoting a beta to
# production is Mike's call, never automatic": rebuilds from that beta's *own* tag (not
# whatever's on main now — later betas may have shipped since) as a non-beta, non-prerelease
# release. This script only wraps the mechanics; the decision to run it is unchanged — invoke
# it only after Mike has explicitly confirmed a specific beta tag.
#
# Promotion requires a passing smoke report at smoke-reports/<tag>.md (issue #358).
# Bypass with --force-no-smoke; all bypasses are logged in smoke-reports/.forced-promotions.log.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
reports_dir="${SMOKE_REPORTS_DIR:-$repo_root/smoke-reports}"
log_file="$reports_dir/.forced-promotions.log"

check_smoke_report() {
    local tag="$1"
    local report_file="$reports_dir/$tag.md"

    if [ ! -f "$report_file" ]; then
        echo "report not found at $report_file"
        return 1
    fi

    if [ ! -s "$report_file" ]; then
        echo "report at $report_file is empty"
        return 1
    fi

    local tag_count
    tag_count="$(grep -c '^Tag:' "$report_file" || true)"
    if [ "$tag_count" -ne 1 ]; then
        echo "report at $report_file must have exactly one 'Tag:' line (found $tag_count)"
        return 1
    fi

    local tag_val
    tag_val="$(grep '^Tag:' "$report_file" | head -1 | sed -e 's/^Tag:[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if [ "$tag_val" != "$tag" ]; then
        echo "report at $report_file is tagged for '$tag_val', expected '$tag'"
        return 1
    fi

    local smoke_count
    smoke_count="$(grep -c '^Smoke:' "$report_file" || true)"
    if [ "$smoke_count" -ne 1 ]; then
        echo "report at $report_file must have exactly one 'Smoke:' line (found $smoke_count)"
        return 1
    fi

    local smoke_val
    smoke_val="$(grep '^Smoke:' "$report_file" | head -1 | sed -e 's/^Smoke:[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if [ "$smoke_val" = "FAIL" ]; then
        echo "report at $report_file recorded Smoke: FAIL"
        return 1
    fi

    if [ "$smoke_val" != "PASS" ]; then
        echo "report at $report_file has invalid Smoke verdict '$smoke_val' (expected PASS or FAIL)"
        return 1
    fi

    # Check for any unwaived FAIL journey rows: | `id` | ... | FAIL | ... |
    local fail_id
    while IFS= read -r fail_id; do
        [ -z "$fail_id" ] && continue
        if ! grep -q -E "^Waived:[[:space:]]*$fail_id[[:space:]]*-" "$report_file"; then
            echo "report at $report_file claims Smoke: PASS but contains unwaived FAIL journey: $fail_id"
            return 1
        fi
    done < <(grep -E '^\|[[:space:]]*`?[a-zA-Z0-9_-]+`?[[:space:]]*\|[^|]*\|[[:space:]]*FAIL[[:space:]]*\|' "$report_file" 2>/dev/null | \
             sed -E 's/^\|[[:space:]]*`?([a-zA-Z0-9_-]+)`?[[:space:]]*\|.*/\1/' || true)

    return 0
}

force_no_smoke=0
check_only=0
args=()

while [ $# -gt 0 ]; do
    case "$1" in
        --force-no-smoke)
            force_no_smoke=1
            shift
            ;;
        --check-only)
            check_only=1
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--force-no-smoke] <confirmed-beta-tag> <next-tag> <release notes>" >&2
            echo "       $0 --check-only <tag>" >&2
            exit 0
            ;;
        *)
            args+=("$1")
            shift
            ;;
    esac
done

if [ "$check_only" -eq 1 ]; then
    if [ "${#args[@]}" -lt 1 ]; then
        echo "Usage: $0 --check-only <tag>" >&2
        exit 1
    fi
    tag="${args[0]}"
    if check_err="$(check_smoke_report "$tag")"; then
        echo "Smoke report for $tag is valid (Smoke: PASS)"
        exit 0
    else
        echo "Smoke check failed for $tag: $check_err" >&2
        exit 1
    fi
fi

if [ "${#args[@]}" -lt 3 ]; then
    echo "Usage: $0 [--force-no-smoke] <confirmed-beta-tag> <next-tag> <release notes>" >&2
    echo "Example: $0 v0.31 v0.32 \"Promoting v0.31 after Mike confirmed it on device.\"" >&2
    exit 1
fi

confirmed_ref="${args[0]}"
next_tag="${args[1]}"
notes="${args[2]}"

report_file="$reports_dir/$confirmed_ref.md"
smoke_err=""
if ! smoke_err="$(check_smoke_report "$confirmed_ref")"; then
    if [ "$force_no_smoke" -eq 1 ]; then
        {
            echo "========================================================================"
            echo "WARNING: --force-no-smoke specified — bypassing smoke report gate."
            echo "Reason check failed: $smoke_err"
            echo "No passing smoke report verified for $confirmed_ref before promoting to production!"
            echo "Logging forced promotion to $log_file"
            echo "========================================================================"
        } >&2
        mkdir -p "$(dirname "$log_file")"
        timestamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
        actor="${GITHUB_ACTOR:-$(whoami 2>/dev/null || echo "unknown")}"
        echo "$timestamp forced $confirmed_ref -> $next_tag by $actor" >> "$log_file"
    else
        {
            echo "ERROR: Promotion blocked — $smoke_err"
            echo "  Report path checked: $report_file"
            echo "  To fix:              run smoke tests and commit passing report"
            echo "                       scripts/smoke/run-smoke.sh --tag $confirmed_ref"
            echo "  Escape hatch:        re-run with --force-no-smoke to bypass and log to $log_file"
        } >&2
        exit 1
    fi
fi

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
