#!/usr/bin/env bash
# scripts/smoke/verify-comment.sh
# Post the standard "verified in beta" evidence comment on a shipped issue (#404).
# The <!-- mahler:verified --> marker is what report.sh/run-smoke.sh (and mahler#611) look for.

set -euo pipefail

REPO_SLUG="mkny13/couch-tour"
GH_BIN="${CCTV_VERIFY_GH:-gh}"

usage() {
  cat << 'EOF2'
Usage: scripts/smoke/verify-comment.sh [--dry-run] <issue> <tag> <evidence...>

  <issue>     Issue number, with or without '#' (346 or #346)
  <tag>       Beta tag the fix was seen working in (e.g. v0.87 or v0.87-beta)
  <evidence>  A smoke-report line (smoke-reports/<tag>.md: PASS <journey>), a screenshot/dump
              path, or an owner UAT note. Remaining arguments are joined with spaces.
  --dry-run   Print the target issue and comment body; make no network call
EOF2
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

DRY=false
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
if [[ "${1:-}" == "--dry-run" ]]; then DRY=true; shift; fi
[[ $# -ge 3 ]] || { usage >&2; die "need <issue> <tag> <evidence...>"; }

issue="${1#\#}"; tag="$2"; shift 2
evidence="$*"
[[ "$issue" =~ ^[0-9]+$ ]] || die "issue must be a number (got '$1')"
[[ "$tag" =~ ^v[0-9]+(\.[0-9]+)+(-[A-Za-z0-9.]+)?$ ]] || die "tag must look like v0.87 or v0.87-beta (got '$tag')"
evidence="${evidence//$'\n'/ }"
[[ -n "${evidence// /}" ]] || die "evidence must not be empty"
bt='`'; sq="'"; evidence="${evidence//$bt/$sq}"

body="<!-- mahler:verified -->
Verified in beta \`$tag\`:
- Evidence: \`$evidence\`"

if [[ "$DRY" == true ]]; then
  printf 'Issue: #%s\n---\n%s\n' "$issue" "$body"
  exit 0
fi
"$GH_BIN" issue comment "$issue" -R "$REPO_SLUG" --body "$body"
