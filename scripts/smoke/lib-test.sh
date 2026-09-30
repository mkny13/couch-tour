#!/bin/bash
# scripts/smoke/lib-test.sh
# Verification test for scripts/smoke/lib.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/smoke/lib.sh
source "$SCRIPT_DIR/lib.sh"

echo "Running lib.sh tests..."

# 1. smoke::result mac x PASS y writes exactly mac\tx\tPASS\ty
RES=$(smoke::result mac x PASS y)
EXPECTED=$(printf 'mac\tx\tPASS\ty')
if [[ "$RES" != "$EXPECTED" ]]; then
  echo "FAIL: smoke::result output mismatch. Got '$RES', expected '$EXPECTED'" >&2
  exit 1
fi
echo "PASS: smoke::result output exact match"

# 1b. smoke::result writes to CCTV_SMOKE_RESULTS
TMP_RESULTS=$(mktemp)
trap 'rm -f "$TMP_RESULTS"' EXIT
CCTV_SMOKE_RESULTS="$TMP_RESULTS" smoke::result android browse-artists-to-artist PASS "artist screen opened" >/dev/null
FILE_CONTENT=$(cat "$TMP_RESULTS")
EXPECTED_FILE=$(printf 'android\tbrowse-artists-to-artist\tPASS\tartist screen opened')
if [[ "$FILE_CONTENT" != "$EXPECTED_FILE" ]]; then
  echo "FAIL: CCTV_SMOKE_RESULTS content mismatch. Got '$FILE_CONTENT', expected '$EXPECTED_FILE'" >&2
  exit 1
fi
echo "PASS: smoke::result appends to CCTV_SMOKE_RESULTS"

# 2. smoke::preflight_adb against a CCTV_SMOKE_SERIAL that cannot exist exits 2
set +e
( CCTV_SMOKE_SERIAL="nonexistent-device-serial-xyz-999" smoke::preflight_adb ) 2>/dev/null
EXIT_CODE=$?
set -e
if [[ $EXIT_CODE -ne 2 ]]; then
  echo "FAIL: smoke::preflight_adb expected exit 2, got $EXIT_CODE" >&2
  exit 1
fi
echo "PASS: smoke::preflight_adb nonexistent serial exits 2"

# 3. smoke::require_journeys_file bogus-id exits non-zero
set +e
( smoke::require_journeys_file "bogus-id" ) 2>/dev/null
EXIT_CODE=$?
set -e
if [[ $EXIT_CODE -eq 0 ]]; then
  echo "FAIL: smoke::require_journeys_file bogus-id expected non-zero, got 0" >&2
  exit 1
fi
echo "PASS: smoke::require_journeys_file bogus-id exits non-zero ($EXIT_CODE)"

# 4. smoke::require_journeys_file valid id succeeds
smoke::require_journeys_file "launch-cold-start"
smoke::require_journeys_file "search-artist-hit"
echo "PASS: smoke::require_journeys_file valid ids succeed"

echo "All lib.sh tests passed."
