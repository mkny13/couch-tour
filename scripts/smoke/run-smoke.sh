#!/usr/bin/env bash
# scripts/smoke/run-smoke.sh
# Orchestrator: run the Mac and Android smoke runners against one beta tag, write
# smoke-reports/<tag>.md via report.sh, and file one issue per failing journey.
#
# Exit codes: 0 = every selected platform ran and a report was written (the report decides
# pass/fail), 1 = usage error, 2 = a platform could not be run (preflight/setup failure).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_SLUG="mkny13/couch-tour"

# Overridable so the filing flow can be exercised with stubs.
RUN_MAC="${CCTV_SMOKE_RUN_MAC:-$SCRIPT_DIR/run-mac.sh}"
RUN_ANDROID="${CCTV_SMOKE_RUN_ANDROID:-$SCRIPT_DIR/run-android.sh}"
GH_BIN="${CCTV_SMOKE_GH:-gh}"
MAHLER_BIN="${CCTV_SMOKE_MAHLER:-mahler}"

TAG=""; PLATFORM="both"; OUT_DIR="smoke-reports"; FILE_BUGS=true
COMMIT_SCREENSHOTS=false; KEEP_GOING=false
WAIVE_ARGS=(); WAIVE_IDS=()

usage() {
  cat << 'EOF2'
Usage: scripts/smoke/run-smoke.sh [--tag <tag>] [OPTIONS]

Options:
  --tag <tag>                Beta tag under test (default: git describe --tags --abbrev=0)
  --platform mac|android|both  Platforms to run (default: both)
  --out <dir>                Report directory (default: smoke-reports); report is <dir>/<tag>.md
  --waive <id> - <reason>    Accept a failing journey in the verdict (repeatable)
  --no-file-bugs             Do not file or comment on GitHub issues (exploratory runs)
  --commit-screenshots       Leave smoke-reports/<tag>/*.png unignored; default keeps them local
                             because they show the owner's signed-in library (this repo is public)
  --keep-going               Carry on to the next platform when a runner exits with a usage error
                             (1); a preflight failure (2) never stops the other platform
  -h, --help                 Print this usage message

Exit codes: 0 = all selected platforms ran and a report was written, 1 = usage error,
2 = a platform could not be run (preflight/setup failure; no report if none ran).
EOF2
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit "${2:-1}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) [[ -n "${2:-}" ]] || die "--tag requires a value"; TAG="$2"; shift 2 ;;
    --platform) [[ "${2:-}" =~ ^(mac|android|both)$ ]] || die "--platform must be mac, android, or both"; PLATFORM="$2"; shift 2 ;;
    --out) [[ -n "${2:-}" ]] || die "--out requires a directory"; OUT_DIR="$2"; shift 2 ;;
    --waive)
      [[ -n "${2:-}" ]] || die "--waive requires '<id> - <reason>'"
      if [[ "${3:-}" == "-" ]]; then
        [[ -n "${4:-}" ]] || die "--waive $2 needs a reason"
        WAIVE_ARGS+=(--waive "$2" - "$4"); WAIVE_IDS+=("$2"); shift 4
      else
        [[ "$2" == *" - "* ]] || die "--waive expects '<id> - <reason>', got '$2'"
        WAIVE_ARGS+=(--waive "$2"); WAIVE_IDS+=("${2%% - *}"); shift 2
      fi ;;
    --no-file-bugs) FILE_BUGS=false; shift ;;
    --commit-screenshots) COMMIT_SCREENSHOTS=true; shift ;;
    --keep-going) KEEP_GOING=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "Unknown argument: $1" ;;
  esac
done

cd "$REPO_ROOT"

if [[ -z "$TAG" ]]; then
  TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  [[ -n "$TAG" ]] || die "no tag to report on and none given. Pass --tag <beta-tag>, or fetch tags with: git fetch --tags"
fi

if [[ "$COMMIT_SCREENSHOTS" == true ]]; then
  echo "NOTE: --commit-screenshots: remember to 'git add -f $OUT_DIR/$TAG/*.png'. Check them first; they show the signed-in library." >&2
fi

RUN_DIR="$OUT_DIR/$TAG"
mkdir -p "$RUN_DIR"
PLATFORMS=(); case "$PLATFORM" in both) PLATFORMS=(mac android) ;; *) PLATFORMS=("$PLATFORM") ;; esac

RESULT_ARGS=(); RESULT_FILES=(); NOTRUN_ARGS=(); ran=0; notrun=0
for plat in "${PLATFORMS[@]}"; do
  results="$RUN_DIR/$plat-results.txt"; errlog="$RUN_DIR/$plat-stderr.txt"
  rm -f "$results"
  runner="$RUN_MAC"; [[ "$plat" == android ]] && runner="$RUN_ANDROID"
  echo "== $plat: $runner" >&2
  rc=0
  # The runners write their screenshots under smoke-reports/<tag>/ relative to the repo root.
  "$runner" --tag "$TAG" --out "$results" 2> >(tee "$errlog" >&2) >/dev/null || rc=$?
  wait 2>/dev/null || true
  case "$rc" in
    0) ran=$((ran + 1)); RESULT_ARGS+=(--results "$results"); RESULT_FILES+=("$results") ;;
    2)
      reason="$(grep -h 'ERROR:' "$errlog" 2>/dev/null | tail -1 | sed 's/^ERROR: //' || true)"
      [[ -n "$reason" ]] || reason="preflight failed (runner exit 2)"
      NOTRUN_ARGS+=(--not-run "$plat - $reason"); notrun=$((notrun + 1)) ;;
    *)
      NOTRUN_ARGS+=(--not-run "$plat - runner exited $rc before completing (see $errlog)"); notrun=$((notrun + 1))
      [[ "$KEEP_GOING" == true ]] || break ;;
  esac
done

if [[ $ran -eq 0 ]]; then
  echo "No platform could be run; no report written." >&2
  rmdir "$RUN_DIR" 2>/dev/null || true
  for a in "${NOTRUN_ARGS[@]:-}"; do [[ "$a" == --not-run ]] || echo "  $a" >&2; done
  exit 2
fi

# ---- Bug filing -------------------------------------------------------------------------
ISSUE_ARGS=()
file_bug() { # file_bug <journey-id> -> prints issue url (existing or new)
  local id="$1" marker="smoke-journey:$1" plat ev rows="" url num existing
  while IFS=$'\t' read -r plat jid status ev; do
    [[ "$jid" == "$id" && "$status" == FAIL ]] && rows+="- **$plat**: $ev"$'\n'
  done < <(cat "${RESULT_FILES[@]}" 2>/dev/null | grep -v '^#' || true)
  local shot_plat; shot_plat="$(printf '%s' "$rows" | head -1 | sed -E 's/^- \*\*([a-z]+).*/\1/')"

  existing="$("$GH_BIN" issue list -R "$REPO_SLUG" --state open --search "$marker in:title,body" \
      --json number,url,title,body 2>/dev/null \
    | jq -r --arg m "$marker" '[.[] | select((.title + " " + .body) | contains($m))][0] // empty | "\(.number) \(.url)"' 2>/dev/null || true)"
  if [[ -n "$existing" ]]; then
    num="${existing%% *}"; url="${existing#* }"
    "$GH_BIN" issue comment "$num" -R "$REPO_SLUG" --body "<!-- mahler:agent -->
Still failing in smoke run for \`$TAG\` (\`$marker\`):

$rows" >/dev/null 2>&1 || echo "WARN: could not comment on $url" >&2
    echo "Smoke: $id already filed, commented on $url" >&2
    printf '%s' "$url"; return 0
  fi

  local title="Smoke journey failing: $id ($TAG)"
  local body="<!-- $marker -->
Smoke journey \`$id\` failed against beta tag \`$TAG\`.

**Failing on:**
$rows
**Pass condition and steps:** see \`$id\` in [scripts/smoke/JOURNEYS.md](scripts/smoke/JOURNEYS.md).
**Screenshot (local, not committed):** \`$OUT_DIR/$TAG/${shot_plat:-unknown}-$id.png\`
**Marker:** \`$marker\` (used to dedupe later runs).

Found by \`scripts/smoke/run-smoke.sh\`; report: \`$OUT_DIR/$TAG.md\`."
  local out
  if command -v "$MAHLER_BIN" >/dev/null 2>&1; then
    out="$("$MAHLER_BIN" add phish-in "$title" --body "$body" 2>&1)" || { echo "WARN: mahler add failed: $out" >&2; return 1; }
  else
    echo "WARNING: '$MAHLER_BIN' not on PATH; falling back to gh issue create (label still applied)" >&2
    out="$("$GH_BIN" issue create -R "$REPO_SLUG" --title "$title" --body "$body" 2>&1)" || { echo "WARN: gh issue create failed: $out" >&2; return 1; }
  fi
  num="$(printf '%s' "$out" | grep -oE '(issues/|#)[0-9]+' | head -1 | grep -oE '[0-9]+' || true)"
  [[ -n "$num" ]] || { echo "WARN: could not read issue number from: $out" >&2; return 1; }
  "$GH_BIN" issue edit "$num" -R "$REPO_SLUG" --add-label mahler >/dev/null \
    || echo "WARN: could not label #$num 'mahler'; add it by hand" >&2
  url="https://github.com/$REPO_SLUG/issues/$num"
  echo "Smoke: $id filed as $url" >&2
  printf '%s' "$url"
}

if [[ "$FILE_BUGS" == true && ${#RESULT_ARGS[@]} -gt 0 ]]; then
  fail_ids="$(cat "${RESULT_FILES[@]}" | awk -F'\t' '$3=="FAIL"{print $2}' | sort -u)"
  for id in $fail_ids; do
    waived=false
    for w in ${WAIVE_IDS[@]+"${WAIVE_IDS[@]}"}; do [[ "$w" == "$id" ]] && waived=true; done
    [[ "$waived" == true ]] && continue
    if url="$(file_bug "$id")" && [[ -n "$url" ]]; then ISSUE_ARGS+=(--issue "$id=$url"); fi
  done
fi

# ---- Report -------------------------------------------------------------------------------
REPORT="$OUT_DIR/$TAG.md"
"$SCRIPT_DIR/report.sh" "${RESULT_ARGS[@]}" --tag "$TAG" --out "$REPORT" \
  ${WAIVE_ARGS[@]+"${WAIVE_ARGS[@]}"} ${NOTRUN_ARGS[@]+"${NOTRUN_ARGS[@]}"} ${ISSUE_ARGS[@]+"${ISSUE_ARGS[@]}"}

echo
echo "Report:  $REPORT"
grep '^Smoke: ' "$REPORT"
[[ $notrun -eq 0 ]] || exit 2
exit 0
