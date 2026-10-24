#!/usr/bin/env bash
# Self-test for check-xcuitest-count.sh: verifies that check-xcuitest-count.sh
# passes when counts match, fails when they disagree, and fails on missing files.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-xcuitest-count.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 1. Matching mock files should pass
printf 'XCUITest suite (2 tests)\n' > "$tmp/README.md"
printf 'func testOne() {}\nfunc testTwo() {}\n' > "$tmp/CouchTourUITests.swift"
README_FILE="$tmp/README.md" TEST_FILE="$tmp/CouchTourUITests.swift" "$check" >/dev/null || {
  echo "FAIL: matching counts should pass" >&2
  exit 1
}

# 2. Mismatched mock files should fail
printf 'XCUITest suite (3 tests)\n' > "$tmp/README.md"
if README_FILE="$tmp/README.md" TEST_FILE="$tmp/CouchTourUITests.swift" "$check" 2>/dev/null; then
  echo "FAIL: mismatched counts should fail" >&2
  exit 1
fi

# 3. Missing count pattern in README should fail
printf 'Some other readme content without test count\n' > "$tmp/README.md"
if README_FILE="$tmp/README.md" TEST_FILE="$tmp/CouchTourUITests.swift" "$check" 2>/dev/null; then
  echo "FAIL: unparseable README should fail" >&2
  exit 1
fi

# 4. Missing files should fail
if README_FILE="$tmp/nonexistent.md" TEST_FILE="$tmp/CouchTourUITests.swift" "$check" 2>/dev/null; then
  echo "FAIL: missing README file should fail" >&2
  exit 1
fi
if README_FILE="$tmp/README.md" TEST_FILE="$tmp/nonexistent.swift" "$check" 2>/dev/null; then
  echo "FAIL: missing test file should fail" >&2
  exit 1
fi

echo "ok"
