#!/usr/bin/env bash
# scripts/smoke/sync-roundtrip.sh
# Two-client sync round trip against the isolated staging sync group (#359): an action on one
# client, an assertion on the other, in both directions, for favorites and listening progress.
# Emits one result line per direction (platform "sync") in the lib.sh contract format.
#
# Usage: scripts/smoke/sync-roundtrip.sh [--tag <tag>] [--out <file>] [--timeout <s>] [--no-reset]
#
# Assumes both betas are already installed and paired to the staging group (pairing is not
# automated). Teardown always runs scripts/smoke-sync-reset.sh --yes, even when a step fails,
# so the group is left as found. Reset wipes the staging group's pairings too: re-pair afterwards.
#
# Exit codes: 0 = ran (the result lines decide pass/fail), 1 = usage error,
# 2 = a platform could not be driven or the staging reset failed.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/smoke/lib.sh
source "${SCRIPT_DIR}/lib.sh"

# Overridable so the orchestration can be tested with stubs (test_sync_roundtrip.sh).
RUN_MAC="${CCTV_SMOKE_RUN_MAC:-$SCRIPT_DIR/run-mac.sh}"
RUN_ANDROID="${CCTV_SMOKE_RUN_ANDROID:-$SCRIPT_DIR/run-android.sh}"
RESET="${CCTV_SMOKE_SYNC_RESET:-$SCRIPT_DIR/../smoke-sync-reset.sh}"

TAG=""; OUT_FILE=""; TIMEOUT=30; DO_RESET=true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) [[ -n "${2:-}" ]] || smoke::die 1 "--tag requires a value"; TAG="$2"; shift 2 ;;
    --out) [[ -n "${2:-}" ]] || smoke::die 1 "--out requires a file"; OUT_FILE="$2"; CCTV_SMOKE_RESULTS="$2"; export CCTV_SMOKE_RESULTS; shift 2 ;;
    --timeout) [[ "${2:-}" =~ ^[0-9]+$ ]] || smoke::die 1 "--timeout requires seconds"; TIMEOUT="$2"; shift 2 ;;
    --no-reset) DO_RESET=false; shift ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) smoke::die 1 "Unknown argument: $1" ;;
  esac
done
[[ -z "$OUT_FILE" ]] || : > "$OUT_FILE"

ABORT=""   # set to a reason when a platform can't be driven (exit 2 after teardown)

teardown() {
  local rc=$?
  trap - EXIT
  if [[ "$DO_RESET" == true ]]; then
    smoke::log "Teardown: resetting the staging sync group"
    if ! "$RESET" --yes >&2; then
      echo "ERROR: staging reset failed; the staging group may hold test rows" >&2
      [[ $rc -ne 0 ]] || rc=2
    fi
  fi
  [[ -z "$ABORT" || $rc -ne 0 ]] || { echo "ERROR: $ABORT" >&2; rc=2; }
  exit $rc
}
trap teardown EXIT

# step <mac|android> <step> -> echoes OK | TIMEOUT | UNAVAILABLE | ABORT (runner exit 2) | ERROR.
# Runs in a subshell, so the caller (not step) records the abort.
step() {
  local plat="$1" name="$2" runner rc=0
  runner="$RUN_MAC"; [[ "$plat" == android ]] && runner="$RUN_ANDROID"
  "$runner" --tag "${TAG:-dev}" --timeout "$TIMEOUT" --sync-step "$name" >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0) echo OK ;;
    "$SMOKE_STEP_TIMEOUT") echo TIMEOUT ;;
    "$SMOKE_STEP_UNAVAILABLE") echo UNAVAILABLE ;;
    2) echo ABORT ;;
    *) echo ERROR ;;
  esac
}

# direction <id> <action-plat> <action-step> <assert-plat> <assert-step> [depends-on-id]
# Records PASS/FAIL/SKIP for one direction; evidence is identifier-level only.
declare -a FAILED_IDS=()
direction() {
  local id="$1" ap="$2" as="$3" bp="$4" bs="$5" dep="${6:-}" r
  [[ -z "$ABORT" ]] || { smoke::result sync "$id" SKIP "not run: $ABORT"; return 0; }
  if [[ -n "$dep" ]] && printf '%s\n' ${FAILED_IDS[@]+"${FAILED_IDS[@]}"} | grep -qx "$dep"; then
    smoke::result sync "$id" SKIP "needs state from $dep, which failed"; return 0
  fi
  r="$(step "$ap" "$as")"
  [[ "$r" != ABORT ]] || ABORT="$ap runner failed preflight during sync step '$as'"
  case "$r" in
    OK) ;;
    UNAVAILABLE) smoke::result sync "$id" SKIP "$ap $as: control or fixture unavailable (see $ap runner log)"; return 0 ;;
    ABORT) smoke::result sync "$id" SKIP "not run: $ABORT"; return 0 ;;
    *) FAILED_IDS+=("$id"); smoke::result sync "$id" FAIL "$ap action '$as' did not complete ($r)"; return 0 ;;
  esac
  r="$(step "$bp" "$bs")"
  [[ "$r" != ABORT ]] || ABORT="$bp runner failed preflight during sync step '$bs'"
  case "$r" in
    OK) smoke::result sync "$id" PASS "$ap '$as' observed on $bp via '$bs' within ${TIMEOUT}s" ;;
    TIMEOUT) FAILED_IDS+=("$id"); smoke::result sync "$id" FAIL "$bp '$bs' not satisfied within ${TIMEOUT}s of $ap '$as'" ;;
    UNAVAILABLE) smoke::result sync "$id" SKIP "$bp $bs: control or fixture unavailable (see $bp runner log)" ;;
    ABORT) smoke::result sync "$id" SKIP "not run: $ABORT" ;;
    *) FAILED_IDS+=("$id"); smoke::result sync "$id" FAIL "$bp '$bs' errored ($r)" ;;
  esac
}

direction favorite-syncs-android-to-mac android favorite-add    mac     favorite-present
direction favorite-syncs-mac-to-android mac     favorite-remove android favorite-absent favorite-syncs-android-to-mac
direction in-progress-syncs-android-to-mac android progress-start mac     progress-present
direction in-progress-syncs-mac-to-android mac     progress-clear  android progress-absent in-progress-syncs-android-to-mac
exit 0
