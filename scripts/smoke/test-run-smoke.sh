#!/usr/bin/env bash
# scripts/smoke/test-run-smoke.sh
# Headless test of run-smoke.sh orchestration:
# 1. Report generation and grammar (Tag:, Smoke: PASS|FAIL, preflight exits) (#357, #358)
# 2. Bug filing via mahler add, labeling with gh, dedupe, and --no-file-bugs (#357)
# 3. Flag pass-through to platform runners and release-APK download (#610, #640)
# Uses stub runners and stub gh/mahler; no device, app or network.

set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required for test-run-smoke.sh but is not installed"
  exit 1
fi

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
TAG="v9.99-beta"
trap 'rm -rf "$TMP" "smoke-reports/$TAG"' EXIT
failures=0

ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

# Stub runners for mac and android:
# - Logs argv to $CALLS_DIR/calls.<plat>
# - Can exit non-zero via STUB_<PLAT>_RC
# - Writes results from STUB_<PLAT>_RESULTS, or defaults to 1 PASS row
# - For each FAIL row, creates the local screenshot png on disk
for plat in mac android; do
  cat > "$TMP/run-$plat" << 'STUB'
#!/usr/bin/env bash
plat="$(basename "$0" | sed 's/^run-//')"
calls_dir="${CALLS_DIR:-$TMP}"
echo "$*" >> "$calls_dir/calls.$plat"

out=""
tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --tag) tag="$2"; shift 2 ;;
    *) shift ;;
  esac
done

uc_plat="$(echo "$plat" | tr '[:lower:]' '[:upper:]')"
rc_var="STUB_${uc_plat}_RC"
results_var="STUB_${uc_plat}_RESULTS"
err_var="STUB_${uc_plat}_ERR"

rc="${!rc_var:-0}"
if [[ "$rc" -ne 0 ]]; then
  echo "${!err_var:-ERROR: preflight failed for $plat}" >&2
  exit "$rc"
fi

if [[ -n "$out" ]]; then
  mkdir -p "$(dirname "$out")"
  if [[ -n "${!results_var:-}" ]]; then
    printf '%s\n' "${!results_var}" > "$out"
  else
    printf '%s\tlaunch-cold-start\tPASS\tstub\n' "$plat" > "$out"
  fi
  while IFS=$'\t' read -r p id st ev; do
    if [[ "$st" == "FAIL" && -n "$tag" && -n "$id" ]]; then
      mkdir -p "smoke-reports/$tag" "$(dirname "$out")"
      touch "smoke-reports/$tag/$p-$id.png"
      touch "$(dirname "$out")/$p-$id.png"
    fi
  done < "$out"
fi
exit 0
STUB
done

# Stub sync runner: logs argv, creates empty results file, exits 0.
cat > "$TMP/run-sync" << 'STUB'
#!/usr/bin/env bash
calls_dir="${CALLS_DIR:-$TMP}"
echo "$*" >> "$calls_dir/calls.sync"
out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[[ -n "$out" ]] && : > "$out"
exit 0
STUB

# Stub gh:
# - Logs argv to $CALLS_DIR/calls.gh
# - 'release download' touches the asset unless GH_FAIL=1
# - 'issue list' returns STUB_GH_OPEN_ISSUES when queried for open issues, otherwise []
# - 'issue edit' / 'issue comment' succeed silently
# - 'issue create' returns a fake issue URL
cat > "$TMP/gh" << 'STUB'
#!/usr/bin/env bash
calls_dir="${CALLS_DIR:-$TMP}"
echo "$*" >> "$calls_dir/calls.gh"
case "$1 $2" in
  "release download")
    [[ "${GH_FAIL:-}" == 1 ]] && { echo "release not found" >&2; exit 1; }
    tag="$3"
    dir=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --dir) dir="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -n "$dir" ]] && : > "$dir/couch-tour-$tag.apk"
    ;;
  "issue list")
    has_state_open=false
    for arg in "$@"; do
      [[ "$arg" == "open" ]] && has_state_open=true
    done
    if [[ "$has_state_open" == true && -n "${STUB_GH_OPEN_ISSUES:-}" ]]; then
      echo "$STUB_GH_OPEN_ISSUES"
    else
      echo '[]'
    fi
    ;;
  "issue edit")
    exit 0
    ;;
  "issue comment")
    exit 0
    ;;
  "issue create")
    echo "https://github.com/mkny13/couch-tour/issues/9001"
    exit 0
    ;;
esac
STUB

