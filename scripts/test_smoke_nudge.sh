#!/usr/bin/env bash
# scripts/test_smoke_nudge.sh
# Hermetic unit tests for scripts/smoke-nudge.sh weekly staleness check (#358).
# Runs against fake git and gh binaries in a temporary repo directory; no network.
set -euo pipefail

failures=0
ok() { printf 'ok   - %s\n' "$1"; }
bad() { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }
check_not() { local name="$1"; shift; if "$@"; then bad "$name"; else ok "$name"; fi; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NUDGE_SCRIPT="$SCRIPT_DIR/smoke-nudge.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BIN_DIR="$TMP/bin"
mkdir -p "$BIN_DIR"

GH_LOG="$TMP/gh.log"
GIT_LOG="$TMP/git.log"
STATE_DIR="$TMP/state"
mkdir -p "$STATE_DIR"

cat > "$BIN_DIR/git" << 'EOF'
#!/bin/sh
echo "$@" >> "$GIT_LOG"
case "$*" in
    *tag*)
        if [ -f "$STATE_DIR/git_tags" ]; then
            cat "$STATE_DIR/git_tags"
        else
            echo "v0.90"
            echo "v0.89"
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
    "issue list")
        if [ -f "$STATE_DIR/existing_issue" ]; then
            num="$(cat "$STATE_DIR/existing_issue")"
            # Output JSON or tab-separated matching title
            echo "$num	Smoke run due: v0.90	[]	OPEN	2026-10-14T00:00:00Z"
        fi
        exit 0
        ;;
    "issue create")
        echo "42" > "$STATE_DIR/existing_issue"
        echo "https://github.com/mkny13/couch-tour/issues/42"
        exit 0
        ;;
    "issue comment")
        exit 0
        ;;
    *) exit 0 ;;
esac
EOF
chmod +x "$BIN_DIR/gh"

export PATH="$BIN_DIR:$PATH"
export GIT_LOG GH_LOG STATE_DIR

run_nudge() {
    local test_dir="$1"; shift
    local reports_dir="$test_dir/smoke-reports"
    mkdir -p "$reports_dir"
    : > "$GH_LOG"
    : > "$GIT_LOG"
    SMOKE_REPORTS_DIR="$reports_dir" bash "$NUDGE_SCRIPT" "$@"
}

TAG="v0.90"

# 1. Newest tag resolution without --tag (resolves v0.90 from git tag)
T1="$TMP/t1"
mkdir -p "$T1/smoke-reports"
cat > "$T1/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
rc=0
out="$T1/out.log"
run_nudge "$T1" >"$out" 2>&1 || rc=$?
check "1a. tag resolution without --tag exits 0 on passing report" [ "$rc" -eq 0 ]
check "1a. stdout confirms tag resolved and is current" grep -q "Smoke report for v0.90 is current" "$out"
check "1a. git tag was consulted" grep -q "tag" "$GIT_LOG"

# 1b. Explicit --tag flag (uses provided tag, e.g. v0.85)
T1b="$TMP/t1b"
mkdir -p "$T1b/smoke-reports"
cat > "$T1b/smoke-reports/v0.85.md" << 'EOF'
Tag: v0.85
Smoke: PASS
EOF
rc=0
out="$T1b/out.log"
run_nudge "$T1b" --tag v0.85 >"$out" 2>&1 || rc=$?
check "1b. explicit --tag exits 0 when that tag's report is passing" [ "$rc" -eq 0 ]
check "1b. stdout confirms explicit tag" grep -q "Smoke report for v0.85 is current" "$out"

# 2. Report current -> no gh issue call at all
T2="$TMP/t2"
mkdir -p "$T2/smoke-reports"
cat > "$T2/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: PASS
EOF
rc=0
run_nudge "$T2" >/dev/null 2>&1 || rc=$?
check "2. report current exits 0" [ "$rc" -eq 0 ]
check_not "2. report current makes no gh issue calls" grep -q "issue" "$GH_LOG"

# 3. Report missing -> issue created, exits non-zero
T3="$TMP/t3"
mkdir -p "$T3/smoke-reports"
rm -f "$STATE_DIR/existing_issue"
rc=0
out="$T3/out.log"
run_nudge "$T3" >"$out" 2>&1 || rc=$?
check "3. report missing exits non-zero" [ "$rc" -ne 0 ]
check "3. report missing searches for existing issue" grep -q "issue list" "$GH_LOG"
check "3. report missing creates issue" grep -q "issue create" "$GH_LOG"
check "3. created issue has expected title" grep -q -- "Smoke run due: v0.90" "$GH_LOG"
check "3. created issue has mahler label" grep -q -- "--label mahler" "$GH_LOG"
check "3. state recorded issue 42" [ "$(cat "$STATE_DIR/existing_issue")" = "42" ]

# 4. Second run finds the existing issue and comments instead of creating a duplicate
T4="$TMP/t4"
mkdir -p "$T4/smoke-reports"
# $STATE_DIR/existing_issue already holds 42 from previous test
rc=0
run_nudge "$T4" >/dev/null 2>&1 || rc=$?
check "4. second run exits non-zero" [ "$rc" -ne 0 ]
check "4. second run searches existing issue" grep -q "issue list" "$GH_LOG"
check "4. second run comments on issue 42" grep -q "issue comment 42" "$GH_LOG"
check_not "4. second run does NOT create another issue" grep -q "issue create" "$GH_LOG"

# 5. --dry-run -> no gh issue create/comment calls, prints intended action, exits non-zero
T5="$TMP/t5"
mkdir -p "$T5/smoke-reports"
rm -f "$STATE_DIR/existing_issue"
rc=0
out="$T5/out.log"
run_nudge "$T5" --dry-run >"$out" 2>&1 || rc=$?
check "5. --dry-run on stale exits non-zero" [ "$rc" -ne 0 ]
check_not "5. --dry-run makes no gh issue create call" grep -q "issue create" "$GH_LOG"
check_not "5. --dry-run makes no gh issue comment call" grep -q "issue comment" "$GH_LOG"
check "5. --dry-run prints intended action" grep -q "Would create issue titled 'Smoke run due: v0.90'" "$out"

# 6. Verdict FAIL -> issue created/commented, exits non-zero
T6="$TMP/t6"
mkdir -p "$T6/smoke-reports"
cat > "$T6/smoke-reports/$TAG.md" << 'EOF'
Tag: v0.90
Smoke: FAIL
EOF
rm -f "$STATE_DIR/existing_issue"
rc=0
out="$T6/out.log"
run_nudge "$T6" >"$out" 2>&1 || rc=$?
check "6. verdict FAIL exits non-zero" [ "$rc" -ne 0 ]
check "6. verdict FAIL creates issue" grep -q "issue create" "$GH_LOG"

if [ "$failures" -gt 0 ]; then
    echo "$failures test(s) failed." >&2
    exit 1
fi
echo "All smoke-nudge tests passed."
