#!/usr/bin/env bash
# Promotes a beta release to production by rebuilding from that beta's tag as a non-beta,
# non-prerelease release.
#
# Production promotion is gated on a passing smoke report at smoke-reports/<tag>.md (D342, issue #251).
# Auto-promotion runs without human approval when smoke tests pass; with no passing report,
# the beta stays a beta.
# Manual bypass with --force-no-smoke is recorded in smoke-reports/.forced-promotions.log.
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

compute_next_tag() {
    local highest
    highest="$(git tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | grep -E '^v[0-9]+\.[0-9]+$' | head -1 || true)"
    if [ -z "$highest" ]; then
        highest="${1:-v0.0}"
    fi
    local major="${highest%.*}"
    local minor="${highest##*.}"
    echo "${major}.$((minor + 1))"
}

force_no_smoke=0
check_only=0
auto_mode=0
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
        --auto)
            auto_mode=1
            shift
            ;;
        -h|--help)
            cat << 'EOF'
Usage: scripts/promote-beta.sh [OPTIONS] [<beta-tag>] [<next-tag>] [<release notes>]
       scripts/promote-beta.sh --auto
       scripts/promote-beta.sh --check-only <tag>

Options:
  --auto              Find latest beta tag and auto-promote if its smoke report passed
  --check-only <tag>  Check whether smoke report for <tag> passes without promoting
  --force-no-smoke    Bypass smoke report gate (escape hatch, logged to .forced-promotions.log)
  -h, --help          Show this help message
EOF
            exit 0
            ;;
        *)
            args+=("$1")
            shift
            ;;
    esac
done

if [ "$auto_mode" -eq 1 ] && [ "$force_no_smoke" -eq 1 ]; then
    echo "ERROR: --auto and --force-no-smoke cannot be combined. Auto-promotion strictly requires a passing smoke report." >&2
    exit 1
fi

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

if [ "$auto_mode" -eq 1 ]; then
    git fetch --tags -q 2>/dev/null || true
    latest_tag="$(git tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | grep -E '^v[0-9]+\.[0-9]+$' | head -1 || true)"
    if [ -z "$latest_tag" ]; then
        latest_tag="$(git tag --list 'v*' --sort=-v:refname 2>/dev/null | head -1 || true)"
    fi
    if [ -z "$latest_tag" ]; then
        echo "No tags found; nothing to auto-promote."
        exit 0
    fi

    # Check if latest release is already production
    if command -v gh >/dev/null 2>&1; then
        gh_prerelease="$(gh release view "$latest_tag" --json isPrerelease -q .isPrerelease 2>/dev/null || true)"
        if [ "$gh_prerelease" = "false" ]; then
            echo "Latest release $latest_tag is already production; nothing to auto-promote."
            exit 0
        fi
    fi

    confirmed_ref="$latest_tag"
    if ! check_err="$(check_smoke_report "$confirmed_ref")"; then
        echo "Smoke report for $confirmed_ref did not pass ($check_err); beta stays a beta."
        exit 0
    fi

    next_tag="$(compute_next_tag "$confirmed_ref")"
    notes="Production release $next_tag: auto-promoted from $confirmed_ref after passing smoke report."
else
    if [ "${#args[@]}" -lt 1 ]; then
        echo "Usage: $0 [--force-no-smoke] <beta-tag> [<next-tag>] [<release notes>]" >&2
        echo "       $0 --auto" >&2
        echo "       $0 --check-only <tag>" >&2
        exit 1
    fi

    confirmed_ref="${args[0]}"
    git fetch --tags -q 2>/dev/null || true

    if [ "${#args[@]}" -ge 2 ]; then
        next_tag="${args[1]}"
    else
        next_tag="$(compute_next_tag "$confirmed_ref")"
    fi

    if [ "${#args[@]}" -ge 3 ]; then
        notes="${args[2]}"
    else
        notes="Production release $next_tag: auto-promoted from $confirmed_ref after passing smoke report."
    fi
fi

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

git fetch --tags -q 2>/dev/null || true
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