# Stub mahler: logs argv to $CALLS_DIR/calls.mahler, returns fake issue URL for add phish-in.
cat > "$TMP/mahler" << 'STUB'
#!/usr/bin/env bash
calls_dir="${CALLS_DIR:-$TMP}"
echo "$*" >> "$calls_dir/calls.mahler"
case "$1 $2" in
  "add phish-in")
    echo "https://github.com/mkny13/couch-tour/issues/9001"
    exit 0
    ;;
  *)
    exit 0
    ;;
esac
STUB

chmod +x "$TMP"/run-mac "$TMP"/run-android "$TMP"/run-sync "$TMP"/gh "$TMP"/mahler

# Stub adb and emulator
mkdir -p "$TMP/bin"
cat > "$TMP/bin/adb" << STUB
#!/usr/bin/env bash
if [[ "\$1" == devices ]]; then
  echo "List of devices attached"
  [[ -e "$TMP/booted" ]] && printf 'emulator-5554\tdevice\n'
  [[ -n "\${PHONE:-}" ]] && printf 'R5CT123\tdevice\n'
  exit 0
fi
[[ "\$1" == -s ]] && shift 2
case "\$1 \$2" in
  "shell getprop") [[ -e "$TMP/bootprop" ]] && echo 1 ;;
esac
exit 0
STUB
cat > "$TMP/emulator" << STUB
#!/usr/bin/env bash
echo "\$*" > "$TMP/emulator.args"
echo "\$\$" > "$TMP/emulator.pid"
[[ "\${EMU_EXIT:-}" == 1 ]] && exit 1
touch "$TMP/booted"; [[ "\${EMU_NOBOOT:-}" == 1 ]] || touch "$TMP/bootprop"
exec sleep 300
STUB
chmod +x "$TMP/bin/adb" "$TMP/emulator"

# run <name> [VAR=val...] -- <run-smoke args...>; exit code in $RC, calls in $TMP/<name>/calls.*
run() {
  local name="$1"; shift; local envs=() sync_args=(--no-sync)
  while [[ "$1" != -- ]]; do envs+=("$1"); shift; done; shift
  local args=()
  for a in "$@"; do
    if [[ "$a" == --sync ]]; then sync_args=(); else args+=("$a"); fi
  done
  mkdir -p "$TMP/$name"; rm -f "$TMP"/calls.*
  rm -f "$TMP/booted" "$TMP/bootprop" "$TMP/emulator.args" "$TMP/emulator.pid"
  CALLS_DIR="$TMP" \
  env CCTV_SMOKE_RUN_MAC="$TMP/run-mac" \
      CCTV_SMOKE_RUN_ANDROID="$TMP/run-android" \
      CCTV_SMOKE_RUN_SYNC="$TMP/run-sync" \
      CCTV_SMOKE_GH="$TMP/gh" \
      CCTV_SMOKE_MAHLER="$TMP/mahler" \
      PATH="$TMP/bin:$PATH" \
      CCTV_SMOKE_EMULATOR="$TMP/emulator" \
      CCTV_SMOKE_AVD_TIMEOUT=6 \
      ${envs[@]+"${envs[@]}"} \
      "$DIR/run-smoke.sh" --tag "$TAG" ${sync_args[@]+"${sync_args[@]}"} --out "$TMP/$name/out" "${args[@]}" \
      >"$TMP/$name/stdout" 2>"$TMP/$name/stderr"
  RC=$?
  for f in "$TMP"/calls.*; do [[ -e "$f" ]] && mv "$f" "$TMP/$name/$(basename "$f")"; done
}
called() { [[ -s "$TMP/$1/calls.$2" ]]; }
argv() { cat "$TMP/$1/calls.$2" 2>/dev/null; }

# ==============================================================================
# 1-5. Core orchestrator & bug filing contract (#357, #358)
# ==============================================================================

# Case 1: all-pass, both platforms
run all-pass -- --platform both
check "1. all-pass: exit 0" test "$RC" = 0
check "1. all-pass: report exists" test -f "$TMP/all-pass/out/$TAG.md"
check "1. all-pass: mac runner invoked" called all-pass mac
check "1. all-pass: android runner invoked" called all-pass android
check "1. all-pass: exactly one Tag: line" test "$(grep -c "^Tag: $TAG\$" "$TMP/all-pass/out/$TAG.md")" = 1
check "1. all-pass: exactly one Smoke: line" test "$(grep -c '^Smoke: ' "$TMP/all-pass/out/$TAG.md")" = 1
check "1. all-pass: Smoke: PASS" grep -q '^Smoke: PASS$' "$TMP/all-pass/out/$TAG.md"
check "1. all-pass: no mahler add in log" bash -c "! grep -q 'add phish-in' '$TMP/all-pass/calls.mahler' 2>/dev/null"

