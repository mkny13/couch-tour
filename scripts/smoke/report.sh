#!/usr/bin/env bash
# scripts/smoke/report.sh
# Pure formatter: smoke result files -> smoke-reports/<tag>.md. No app, device, or network.
#
# The Tag:/Smoke:/Waived: line grammar is read by the promotion gate in
# scripts/promote-beta.sh (#358); the rest of the file is for humans.

set -euo pipefail

usage() {
  cat << 'EOF2'
Usage: scripts/smoke/report.sh --results <file>... --tag <tag> --out <report.md> [OPTIONS]

Options:
  --results <file>          Tab-separated result file (<platform>\t<id>\t<status>\t<evidence>);
                            repeatable. A line "# preflight: <platform> - <reason>" marks a platform
                            that could not be run (runner exit 2).
  --tag <tag>               Beta tag the run was made against
  --out <report.md>         Report file to write
  --waive <id> - <reason>   Accept a FAIL journey (repeatable; "<id> - <reason>" as one argument also works)
  --not-run <platform> - <reason>
                            Same as a preflight line, given on the command line (repeatable)
  --issue <id>=<url>        Link a filed bug issue to a failing journey (repeatable)
  --unverified-issues <file>
                            Tab-separated shipped-but-unverified issues (<number>\t<title>\t<body>);
                            adds an "Unverified shipped issues" table marking each covered, not covered,
                            or still reproduces against this run's journeys (#404)
  --journeys <file>         JOURNEYS.md used to map issue numbers to journeys (default: next to this script)
  --commit <sha>            Commit shown in the footer (default: git rev-parse --short HEAD)
  -h, --help                Print this usage message

Verdict: "Smoke: PASS" only if no journey is FAIL (or each FAIL is waived) and no platform failed
preflight. SKIP is reported as "not verified" and neither passes nor blocks. A run with no results
is FAIL.
EOF2
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

RESULTS=(); TAG=""; OUT=""; COMMIT=""
WAIVE_IDS=(); WAIVE_REASONS=()
NOTRUN_PLATFORMS=(); NOTRUN_REASONS=()
ISSUE_IDS=(); ISSUE_URLS=()
UNVERIFIED=""; JOURNEYS_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/JOURNEYS.md"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --results) [[ -n "${2:-}" ]] || die "--results requires a file"; RESULTS+=("$2"); shift 2 ;;
    --tag) [[ -n "${2:-}" ]] || die "--tag requires a value"; TAG="$2"; shift 2 ;;
    --out) [[ -n "${2:-}" ]] || die "--out requires a file"; OUT="$2"; shift 2 ;;
    --unverified-issues) [[ -n "${2:-}" ]] || die "--unverified-issues requires a file"; UNVERIFIED="$2"; shift 2 ;;
    --journeys) [[ -n "${2:-}" ]] || die "--journeys requires a file"; JOURNEYS_FILE="$2"; shift 2 ;;
    --commit) COMMIT="${2:-}"; shift 2 ;;
    --waive|--not-run)
      flag="$1"; [[ -n "${2:-}" ]] || die "$flag requires '<name> - <reason>'"
      if [[ "${3:-}" == "-" ]]; then
        name="$2"; reason="${4:-}"; shift 4 || die "$flag requires a reason"
      else
        [[ "$2" == *" - "* ]] || die "$flag expects '<name> - <reason>', got '$2'"
        name="${2%% - *}"; reason="${2#* - }"; shift 2
      fi
      [[ -n "$reason" ]] || die "$flag $name needs a reason"
      if [[ "$flag" == "--waive" ]]; then WAIVE_IDS+=("$name"); WAIVE_REASONS+=("$reason")
      else NOTRUN_PLATFORMS+=("$name"); NOTRUN_REASONS+=("$reason"); fi
      ;;
    --issue)
      [[ "${2:-}" == *=* ]] || die "--issue expects <id>=<url>"
      ISSUE_IDS+=("${2%%=*}"); ISSUE_URLS+=("${2#*=}"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "Unknown argument: $1" ;;
  esac
done

[[ ${#RESULTS[@]} -gt 0 ]] || die "at least one --results file is required"
[[ -n "$TAG" ]] || die "--tag is required"
[[ -n "$OUT" ]] || die "--out is required"
[[ "$TAG" != *$'\n'* ]] || die "--tag must be a single line"
[[ -z "$UNVERIFIED" || -f "$UNVERIFIED" ]] || die "unverified-issues file not found: $UNVERIFIED"
for f in "${RESULTS[@]}"; do [[ -f "$f" ]] || die "results file not found: $f"; done

lookup() { # lookup <needle> <array-name-of-keys> <array-name-of-values>
  local needle="$1" keys="$2" vals="$3" i n
  eval "n=\${#$keys[@]}"
  for ((i = 0; i < n; i++)); do
    local k v; eval "k=\${$keys[$i]}; v=\${$vals[$i]}"
    if [[ "$k" == "$needle" ]]; then printf '%s' "$v"; return 0; fi
  done
  return 1
}

cell() { # make a value safe for a one-line table cell, otherwise verbatim
  local s="${1//$'\r'/}"; s="${s//$'\n'/ }"; printf '%s' "${s//|/\\|}"
}

# Parse results. Rows are kept in input order.
ROW_PLAT=(); ROW_ID=(); ROW_STATUS=(); ROW_EVID=()
while IFS= read -r f; do
  while IFS=$'\t' read -r plat id status evid || [[ -n "${plat:-}" ]]; do
    [[ -z "${plat// /}" ]] && continue
    if [[ "$plat" == "#"* ]]; then
      if [[ "$plat" =~ ^#[[:space:]]*preflight:[[:space:]]*([^[:space:]]+)[[:space:]]+-[[:space:]]+(.*)$ ]]; then
        NOTRUN_PLATFORMS+=("${BASH_REMATCH[1]}"); NOTRUN_REASONS+=("${BASH_REMATCH[2]}")
      fi
      continue
    fi
    case "${status:-}" in
      PASS|FAIL|SKIP) ;;
      *) die "$f: bad result line (platform='$plat' id='${id:-}' status='${status:-}')" ;;
    esac
    ROW_PLAT+=("$plat"); ROW_ID+=("$id"); ROW_STATUS+=("$status"); ROW_EVID+=("${evid:-}")
  done < "$f"
done < <(printf '%s\n' "${RESULTS[@]}")

# Verdict
unwaived=0; waived_hit=(); fails=0; skips=0; passes=0
for ((i = 0; i < ${#ROW_ID[@]}; i++)); do
  case "${ROW_STATUS[$i]}" in
    PASS) passes=$((passes + 1)) ;;
    SKIP) skips=$((skips + 1)) ;;
    FAIL)
      fails=$((fails + 1))
      if lookup "${ROW_ID[$i]}" WAIVE_IDS WAIVE_REASONS >/dev/null; then
        waived_hit+=("${ROW_ID[$i]}")
      else
        unwaived=$((unwaived + 1))
      fi ;;
  esac
done
verdict=PASS
[[ $unwaived -eq 0 ]] || verdict=FAIL
[[ ${#NOTRUN_PLATFORMS[@]} -eq 0 ]] || verdict=FAIL
[[ ${#ROW_ID[@]} -gt 0 ]] || verdict=FAIL

# Journey -> issue-number references from JOURNEYS.md ("#345" inside a journey's section or table row).
JREF_ID=(); JREF_NUM=()
if [[ -n "$UNVERIFIED" && -f "$JOURNEYS_FILE" ]]; then
  cur=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^###[[:space:]]+\`([a-z0-9-]+)\` ]]; then cur="${BASH_REMATCH[1]}"; continue; fi
    row=""
    [[ "$line" =~ ^\|[[:space:]]*\`([a-z0-9-]+)\` ]] && row="${BASH_REMATCH[1]}"
    owner="${row:-$cur}"; [[ -n "$owner" ]] || continue
    for n in $(printf '%s\n' "$line" | grep -oE '#[0-9]+' || true); do JREF_ID+=("$owner"); JREF_NUM+=("${n#\#}"); done
  done < "$JOURNEYS_FILE"
fi

# unverified_row <number> <title> <body> -> "<status>\t<notes>"; a FAIL anywhere beats a PASS.
unverified_row() {
  local num="$1" hay="$2 $3" i j id state="not covered" note="no journey ran for this issue" pass_note="" fail_note=""
  local -a cand=()
  for ((j = 0; j < ${#JREF_ID[@]}; j++)); do [[ "${JREF_NUM[$j]}" == "$num" ]] && cand+=("${JREF_ID[$j]}"); done
  for ((i = 0; i < ${#ROW_ID[@]}; i++)); do
    id="${ROW_ID[$i]}"; local hit=false c
    for c in ${cand[@]+"${cand[@]}"}; do [[ "$c" == "$id" ]] && hit=true; done
    [[ "$hit" == true || "$hay" == *"$id"* ]] || continue
    case "${ROW_STATUS[$i]}" in
      FAIL) [[ -n "$fail_note" ]] || fail_note="FAIL \`$id\` (${ROW_PLAT[$i]}): ${ROW_EVID[$i]}" ;;
      PASS) [[ -n "$pass_note" ]] || pass_note="PASS \`$id\` (${ROW_PLAT[$i]})" ;;
    esac
  done
  if [[ -n "$fail_note" ]]; then state="still reproduces"; note="$fail_note"
  elif [[ -n "$pass_note" ]]; then state="covered"; note="smoke-reports/$TAG.md: $pass_note"; fi
  printf '%s\t%s' "$state" "$note"
}

[[ -n "$COMMIT" ]] || COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

mkdir -p "$(dirname "$OUT")"
{
  echo "# Smoke report"
  echo
  echo "Tag: $TAG"
  echo "Smoke: $verdict"
  echo
  if [[ "$verdict" == PASS ]]; then
    seen=" "
    for id in ${waived_hit[@]+"${waived_hit[@]}"}; do
      [[ "$seen" == *" $id "* ]] && continue
      seen+="$id "
      echo "Waived: $id - $(lookup "$id" WAIVE_IDS WAIVE_REASONS)"
    done
    [[ ${#waived_hit[@]} -eq 0 ]] || echo
  fi
  echo "$passes passed, $fails failed (${#waived_hit[@]} waived), $skips skipped."
  echo

  if [[ ${#NOTRUN_PLATFORMS[@]} -gt 0 ]]; then
    echo "## Platforms that did not run"
    echo
    echo "**These platforms could not be run at all, so this report cannot be a PASS.**"
    echo
    for ((i = 0; i < ${#NOTRUN_PLATFORMS[@]}; i++)); do
      echo "- \`${NOTRUN_PLATFORMS[$i]}\`: $(cell "${NOTRUN_REASONS[$i]}")"
    done
    echo
  fi

  echo "## Journeys"
  echo
  echo "| id | platform | status | evidence |"
  echo "|---|---|---|---|"
  for ((i = 0; i < ${#ROW_ID[@]}; i++)); do
    echo "| \`${ROW_ID[$i]}\` | ${ROW_PLAT[$i]} | ${ROW_STATUS[$i]} | $(cell "${ROW_EVID[$i]}") |"
  done
  echo

  if [[ -n "$UNVERIFIED" ]]; then
    echo "## Unverified shipped issues"
    echo
    echo "Shipped issues with no \`<!-- mahler:verified -->\` evidence comment, checked against this run's journeys."
    echo
    echo "| Issue | Title | Status | Evidence / Notes |"
    echo "|---|---|---|---|"
    unv=0
    while IFS=$'\t' read -r unum utitle ubody || [[ -n "${unum:-}" ]]; do
      [[ -n "${unum// /}" ]] || continue
      unv=$((unv + 1))
      res="$(unverified_row "${unum#\#}" "${utitle:-}" "${ubody:-}")"
      echo "| #${unum#\#} | $(cell "${utitle:-}") | ${res%%$'\t'*} | $(cell "${res#*$'\t'}") |"
    done < "$UNVERIFIED"
    [[ $unv -gt 0 ]] || echo "| none | | | |"
    echo
  fi

  if [[ $skips -gt 0 ]]; then
    echo "## Skipped (not verified)"
    echo
    echo "| id | platform | reason |"
    echo "|---|---|---|"
    for ((i = 0; i < ${#ROW_ID[@]}; i++)); do
      [[ "${ROW_STATUS[$i]}" == SKIP ]] || continue
      echo "| \`${ROW_ID[$i]}\` | ${ROW_PLAT[$i]} | $(cell "${ROW_EVID[$i]}") |"
    done
    echo
  fi

  if [[ $fails -gt 0 ]]; then
    echo "## Failures"
    echo
    for ((i = 0; i < ${#ROW_ID[@]}; i++)); do
      [[ "${ROW_STATUS[$i]}" == FAIL ]] || continue
      id="${ROW_ID[$i]}"; plat="${ROW_PLAT[$i]}"
      echo "### \`$id\` ($plat)"
      echo
      echo "- Evidence: $(cell "${ROW_EVID[$i]}")"
      echo "- Screenshot (local, not committed): \`smoke-reports/$TAG/${plat%%[^a-z]*}-$id.png\`"
      if url="$(lookup "$id" ISSUE_IDS ISSUE_URLS)"; then
        echo "- Issue: $url"
      else
        echo "- Issue: none filed"
      fi
      if reason="$(lookup "$id" WAIVE_IDS WAIVE_REASONS)"; then
        echo "- Waiver: $(cell "$reason")"
      fi
      echo
    done
  fi

  echo "---"
  echo
  echo "Run on $(date '+%Y-%m-%d') from commit \`$COMMIT\` with \`scripts/smoke/run-mac.sh\` and \`scripts/smoke/run-android.sh\` (via \`scripts/smoke/run-smoke.sh\`)."
} > "$OUT"

# Self-check: a report that violates its own grammar is a bug, not a result.
[[ -s "$OUT" ]] || { echo "report.sh: empty report" >&2; exit 1; }
[[ "$(grep -c '^Tag: ' "$OUT")" == 1 ]] || { echo "report.sh: expected exactly one Tag: line" >&2; exit 1; }
[[ "$(grep -c '^Tag: '"$(printf '%s' "$TAG" | sed 's/[][\.*^$/]/\\&/g')"'$' "$OUT")" == 1 ]] || { echo "report.sh: Tag: line does not equal --tag" >&2; exit 1; }
[[ "$(grep -c '^Smoke: ' "$OUT")" == 1 ]] || { echo "report.sh: expected exactly one Smoke: line" >&2; exit 1; }
grep -q '^Smoke: \(PASS\|FAIL\)$' "$OUT" || { echo "report.sh: malformed Smoke: line" >&2; exit 1; }
if [[ "$verdict" == FAIL ]] && grep -q '^Waived: ' "$OUT"; then echo "report.sh: Waived: line on a FAIL report" >&2; exit 1; fi
exit 0
