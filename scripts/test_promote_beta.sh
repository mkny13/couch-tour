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
    tag)
        if [ -f "$TMP/mock_tags" ]; then
            cat "$TMP/mock_tags"
        else
            echo "v0.90"
        fi
        exit 0
        ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/git"

cat > "$BIN_DIR/gh" << 'EOF'
#!/bin/sh
echo "$@" >> "$GH_LOG"
case "$1 $2" in
    "workflow run") exit 0 ;;
    "release view")
        if [ -f "$TMP/mock_release_prerelease" ]; then
            cat "$TMP/mock_release_prerelease"
        else
            echo "true"
        fi
        exit 0
        ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/gh"

export PATH="$BIN_DIR:$PATH"
export GIT_LOG GH_LOG TMP

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

# 10. Single argument (beta tag only): auto-computes next tag and notes, dispatches on PASS
T10="$TMP/t10"
mkdir -p "$T10/smoke-reports"
cat > "$T10/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
echo "v0.90" > "$TMP/mock_tags"
rc=0
run_promote "$T10" "$TAG" >/dev/null 2>&1 || rc=$?
check "10. single-arg promote exits 0 on PASS" [ "$rc" -eq 0 ]
check "10. single-arg promote dispatches with next tag v0.91" grep -q "release_tag=v0.91" "$GH_LOG"
check "10. single-arg promote dispatches with ref v0.90" grep -q "workflow run build-debug-apk.yml --ref v0.90" "$GH_LOG"
check "10. single-arg promote sets prerelease=false" grep -q "prerelease=false" "$GH_LOG"
check "10. single-arg promote sets side_install=false" grep -q "side_install=false" "$GH_LOG"

# 11. --auto with passing smoke report: auto-detects beta and promotes
T11="$TMP/t11"
mkdir -p "$T11/smoke-reports"
cat > "$T11/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
echo "v0.90" > "$TMP/mock_tags"
echo "true" > "$TMP/mock_release_prerelease"
rc=0
run_promote "$T11" --auto >/dev/null 2>&1 || rc=$?
check "11. --auto exits 0 on PASS" [ "$rc" -eq 0 ]
check "11. --auto dispatches promotion" grep -q "workflow run build-debug-apk.yml --ref v0.90" "$GH_LOG"
check "11. --auto passes bumped release_tag v0.91" grep -q "release_tag=v0.91" "$GH_LOG"

# 12. --auto with failing smoke report: does not promote, beta stays a beta, exits 0
T12="$TMP/t12"
mkdir -p "$T12/smoke-reports"
cat > "$T12/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: FAIL
EOF
echo "v0.90" > "$TMP/mock_tags"
echo "true" > "$TMP/mock_release_prerelease"
rc=0
out12="$T12/out.log"
run_promote "$T12" --auto >"$out12" 2>&1 || rc=$?
check "12. --auto exits 0 when report fails" [ "$rc" -eq 0 ]
check "12. --auto records NO dispatch on FAIL" bash -c "! grep -q 'workflow run' '$GH_LOG'"
check "12. --auto message notes beta stays a beta" grep -q "stays a beta" "$out12"

# 13. --auto with no smoke report: does not promote, beta stays a beta, exits 0
T13="$TMP/t13"
mkdir -p "$T13/smoke-reports"
echo "v0.90" > "$TMP/mock_tags"
echo "true" > "$TMP/mock_release_prerelease"
rc=0
out13="$T13/out.log"
run_promote "$T13" --auto >"$out13" 2>&1 || rc=$?
check "13. --auto exits 0 when report missing" [ "$rc" -eq 0 ]
check "13. --auto records NO dispatch when report missing" bash -c "! grep -q 'workflow run' '$GH_LOG'"
check "13. --auto message notes report not found / beta stays a beta" grep -q "stays a beta" "$out13"

# 14. --auto when latest release is already production: does nothing, exits 0
T14="$TMP/t14"
mkdir -p "$T14/smoke-reports"
cat > "$T14/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
echo "v0.90" > "$TMP/mock_tags"
echo "false" > "$TMP/mock_release_prerelease"
rc=0
out14="$T14/out.log"
run_promote "$T14" --auto >"$out14" 2>&1 || rc=$?
check "14. --auto exits 0 when release already prod" [ "$rc" -eq 0 ]
check "14. --auto records NO dispatch when release already prod" bash -c "! grep -q 'workflow run' '$GH_LOG'"

# 15. --auto rejects --force-no-smoke: auto-promotion MUST NOT bypass smoke gate
T15="$TMP/t15"
mkdir -p "$T15/smoke-reports"
rc=0
err15="$T15/err.log"
run_promote "$T15" --auto --force-no-smoke >/dev/null 2>"$err15" || rc=$?
check "15. --auto rejects --force-no-smoke" [ "$rc" -ne 0 ]
check "15. --auto rejection error mentions cannot be combined" grep -q "cannot be combined" "$err15"

if [ "$failures" -gt 0 ]; then
    echo "$failures test(s) failed." >&2
    exit 1
fi
echo "All promote-beta tests passed."
