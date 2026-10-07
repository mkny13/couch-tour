#!/usr/bin/env bash
# scripts/smoke/test_mac_library_phishin_playlists.sh
# Headless test (#547): the real mac_run_library_phishin_playlists from run-mac.sh runs against a
# stubbed accessibility tree. Local library.row.* ids must not pass the journey; a
# library.row.account-playlist-* id must. Needs no app.

set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failures=0
ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }

# Pull just the journey function out of the runner (the runner itself executes on source).
fn="$(awk '/^mac_run_library_phishin_playlists\(\)/{p=1} p{print} p&&/^}/{exit}' "$DIR/run-mac.sh")"
[[ -n "$fn" ]] || { bad "could not extract mac_run_library_phishin_playlists from run-mac.sh"; exit 1; }

RESULT=""
smoke::require_journeys_file() { :; }
smoke::log() { :; }
smoke::result() { RESULT="$3|$4"; }
mac_screenshot() { :; }
mac::click() { return 0; }
mac::ax_query() {
  local target_id="$1"
  local id
  for id in $IDS; do
    if [[ "$id" == "$target_id" ]] || [[ "$id" == "$target_id."* ]]; then
      echo "$id"
    fi
  done
}
mac::wait_for_id() {
  local target_id="$1"
  local id
  for id in $IDS; do
    if [[ "$id" == "$target_id" ]] || [[ "$id" == "$target_id."* ]]; then
      echo "$id"
      return 0
    fi
  done
  return 1
}
TIMEOUT=1 DEEP=1 NO_INPUT=false
eval "$fn"

run() { # <name> <want status> <ids>
  IDS="$3" RESULT=""
  mac_run_library_phishin_playlists
  if [[ "${RESULT%%|*}" == "$2" ]]; then ok "$1: $2"; else bad "$1: want $2, got $RESULT"; fi
}

run "no rows" SKIP "sidebar.favorites.row.stub library.screen"
run "local-only rows" SKIP "sidebar.favorites.row.stub library.screen library.row.playlist-abc library.row.track-1 library.row.liked-9"
run "account playlist row" PASS "sidebar.favorites.row.stub library.screen library.row.playlist-abc library.row.account-playlist-my-list"

(( failures == 0 )) || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
