#!/usr/bin/env bash
# scripts/smoke/test_home_sections_after_relaunch.sh
# Headless test (#679): the real home-sections-after-relaunch journeys from run-mac.sh and
# run-android.sh run against stubbed query layers. Covers a present/missing section, a timed-out
# AX walk (must say so, not just "missing"), and an Android section that only appears after a
# scroll. Needs no app or device.

set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failures=0
ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }

extract() { awk -v n="$2" '$0 ~ "^"n"\\(\\)"{p=1} p{print} p&&/^}/{exit}' "$DIR/$1"; }
mac_fn="$(extract run-mac.sh mac_run_home_sections_after_relaunch)"
and_fn="$(extract run-android.sh android_run_home_sections_after_relaunch)"
[[ -n "$mac_fn" && -n "$and_fn" ]] || { bad "could not extract journey functions"; exit 1; }

RESULT=""
smoke::require_journeys_file() { :; }
smoke::log() { :; }
smoke::result() { RESULT="$1|$3|$4"; }
mac_screenshot() { :; }
android_screenshot() { :; }
TIMEOUT=1 DEEP=14 NO_INPUT=false MAC_AX_TIMED_OUT=0

# --- mac stubs: IDS = present ids, TIMED_OUT_IDS = ids whose lookup ends in a timed-out walk.
mac::relaunch() { return 0; }
mac::ax_query() { [[ " $IDS " == *" $1 "* ]] && echo "$1"; }
mac::wait_for_id() {
  WAIT_TIMEOUTS+=" $2"
  MAC_AX_TIMED_OUT=0
  [[ " $IDS " == *" $1 "* ]] && return 0
  [[ " ${TIMED_OUT_IDS:-} " == *" $1 "* ]] && MAC_AX_TIMED_OUT=1
  return 1
}
eval "$mac_fn"

all="home.in_progress home.next_tour_stops home.on_this_date home.in_progress.card"
mrun() { # <name> <want status> <want evidence substring> <ids> [timed-out ids]
  IDS="$4" TIMED_OUT_IDS="${5:-}" RESULT=""
  mac_run_home_sections_after_relaunch
  if [[ "$RESULT" == "mac|$2|"*"$3"* ]]; then ok "$1"; else bad "$1: got $RESULT"; fi
}
mrun "mac all present" PASS "" "$all"
mrun "mac missing on_this_date" FAIL "home.on_this_date" "home.in_progress home.next_tour_stops home.in_progress.card"
mrun "mac timed-out walk says so" FAIL "AX walk timed out" "home.in_progress home.in_progress.card" "home.next_tour_stops home.on_this_date"
# Walk budget: every section lookup gets the larger Home budget, not the 20 s default (#716)
WAIT_TIMEOUTS="" mrun "mac budget" PASS "" "$all"
[[ " ${WAIT_TIMEOUTS# } " != *" 1 "* && "$WAIT_TIMEOUTS" == *" 60"* ]] && ok "mac home walk budget >= 60s" || bad "mac home walk budget: '$WAIT_TIMEOUTS'"
# SKIP path: no in-progress card before relaunch
mrun "mac no fixture" SKIP "seeded-favorite" ""

# --- android stubs: SCROLLED flips to 1 after android_scroll_to; HIDDEN_UNTIL_SCROLL ids need it.
state=0
android::dump() { :; }
android::count() {
  local t="$1"
  [[ " $ABSENT " == *" $t "* ]] && { echo 0; return; }
  [[ " ${LATE:-} " == *" $t "* && "$state" -eq 0 ]] && { echo 0; return; }
  echo 1
}
android::relaunch() { state=0; }
android::wait_for_tag() { [[ "$(android::count "$1")" -gt 0 ]]; }
android_scroll_to() { state=1; [[ "$(android::count "$1")" -gt 0 ]]; }
eval "$and_fn"

arun() { # <name> <want status> <want evidence substring>
  state=0 RESULT=""
  android_run_home_sections_after_relaunch
  if [[ "$RESULT" == "android|$2|"*"$3"* ]]; then ok "$1"; else bad "$1: got $RESULT"; fi
}
ABSENT="" LATE="home.section.on-this-date"; arun "android appears after a scroll" PASS ""
ABSENT="home.section.on-this-date" LATE=""; arun "android never appears" FAIL "home.section.on-this-date"
ABSENT="home.section.in-progress" LATE=""; arun "android no in-progress fixture" SKIP "fixture"

(( failures == 0 )) || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
