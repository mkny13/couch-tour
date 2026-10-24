#!/usr/bin/env bash
# Fails when the XCUITest suite test count documented in README.md does not match the
# count of test methods in macos/CouchTourUITests/CouchTourUITests.swift.
#
# README_FILE and TEST_FILE override the paths (used by test_check_xcuitest_count.sh).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

readme="${README_FILE:-$repo_root/README.md}"
test_file="${TEST_FILE:-$repo_root/macos/CouchTourUITests/CouchTourUITests.swift}"

if [[ ! -f "$readme" ]]; then
  echo "FAIL: README not found at $readme" >&2
  exit 1
fi

if [[ ! -f "$test_file" ]]; then
  echo "FAIL: test file not found at $test_file" >&2
  exit 1
fi

# Extract documented count from README.md, e.g. "XCUITest suite (11 tests)"
documented_count="$(sed -nE 's/.*XCUITest suite \(([0-9]+) tests\).*/\1/p' "$readme" | head -n 1)"
if [[ -z "$documented_count" ]]; then
  echo "FAIL: could not parse XCUITest count from $readme" >&2
  exit 1
fi

actual_count="$(grep -c 'func test' "$test_file" || true)"

if [[ "$documented_count" -ne "$actual_count" ]]; then
  echo "FAIL: README.md documents $documented_count XCUITest tests, but $test_file contains $actual_count tests" >&2
  exit 1
fi

echo "OK: README.md and $test_file agree on $actual_count tests"
exit 0
