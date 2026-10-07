#!/usr/bin/env bash
# Applies main's branch protection: require the `test` check (job id in
# .github/workflows/test.yml), nothing else. Run once, after the PR that removed test.yml's
# paths: filter has merged — applying it earlier could gate that PR on a check that skips.
# Needs repo admin. Usage: scripts/apply-branch-protection.sh [--dry-run]
set -euo pipefail

REPO="mkny13/couch-tour"
BRANCH="main"
DRY_RUN=0

case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  *) echo "usage: $0 [--dry-run]" >&2; exit 1 ;;
esac

# strict=false: no "branch must be up to date" rule, so Mahler's conductor can merge on green
# (D18). No required reviews, no admin enforcement, no push restrictions.
PAYLOAD='{
  "required_status_checks": {"strict": false, "contexts": ["test"]},
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null
}'

if [ "$DRY_RUN" = 1 ]; then
  echo "[dry-run] PUT repos/$REPO/branches/$BRANCH/protection"
  echo "$PAYLOAD"
  echo "[dry-run] PATCH repos/$REPO allow_squash_merge=true"
  exit 0
fi

echo "$PAYLOAD" | gh api -X PUT "repos/$REPO/branches/$BRANCH/protection" --input - >/dev/null
gh api -X PATCH "repos/$REPO" -F allow_squash_merge=true >/dev/null
gh api "repos/$REPO/branches/$BRANCH/protection"
