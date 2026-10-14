#!/usr/bin/env bash
# scripts/test_promote_beta.sh
# Hermetic unit tests for scripts/promote-beta.sh smoke gate and escape hatch (#358).
# Runs against fake git and gh binaries in a temporary repo directory; no network.
set -euo pipefail

failures=0
ok() { printf 'ok   - %s\n' "$1"; }
bad() { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMOTE_SCRIPT="$SCRIPT_DIR/promote-beta.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Set up test environment
BIN_DIR="$TMP/bin"
mkdir -p "$BIN_DIR"

GH_LOG="$TMP/gh.log"
GIT_LOG="$TMP/git.log"

cat > "$BIN_DIR/git" << 'EOF'
#!/bin/sh
echo "$@" >> "$GIT_LOG"
case "$1" in
    fetch) exit 0 ;;
    rev-parse) exit 0 ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/git"

cat > "$BIN_DIR/gh" << 'EOF'
#!/bin/sh
echo "$@" >> "$GH_LOG"
case "$1 $2" in
    "workflow run") exit 0 ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/gh"

export PATH="$BIN_DIR:$PATH"
export GIT_LOG GH_LOG

# Helper to run promote-beta.sh in a clean test harness
run_promote() {
    local test_dir="$1"; shift
    local reports_dir="$test_dir/smoke-reports"
    mkdir -p "$reports_dir"
    : > "$GH_LOG"
    : > "$GIT_LOG"
    SMOKE_REPORTS_DIR="$reports_dir" bash "$PROMOTE_SCRIPT" "$@"
}

TAG="v0.90"
NEXT="v0.91"
NOTES="Release notes"

# 1. Missing report -> exit 1 and no gh workflow run
T1="$TMP/t1"
mkdir -p "$T1"
rc=0
err_out="$T1/err.log"
run_promote "$T1" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>"$err_out" || rc=$?
check "1. missing report exits non-zero" [ "$rc" -ne 0 ]
check "1. missing report records no dispatch" [ ! -s "$GH_LOG" ]
check "1. missing report error names checked path" grep -q "smoke-reports/$TAG.md" "$err_out"

# 2. Empty report -> exit 1 and no gh workflow run
T2="$TMP/t2"
mkdir -p "$T2/smoke-reports"
touch "$T2/smoke-reports/$TAG.md"
rc=0
err_out="$T2/err.log"
run_promote "$T2" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>"$err_out" || rc=$?
check "2. empty report exits non-zero" [ "$rc" -ne 0 ]
check "2. empty report records no dispatch" [ ! -s "$GH_LOG" ]
check "2. empty report error mentions empty" grep -q "empty" "$err_out"

# 3. Report tagged for a different tag -> exit 1 and no dispatch
T3="$TMP/t3"
mkdir -p "$T3/smoke-reports"
cat > "$T3/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.89
Smoke: PASS
EOF
rc=0
err_out="$T3/err.log"
run_promote "$T3" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>"$err_out" || rc=$?
check "3. wrong tag exits non-zero" [ "$rc" -ne 0 ]
check "3. wrong tag records no dispatch" [ ! -s "$GH_LOG" ]
check "3. wrong tag error explains mismatch" grep -q "tagged for 'v0.89', expected 'v0.90'" "$err_out"

# 4. Verdict FAIL -> exit 1 and no dispatch
T4="$TMP/t4"
mkdir -p "$T4/smoke-reports"
cat > "$T4/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: FAIL
EOF
rc=0
err_out="$T4/err.log"
run_promote "$T4" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>"$err_out" || rc=$?
check "4. verdict FAIL exits non-zero" [ "$rc" -ne 0 ]
check "4. verdict FAIL records no dispatch" [ ! -s "$GH_LOG" ]
check "4. verdict FAIL error explains Smoke: FAIL" grep -q "recorded Smoke: FAIL" "$err_out"

