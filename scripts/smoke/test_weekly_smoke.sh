#!/usr/bin/env bash
# scripts/smoke/test_weekly_smoke.sh
# Hermetic unit tests for scripts/smoke/weekly-smoke.sh (#358).
set -euo pipefail

failures=0
ok() { printf 'ok   - %s\n' "$1"; }
bad() { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEEKLY_SCRIPT="$SCRIPT_DIR/weekly-smoke.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BIN_DIR="$TMP/bin"
mkdir -p "$BIN_DIR"
STATE_DIR="$TMP/state"
mkdir -p "$STATE_DIR"

GIT_LOG="$TMP/git.log"

cat > "$BIN_DIR/git" << 'EOF'
#!/bin/sh
echo "$@" >> "$GIT_LOG"
case "$1 $2" in
    "tag --list"*)
        echo "v0.88"
        exit 0
        ;;
    "diff --name-only"*)
        if [ -f "$STATE_DIR/app_changed" ]; then
            echo "app/src/main/java/dev/mike/couchtour/MainActivity.kt"
        fi
        exit 0
        ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/git"

cat > "$BIN_DIR/mahler" << 'EOF'
#!/bin/sh
case "$*" in
    *desktop*run*)
        if [ -f "$STATE_DIR/gate_busy" ]; then
            exit 75
        fi
        shift 5 # skip mahler desktop run --scheduled --
        exec "$@"
        ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/mahler"

export PATH="$BIN_DIR:$PATH"
export GIT_LOG STATE_DIR

# 1. No app code changed: tests existing tag
rm -f "$STATE_DIR/app_changed"
out1="$TMP/out1.log"
bash "$WEEKLY_SCRIPT" --no-gate --dry-run >"$out1" 2>&1
check "1. no app changes mentions existing beta" grep -q "No app code changes since v0.88" "$out1"
check "1. no app changes runs smoke on existing beta" grep -q "run-smoke.sh --tag v0.88" "$out1"

# 2. App code changed: detects change, cuts beta
touch "$STATE_DIR/app_changed"
out2="$TMP/out2.log"
bash "$WEEKLY_SCRIPT" --no-gate --dry-run >"$out2" 2>&1
check "2. app changes detects modified app code" grep -q "App code changed since v0.88" "$out2"
check "2. app changes plans cut-beta" grep -q "Would cut new beta" "$out2"

# 3. Explicit tag flag
out3="$TMP/out3.log"
bash "$WEEKLY_SCRIPT" --no-gate --dry-run --tag v0.80 >"$out3" 2>&1
check "3. explicit tag uses specified tag" grep -q "Using explicit tag: v0.80" "$out3"
check "3. explicit tag runs smoke for that tag" grep -q "run-smoke.sh --tag v0.80" "$out3"

# 4. Gate busy with --no-retry exits 75
touch "$STATE_DIR/gate_busy"
rc=0
bash "$WEEKLY_SCRIPT" --no-retry >/dev/null 2>&1 || rc=$?
check "4. gate busy with --no-retry exits 75" [ "$rc" -eq 75 ]

if [ "$failures" -gt 0 ]; then
    echo "$failures test(s) failed." >&2
    exit 1
fi
echo "All weekly-smoke tests passed."
