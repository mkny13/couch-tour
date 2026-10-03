#!/bin/bash
# scripts/smoke/lib.sh
# Shared runner contract and helper library for Couch Tour smoke tests.
#
# This file is sourced by smoke test runners (e.g. run-mac.sh, run-android.sh),
# not executed directly.
#
# Exit-code convention:
#   0 = Complete: ran to completion regardless of verdict (the report decides pass/fail)
#   1 = Usage error (invalid flags, bad arguments)
#   2 = Preflight/setup failure (app not running, no device attached, permission denied)
#
# Note: A failing smoke journey is NOT exit 2. Journey outcomes are reported via
# smoke::result and recorded in the smoke report.

set -euo pipefail

# Directory where lib.sh resides
SMOKE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Log a timestamped message to stderr, and append to $CCTV_SMOKE_LOG if set.
smoke::log() {
  local ts
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  local msg="[$ts] $*"
  if [[ -n "${CCTV_SMOKE_LOG:-}" ]]; then
    mkdir -p "$(dirname "$CCTV_SMOKE_LOG")" 2>/dev/null || true
    printf '%s\n' "$msg" | tee -a "$CCTV_SMOKE_LOG" >&2
  else
    printf '%s\n' "$msg" >&2
  fi
}

# Print a one-line error to stderr and exit with the specified code.
smoke::die() {
  local code="${1:-1}"
  shift || true
  printf 'ERROR: %s\n' "$*" >&2
  if [[ -n "${CCTV_SMOKE_LOG:-}" ]]; then
    mkdir -p "$(dirname "$CCTV_SMOKE_LOG")" 2>/dev/null || true
    local ts
    ts="$(date '+%Y-%m-%d %H:%M:%S')"
    printf '[%s] ERROR: %s\n' "$ts" "$*" >> "$CCTV_SMOKE_LOG" 2>/dev/null || true
  fi
  exit "$code"
}

# Verify that Couch Tour Beta is running and that the invoking process has Accessibility access.
# Exits 2 with the exact remedy when either preflight check fails.
smoke::preflight_macos_beta() {
  local bundle_id="dev.mike.couchtour.mac.beta"
  local running=""

  if command -v lsappinfo >/dev/null 2>&1; then
    running="$(lsappinfo info -app "$bundle_id" 2>/dev/null || true)"
  fi

  if [[ -z "$running" ]]; then
    if ! pgrep -f "Couch Tour Beta" >/dev/null 2>&1; then
      smoke::die 2 "Couch Tour Beta is not running. Launch it from /Applications or run: macos/scripts/install-beta.sh && open -a 'Couch Tour Beta'"
    fi
  fi

  if ! osascript -e 'tell application "System Events" to return true' >/dev/null 2>&1; then
    smoke::die 2 "Accessibility access denied for System Events. Grant Accessibility permission to Terminal/Runner in System Settings -> Privacy & Security -> Accessibility."
  fi
}

# Verify that an Android device is attached in 'device' state.
# Accepts serial via first argument or $CCTV_SMOKE_SERIAL environment variable.
# Exits 2 listing what was seen when no matching device is found.
smoke::preflight_adb() {
  local target_serial="${1:-${CCTV_SMOKE_SERIAL:-}}"
  local adb_bin="adb"

  if ! command -v "$adb_bin" >/dev/null 2>&1; then
    if [[ -x "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb" ]]; then
      adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
    else
      smoke::die 2 "adb command not found on PATH. Install Android platform-tools or set PATH."
    fi
  fi

  local devices_output
  devices_output="$("$adb_bin" devices 2>&1 || true)"

  if [[ -n "$target_serial" ]]; then
    if ! echo "$devices_output" | grep -q -E "^${target_serial}[[:space:]]+device$"; then
      smoke::die 2 "Target Android device '${target_serial}' not found in 'device' state. adb devices output:\n${devices_output}"
    fi
  else
    if ! echo "$devices_output" | grep -q -E "^[^\t ]+[[:space:]]+device$"; then
      smoke::die 2 "No Android device attached in 'device' state. adb devices output:\n${devices_output}"
    fi
  fi
}

# Record a journey result line: <platform>\t<journey-id>\t<status>\t<evidence>
# Appends to $CCTV_SMOKE_RESULTS (default stdout) and writes to stdout (console).
smoke::result() {
  local platform="${1:-}"
  local id="${2:-}"
  local status="${3:-}"
  local evidence="${4:-}"

  if [[ -z "$platform" || -z "$id" || -z "$status" ]]; then
    smoke::die 1 "smoke::result requires <platform> <id> <status> [evidence]"
  fi

  case "$status" in
    PASS|FAIL|SKIP) ;;
    *) smoke::die 1 "Invalid status '$status': must be PASS, FAIL, or SKIP" ;;
  esac

  local line
  line="$(printf '%s\t%s\t%s\t%s' "$platform" "$id" "$status" "$evidence")"

  if [[ -n "${CCTV_SMOKE_RESULTS:-}" ]]; then
    mkdir -p "$(dirname "$CCTV_SMOKE_RESULTS")" 2>/dev/null || true
    printf '%s\n' "$line" >> "$CCTV_SMOKE_RESULTS"
  fi

  printf '%s\n' "$line"
}

# Verify that scripts/smoke/JOURNEYS.md exists, and optionally that a given journey id
# appears in it, preventing runners from inventing unauthorized journey ids.
smoke::require_journeys_file() {
  local id="${1:-}"
  local journeys_file="${CCTV_SMOKE_JOURNEYS:-${SMOKE_LIB_DIR}/JOURNEYS.md}"

  if [[ ! -f "$journeys_file" ]]; then
    smoke::die 2 "JOURNEYS.md not found at $journeys_file"
  fi

  if [[ -n "$id" ]]; then
    if ! grep -q -E "^\s*-\s*\*\*id\*\*:\s*(\`?)${id}(\`?)\s*$" "$journeys_file"; then
      smoke::die 1 "Journey id '${id}' not found in $journeys_file"
    fi
  fi
}

# Sync-step exit codes shared by the runners' --sync-step mode and sync-roundtrip.sh.
# 0 = step satisfied, 3 = assertion timed out (a real FAIL), 4 = the control or fixture the step
# needs is not available (a SKIP, never a PASS). Exit 2 stays "preflight failed".
SMOKE_STEP_TIMEOUT=3
SMOKE_STEP_UNAVAILABLE=4

# Poll `$@` (a command that succeeds once the condition holds) until it does or <timeout>s pass.
# Usage: smoke::poll <timeout> <cmd...>
smoke::poll() {
  local timeout="$1"; shift
  local deadline=$(( $(date +%s) + timeout ))
  while true; do
    if "$@"; then return 0; fi
    [[ "$(date +%s)" -lt "$deadline" ]] || return 1
    sleep 1
  done
}
