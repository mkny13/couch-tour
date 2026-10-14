#!/usr/bin/env bash
# check_hook_paths.sh
#
# Guards against the cwd-relative hook path regression (#656/#667). Hook commands
# in .claude/settings.json must anchor to $CLAUDE_PROJECT_DIR (which Claude Code
# sets to the project root regardless of the working directory) instead of a bare
# relative path like `python3 .claude/hooks/...`. When the session's cwd is a
# subdirectory, a relative invocation makes python3 unable to open the script and
# exit 2; because these are PreToolUse hooks, that blocks every matching tool call
# and wedges the session.
#
# This check emulates a non-root cwd: it exports $CLAUDE_PROJECT_DIR (as Claude
# Code does), cds into a subdirectory, and runs each configured hook command via
# `bash -c` with a minimal stdin payload, requiring exit 0. A self-test also
# confirms that the OLD cwd-relative form fails from a subdirectory, proving the
# check actually catches the regression.
#
# REPO_ROOT overrides the repo root (used by the self-test).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="${REPO_ROOT:-$(cd "$here/.." && pwd)}"
settings="$repo_root/.claude/settings.json"

if [ ! -f "$settings" ]; then
  echo "FAIL: settings file not found at $settings" >&2
  exit 1
fi

# Must be parseable JSON, or downstream extraction silently no-ops.
python3 - "$settings" <<'PY' || { echo "FAIL: $settings is not valid JSON" >&2; exit 1; }
import json, sys
json.load(open(sys.argv[1]))
PY

# 1. The fix: commands must reference $CLAUDE_PROJECT_DIR.
if ! grep -q 'CLAUDE_PROJECT_DIR' "$settings"; then
  echo "FAIL: $settings does not use \$CLAUDE_PROJECT_DIR for hook paths" >&2
  exit 1
fi

# 2. And must NOT contain cwd-relative invocations (the regression).
if grep -qE 'python3 \.claude/hooks' "$settings"; then
  echo "FAIL: $settings still uses cwd-relative hook invocations" >&2
  exit 1
fi

# Emulate Claude Code, which sets $CLAUDE_PROJECT_DIR to the project root.
export CLAUDE_PROJECT_DIR="$repo_root"

# A minimal PreToolUse-style payload consumed by protected_files.py / pre_tool_use.py.
stdin_payload='{"tool_name":"Bash","tool_input":{"command":"true"}}'

# Subdirectories a session might `cd` into. A cwd-relative path would die here.
subdirs=(
  "app/src/main/java"
  "macos/Packages/CouchTourKit"
)

# 3. Run each configured hook command from a subdirectory; require exit 0.
fail=0
while IFS= read -r cmd; do
  [ -z "$cmd" ] && continue
  for sub in "${subdirs[@]}"; do
    target="$repo_root/$sub"
    if [ ! -d "$target" ]; then
      echo "skip: subdir $sub does not exist in this checkout" >&2
      continue
    fi
    rc=0
    ( cd "$target" && printf '%s' "$stdin_payload" | bash -c "$cmd" >/dev/null 2>&1 ) || rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "FAIL: hook '$cmd' exited $rc from subdir $sub (expected 0)" >&2
      fail=1
    fi
  done
done < <(python3 - "$settings" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
for event in s.get("hooks", {}).values():
    for group in event:
        for h in group.get("hooks", []):
            if h.get("type") == "command" and h.get("command"):
                print(h["command"])
PY
)

# 3b. Self-test: the OLD cwd-relative form must FAIL from a subdirectory, proving
#     this check would catch a regression to relative paths.
if [ -z "${CHECK_HOOK_PATHS_NO_SELFTEST:-}" ]; then
  for sub in "${subdirs[@]}"; do
    target="$repo_root/$sub"
    [ -d "$target" ] || continue
    # A bare relative path cannot resolve from a subdir -> python3 exits 2.
    rc=0
    ( cd "$target" && printf '%s' "$stdin_payload" | bash -c 'python3 .claude/hooks/heartbeat.py' >/dev/null 2>&1 ) || rc=$?
    if [ "$rc" -eq 0 ]; then
      echo "FAIL: self-test expected cwd-relative path to fail from $sub but it succeeded" >&2
      fail=1
    fi
    break
  done
fi

exit "$fail"
