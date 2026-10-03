#!/usr/bin/env bash
# scripts/smoke/test-report.sh
# Headless test of the report.sh gate contract (Tag:/Smoke:/Waived: grammar read by #358).
# Needs no app, device, or beta: it feeds checked-in fixtures to report.sh.

set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$DIR/testdata"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
TAG="v9.99-beta"
failures=0

ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

# run <name> <expected verdict> <report.sh args...>; leaves report in $TMP/<name>.md
run() {
  local name="$1" want="$2"; shift 2
  local out="$TMP/$name.md"
  "$DIR/report.sh" "$@" --tag "$TAG" --out "$out" --commit testsha >/dev/null 2>"$TMP/$name.err" \
    || { bad "$name: report.sh exited non-zero ($(cat "$TMP/$name.err"))"; return; }
  check "$name: exactly one Tag: line equal to tag" test "$(grep -c '^Tag: ' "$out")" = 1 -a "$(grep -c "^Tag: $TAG\$" "$out")" = 1
  check "$name: exactly one Smoke: line" test "$(grep -c '^Smoke: ' "$out")" = 1
  check "$name: verdict is $want" grep -q "^Smoke: $want\$" "$out"
  if [[ "$want" == FAIL ]]; then
    check "$name: no Waived: line on FAIL" test "$(grep -c '^Waived: ' "$out")" = 0
  fi
}

run all-pass PASS --results "$DATA/all-pass.tsv"
check "all-pass: no Failures section" test "$(grep -c '^## Failures' "$TMP/all-pass.md")" = 0

run fail FAIL --results "$DATA/fail.tsv"
check "fail: failure screenshot path listed" grep -q 'smoke-reports/v9.99-beta/mac-search-artist-hit.png' "$TMP/fail.md"
check "fail: no filed issue is noted" grep -q 'Issue: none filed' "$TMP/fail.md"
check "fail: evidence passed through verbatim (public-repo rule: runners must keep evidence identifier-level)" grep -q 'Planted Artist Xyzzy' "$TMP/fail.md"
check "fail: pipe in evidence escaped" grep -q 'not found \\| pipe' "$TMP/fail.md"

run fail-waived PASS --results "$DATA/fail.tsv" --waive search-artist-hit - "known flaky on CI"
check "fail-waived: Waived: line present" grep -qx 'Waived: search-artist-hit - known flaky on CI' "$TMP/fail-waived.md"

run fail-wrong-waiver FAIL --results "$DATA/fail.tsv" --waive some-other-id - "irrelevant"

run fail-issue FAIL --results "$DATA/fail.tsv" --issue search-artist-hit=https://example.test/issues/1
check "fail-issue: issue link listed" grep -q 'Issue: https://example.test/issues/1' "$TMP/fail-issue.md"

run skip PASS --results "$DATA/skip.tsv"
check "skip: Skipped table lists the skip" grep -q 'favorite-persists-across-relaunch.*not signed in' "$TMP/skip.md"
check "skip: reported as not verified" grep -q 'Skipped (not verified)' "$TMP/skip.md"

run preflight FAIL --results "$DATA/preflight.tsv"
check "preflight: names the platform that did not run" grep -q '`android`: No Android device' "$TMP/preflight.md"

run preflight-waive-cannot-help FAIL --results "$DATA/preflight.tsv" --waive launch-cold-start - "nope"
run not-run-flag FAIL --results "$DATA/all-pass.tsv" --not-run android - "adb missing"
check "not-run-flag: names platform" grep -q '`android`: adb missing' "$TMP/not-run-flag.md"

# Two result files merge into one report.
run merged PASS --results "$DATA/all-pass.tsv" --results "$DATA/skip.tsv"

# An empty run verifies nothing and can't pass.
: > "$TMP/empty.tsv"
run empty FAIL --results "$TMP/empty.tsv"

# Malformed input is refused rather than reported.
check "malformed result line is rejected" bash -c "printf 'mac\tx\tMAYBE\tz\n' > '$TMP/bad.tsv'; ! '$DIR/report.sh' --results '$TMP/bad.tsv' --tag t --out '$TMP/bad.md' 2>/dev/null"

# Re-running the same tag/verdict differs only in the date footer.
"$DIR/report.sh" --results "$DATA/fail.tsv" --tag "$TAG" --out "$TMP/again.md" --commit testsha >/dev/null
check "rerun diff is confined to the footer date" test -z "$(diff <(grep -v '^Run on' "$TMP/fail.md") <(grep -v '^Run on' "$TMP/again.md"))"

if [[ $failures -gt 0 ]]; then echo "$failures check(s) failed"; exit 1; fi
echo "all report checks passed"