# Case 2: one FAIL, first run
fail_tsv=$'mac\tsearch-artist-hit\tFAIL\tartist not found'
run one-fail STUB_MAC_RESULTS="$fail_tsv" -- --platform both
check "2. one-fail: exit 0" test "$RC" = 0
check "2. one-fail: exactly one Smoke: line" test "$(grep -c '^Smoke: ' "$TMP/one-fail/out/$TAG.md")" = 1
check "2. one-fail: Smoke: FAIL" grep -q '^Smoke: FAIL$' "$TMP/one-fail/out/$TAG.md"
check "2. one-fail: report mentions screenshot" grep -q "smoke-reports/$TAG/mac-search-artist-hit.png" "$TMP/one-fail/out/$TAG.md"
check "2. one-fail: screenshot exists on disk" test -f "smoke-reports/$TAG/mac-search-artist-hit.png"
check "2. one-fail: exactly one mahler add call" test "$(grep -c 'add phish-in' "$TMP/one-fail/calls.mahler" 2>/dev/null)" = 1
check "2. one-fail: mahler body contains marker" grep -q 'smoke-journey:search-artist-hit' "$TMP/one-fail/calls.mahler"
check "2. one-fail: gh issue edit labels mahler" grep -Eq 'issue edit 9001 .*--add-label mahler' "$TMP/one-fail/calls.gh"

# Case 3: same FAIL, repeat run (dedupe)
existing_issue='[{"number":9001,"url":"https://github.com/mkny13/couch-tour/issues/9001","title":"Smoke journey failing: search-artist-hit","body":"<!-- smoke-journey:search-artist-hit -->"}]'
run repeat-fail STUB_MAC_RESULTS="$fail_tsv" STUB_GH_OPEN_ISSUES="$existing_issue" -- --platform both
check "3. repeat-fail: exit 0" test "$RC" = 0
check "3. repeat-fail: exactly one Smoke: line" test "$(grep -c '^Smoke: ' "$TMP/repeat-fail/out/$TAG.md")" = 1
check "3. repeat-fail: Smoke: FAIL" grep -q '^Smoke: FAIL$' "$TMP/repeat-fail/out/$TAG.md"
check "3. repeat-fail: no mahler add call" bash -c "! grep -q 'add phish-in' '$TMP/repeat-fail/calls.mahler' 2>/dev/null"
check "3. repeat-fail: gh issue comment called" grep -q 'issue comment 9001' "$TMP/repeat-fail/calls.gh"

# Case 4: --no-file-bugs
run no-file-bugs STUB_MAC_RESULTS="$fail_tsv" -- --platform both --no-file-bugs
check "4. no-file-bugs: exit 0" test "$RC" = 0
check "4. no-file-bugs: exactly one Smoke: line" test "$(grep -c '^Smoke: ' "$TMP/no-file-bugs/out/$TAG.md")" = 1
check "4. no-file-bugs: Smoke: FAIL" grep -q '^Smoke: FAIL$' "$TMP/no-file-bugs/out/$TAG.md"
check "4. no-file-bugs: no mahler add" bash -c "! grep -q 'add phish-in' '$TMP/no-file-bugs/calls.mahler' 2>/dev/null"
check "4. no-file-bugs: no gh issue edit/comment/create" bash -c "! grep -Eq 'issue (edit|comment|create)' '$TMP/no-file-bugs/calls.gh' 2>/dev/null"

# Case 5: one platform preflight-fails (stub exits 2)
run preflight-fail STUB_ANDROID_RC=2 STUB_ANDROID_ERR="ERROR: emulator boot timeout" -- --platform both
check "5. preflight-fail: exit 2" test "$RC" = 2
check "5. preflight-fail: report exists" test -f "$TMP/preflight-fail/out/$TAG.md"
check "5. preflight-fail: exactly one Tag: line" test "$(grep -c "^Tag: $TAG\$" "$TMP/preflight-fail/out/$TAG.md")" = 1
check "5. preflight-fail: exactly one Smoke: line" test "$(grep -c '^Smoke: ' "$TMP/preflight-fail/out/$TAG.md")" = 1
check "5. preflight-fail: Smoke: FAIL" grep -q '^Smoke: FAIL$' "$TMP/preflight-fail/out/$TAG.md"
check "5. preflight-fail: never Smoke: PASS" bash -c "! grep -q '^Smoke: PASS' '$TMP/preflight-fail/out/$TAG.md'"
check "5. preflight-fail: report names runner error" grep -q "emulator boot timeout" "$TMP/preflight-fail/out/$TAG.md"

