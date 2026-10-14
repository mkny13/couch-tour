#!/usr/bin/env bash
# scripts/smoke-nudge.sh
# Weekly staleness check (#358): flags when the latest beta tag has no passing smoke report.
# If stale, opens or comments on rolling issue 'Smoke run due: <tag>' and exits non-zero.
set -euo pipefail

tag=""
dry_run=0

while [ $# -gt 0 ]; do
    case "$1" in
        -t|--tag)
            tag="${2:-}"
            shift 2
            ;;
        --dry-run)
            dry_run=1
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--tag vX.Y] [--dry-run]" >&2
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Usage: $0 [--tag vX.Y] [--dry-run]" >&2
            exit 1
            ;;
    esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
promote_script="$script_dir/promote-beta.sh"

if [ -z "$tag" ]; then
    tag="$(git tag --list 'v*' --sort=-v:refname 2>/dev/null | head -1 || true)"
fi

if [ -z "$tag" ]; then
    echo "ERROR: No v* tag found locally." >&2
    exit 1
fi

title="Smoke run due: $tag"

# Run the exact same verdict check as the gate in promote-beta.sh
check_err=""
if check_err="$("$promote_script" --check-only "$tag" 2>&1)"; then
    echo "Smoke report for $tag is current (Smoke: PASS)."
    exit 0
fi

# Extract the reason line
reason="$(printf '%s\n' "$check_err" | head -1)"

echo "Smoke report for $tag is stale: $reason"

# Search for existing issue titled exactly "Smoke run due: <tag>"
search_query="$title in:title"
existing_issue=""

existing_issue="$(gh issue list --search "$search_query" --state all --limit 10 --json number,title --jq ".[] | select(.title == \"$title\") | .number" 2>/dev/null | head -1 || true)"
if [ -z "$existing_issue" ]; then
    existing_issue="$(gh issue list --search "$search_query" --state all --limit 10 2>/dev/null | grep -F "$title" | awk '{print $1}' | sed 's/^#//' | head -1 || true)"
fi

body=$(cat <<EOF
## Problem

The beta release **$tag** does not have a passing smoke report in \`smoke-reports/$tag.md\`.
Promotion to production is blocked until smoke tests pass.

- **Tag:** \`$tag\`
- **Status:** $reason
- **Blocked command:** \`scripts/promote-beta.sh $tag <next-tag> "<release notes>"\`

## Action required

Run smoke tests for \`$tag\` and commit the passing report at \`smoke-reports/$tag.md\`:

\`\`\`bash
scripts/smoke/run-smoke.sh --tag $tag
\`\`\`
EOF
)

if [ "$dry_run" -eq 1 ]; then
    if [ -n "$existing_issue" ]; then
        echo "[dry-run] Would comment on existing issue #$existing_issue with body:"
        echo "gh issue comment $existing_issue --body \"...\""
    else
        echo "[dry-run] Would create issue titled '$title':"
        echo "gh issue create --title \"$title\" --label mahler --body \"...\""
    fi
    exit 1
fi

if [ -n "$existing_issue" ]; then
    echo "Updating existing issue #$existing_issue..."
    gh issue comment "$existing_issue" --body "$body"
else
    echo "Creating issue: $title..."
    gh issue create --title "$title" --label mahler --body "$body"
fi

exit 1