# 5. Verdict PASS -> dispatches with expected --ref / inputs
T5="$TMP/t5"
mkdir -p "$T5/smoke-reports"
cat > "$T5/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
rc=0
run_promote "$T5" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>&1 || rc=$?
check "5. verdict PASS exits 0" [ "$rc" -eq 0 ]
check "5. verdict PASS dispatches workflow" grep -q "workflow run build-debug-apk.yml --ref v0.90" "$GH_LOG"
check "5. verdict PASS passes release_tag" grep -q "release_tag=v0.91" "$GH_LOG"
check "5. verdict PASS passes prerelease=false" grep -q "prerelease=false" "$GH_LOG"
check "5. verdict PASS passes side_install=false" grep -q "side_install=false" "$GH_LOG"

# 6. PASS plus Waived: lines -> dispatches
T6="$TMP/t6"
mkdir -p "$T6/smoke-reports"
cat > "$T6/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
Waived: journey-flake - known timing issue

| id | platform | status | evidence |
|---|---|---|---|
| `journey-flake` | mac | FAIL | timeout |
EOF
rc=0
run_promote "$T6" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>&1 || rc=$?
check "6. PASS with Waived lines exits 0" [ "$rc" -eq 0 ]
check "6. PASS with Waived lines dispatches workflow" grep -q "workflow run build-debug-apk.yml --ref v0.90" "$GH_LOG"

# 6b. PASS with UNWAIVED FAIL journey -> exits non-zero
T6b="$TMP/t6b"
mkdir -p "$T6b/smoke-reports"
cat > "$T6b/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS

| id | platform | status | evidence |
|---|---|---|---|
| `unwaived-journey` | mac | FAIL | crash |
EOF
rc=0
run_promote "$T6b" "$TAG" "$NEXT" "$NOTES" >/dev/null 2>&1 || rc=$?
check "6b. PASS with unwaived FAIL journey exits non-zero" [ "$rc" -ne 0 ]

# 7. Missing report plus --force-no-smoke -> dispatches AND appends exactly one line to log
T7="$TMP/t7"
mkdir -p "$T7/smoke-reports"
log_file="$T7/smoke-reports/.forced-promotions.log"
rc=0
run_promote "$T7" --force-no-smoke "$TAG" "$NEXT" "$NOTES" >/dev/null 2>&1 || rc=$?
check "7. missing report + --force-no-smoke exits 0" [ "$rc" -eq 0 ]
check "7. missing report + --force-no-smoke dispatches" grep -q "workflow run build-debug-apk.yml --ref v0.90" "$GH_LOG"
check "7. forced-promotion log file created" [ -f "$log_file" ]
check "7. forced-promotion log has exactly one line" [ "$(wc -l < "$log_file" | tr -d ' ')" = "1" ]
check "7. forced-promotion log matches format" grep -q -E "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z forced v0.90 -> v0.91 by .+$" "$log_file"

# 8. PASS plus --force-no-smoke -> dispatches and log is unchanged (not created/written)
T8="$TMP/t8"
mkdir -p "$T8/smoke-reports"
cat > "$T8/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
log_file8="$T8/smoke-reports/.forced-promotions.log"
rc=0
run_promote "$T8" --force-no-smoke "$TAG" "$NEXT" "$NOTES" >/dev/null 2>&1 || rc=$?
check "8. PASS + --force-no-smoke exits 0" [ "$rc" -eq 0 ]
check "8. PASS + --force-no-smoke dispatches" grep -q "workflow run build-debug-apk.yml --ref v0.90" "$GH_LOG"
check "8. PASS + --force-no-smoke writes no log" [ ! -f "$log_file8" ]

# 9. --check-only flag
rc_good=0
SMOKE_REPORTS_DIR="$T5/smoke-reports" bash "$PROMOTE_SCRIPT" --check-only "$TAG" >/dev/null 2>&1 || rc_good=$?
check "9. --check-only exits 0 for valid report" [ "$rc_good" -eq 0 ]

rc_bad=0
SMOKE_REPORTS_DIR="$T4/smoke-reports" bash "$PROMOTE_SCRIPT" --check-only "$TAG" >/dev/null 2>&1 || rc_bad=$?
check "9. --check-only exits non-zero for FAIL report" [ "$rc_bad" -ne 0 ]

if [ "$failures" -gt 0 ]; then
    echo "$failures test(s) failed." >&2
    exit 1
fi
echo "All promote-beta tests passed."