# ==============================================================================
# Flag pass-through, download, and AVD logic (#610, #640)
# ==============================================================================

# (a) --allow-focus reaches mac only when given
run a1 -- --platform mac --allow-focus
check "a: --allow-focus reaches the mac runner" grep -q -- '--allow-focus' "$TMP/a1/calls.mac"
run a2 -- --platform mac
check "a: absent without the flag" bash -c "! grep -q -- --allow-focus '$TMP/a2/calls.mac'"

# (b) env var does the same
run b CCTV_SMOKE_ALLOW_FOCUS=1 -- --platform mac
check "b: CCTV_SMOKE_ALLOW_FOCUS=1 passes --allow-focus" grep -q -- '--allow-focus' "$TMP/b/calls.mac"

# (c) --apk/--serial reach android, no download
run c -- --platform android --apk /x/my.apk --serial emu-1
check "c: android gets --apk and --serial" bash -c "grep -q -- '--apk /x/my.apk' '$TMP/c/calls.android' && grep -q -- '--serial emu-1' '$TMP/c/calls.android'"
check "c: gh release download not called" bash -c "! grep -q 'release download' '$TMP/c/calls.gh' 2>/dev/null"

# (d) default: download the tag's asset and hand it to android
run d -- --platform android
check "d: gh release download <tag> --pattern couch-tour-<tag>.apk" grep -Eq "^release download $TAG .*--pattern couch-tour-$TAG.apk" "$TMP/d/calls.gh"
check "d: android gets the downloaded --apk" grep -q -- "--apk $TMP/d/out/$TAG/couch-tour-$TAG.apk" "$TMP/d/calls.android"
check "d: exit 0" test "$RC" = 0

# (e) failed download: exit 2, android not run, reason names the download
run e GH_FAIL=1 -- --platform both
check "e: exit 2" test "$RC" = 2
check "e: android runner not invoked" bash -c "[[ ! -s '$TMP/e/calls.android' ]]"
check "e: mac still ran" called e mac
check "e: report names the download" grep -q "could not download couch-tour-$TAG.apk" "$TMP/e/out/$TAG.md"

# (f) --build-apk: no --apk, no gh call
run f -- --platform android --build-apk
check "f: android gets no --apk" bash -c "! grep -q -- --apk '$TMP/f/calls.android'"
check "f: no gh call" bash -c "[[ ! -s '$TMP/f/calls.gh' ]] || ! grep -q 'release download' '$TMP/f/calls.gh'"

# (g) --apk + --build-apk is a usage error
run g -- --platform android --apk /x/a.apk --build-apk
check "g: exit 1" test "$RC" = 1

# (h) mac-only never downloads
run h -- --platform mac
check "h: no gh release download" bash -c "! grep -q 'release download' '$TMP/h/calls.gh' 2>/dev/null"

# (i) --avd / CCTV_SMOKE_AVD reach android only
run i -- --platform android --apk /x/a.apk --avd phishin_test
check "i: android gets --avd" grep -q -- '--avd phishin_test' "$TMP/i/calls.android"
run i2 CCTV_SMOKE_AVD=envavd -- --platform android --apk /x/a.apk
check "i: CCTV_SMOKE_AVD passes --avd" grep -q -- '--avd envavd' "$TMP/i2/calls.android"
run i3 -- --platform android --apk /x/a.apk
check "i: absent without the flag" bash -c "! grep -q -- --avd '$TMP/i3/calls.android'"

# (j) run-android.sh --avd boot logic with stub adb + emulator
avd_run() { # name [VAR=val...] -- args; stderr in $TMP/<name>.err
  local name="$1"; shift; local envs=()
  while [[ "$1" != -- ]]; do envs+=("$1"); shift; done; shift
  rm -f "$TMP/booted" "$TMP/bootprop" "$TMP/emulator.args" "$TMP/emulator.pid"
  env PATH="$TMP/bin:$PATH" CCTV_SMOKE_EMULATOR="$TMP/emulator" CCTV_SMOKE_AVD_TIMEOUT=6 ${envs[@]+"${envs[@]}"} \
    "$DIR/run-android.sh" --apk "$TMP/none.apk" "$@" >/dev/null 2>"$TMP/$name.err"
  RC=$?
}
avd_run j1 -- --avd phishin_test
check "j1: boots emulator headless with the AVD" grep -q -- '-avd phishin_test -no-window -no-audio -no-snapshot-save' "$TMP/emulator.args"
check "j1: uses the emulator serial" grep -q "booted as emulator-5554" "$TMP/j1.err"
check "j1: emulator killed on exit" bash -c "! kill -0 \$(cat '$TMP/emulator.pid') 2>/dev/null"
avd_run j2 EMU_NOBOOT=1 -- --avd phishin_test
check "j2: boot timeout exits 2 with ERROR" bash -c "[[ $RC == 2 ]] && grep -q 'ERROR:.*did not finish booting' '$TMP/j2.err'"
check "j2: emulator killed after timeout" bash -c "! kill -0 \$(cat '$TMP/emulator.pid') 2>/dev/null"
avd_run j3 PHONE=1 -- --avd phishin_test
check "j3: physical device not used" bash -c "! grep -q R5CT123 '$TMP/j3.err'"
avd_run j4 EMU_EXIT=1 -- --avd phishin_test
check "j4: emulator dying exits 2" bash -c "[[ $RC == 2 ]] && grep -q 'ERROR:.*exited before booting' '$TMP/j4.err'"
avd_run j5 CCTV_SMOKE_EMULATOR=/nonexistent -- --avd phishin_test
check "j5: missing emulator binary exits 2" bash -c "[[ $RC == 2 ]] && grep -q 'ERROR:.*emulator binary not found' '$TMP/j5.err'"

# (k) mac::click_retry: a click that fails once then succeeds must not fail the journey (#712)
click_retry_run() { rm -f "$TMP/click.count"; env FN="$(awk '/^mac::click_retry\(\)/{p=1} p{print} p&&/^}/{exit}' "$DIR/run-mac.sh")" COUNT="$TMP/click.count" FAILS="$1" bash -c '
    smoke::log() { :; }
    mac::click() { n=$(( $(cat "$COUNT" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$COUNT"; (( n > FAILS )); }
    eval "$FN"
    SMOKE_CLICK_RETRY_DELAY=0
    mac::click_retry sidebar.nav.search; echo "$? $(cat "$COUNT")"
  ' 2>/dev/null; }
check "k1: click fails once then succeeds -> ok on attempt 2" test "$(click_retry_run 1)" = "0 2"
check "k2: click always fails -> gives up after 3 attempts" test "$(click_retry_run 99)" = "1 3"
check "k3: both journeys use mac::click_retry for the search nav" bash -c "! grep -q 'if ! mac::click \"sidebar.nav.search\"' '$DIR/run-mac.sh'"
check "k4: mac::click_retry is callable without calling mac::click first" bash -c '
  eval "$(sed -n "/^mac::click()/,/^mac::type()/p" "'"$DIR"'/run-mac.sh" | sed "/^mac::type()/d")"
  smoke::log() { :; }
  mac::click() { return 0; }
  mac::click_retry test_id
'

# (l) Sync round trip forwards --serial and --avd, and keeps emulator alive across both (#741)
run l1 -- --platform both --serial emu-123 --sync
check "l1: sync gets --serial" grep -q -- '--serial emu-123' "$TMP/l1/calls.sync"
check "l1: android gets --serial" grep -q -- '--serial emu-123' "$TMP/l1/calls.android"

run l2 -- --platform both --avd phishin_test --sync
check "l2: android gets --serial" grep -q -- '--serial emulator-5554' "$TMP/l2/calls.android"
check "l2: android gets --avd" grep -q -- '--avd phishin_test' "$TMP/l2/calls.android"
check "l2: sync gets --serial" grep -q -- '--serial emulator-5554' "$TMP/l2/calls.sync"
check "l2: sync gets --avd" grep -q -- '--avd phishin_test' "$TMP/l2/calls.sync"
check "l2: emulator was running during run" test -f "$TMP/l2/calls.sync"
check "l2: emulator killed on exit" bash -c "! kill -0 \$(cat '$TMP/emulator.pid') 2>/dev/null"

# --help lists the new flags
help="$("$DIR/run-smoke.sh" --help)"
for flag in --allow-focus --apk --serial --avd --build-apk; do
  check "help lists $flag" grep -q -- "$flag" <<< "$help"
done

[[ $failures -eq 0 ]] && echo "all ok" || { echo "$failures failure(s)"; exit 1; }
