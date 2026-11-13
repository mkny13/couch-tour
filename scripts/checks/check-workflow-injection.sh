#!/usr/bin/env bash
# Fails when a workflow script interpolates untrusted expressions (${{ github.event.* }},
# ${{ inputs.* }}, ${{ github.head_ref }}) directly into a run: block (#805, part of #801).
# Untrusted values must be passed via step-level env: to prevent workflow injection.
# WORKFLOWS_DIR overrides the directory (used by test_check_workflow_injection.sh).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dir="${WORKFLOWS_DIR:-$here/../../.github/workflows}"

python3 - "$dir" << 'PYEOF'
import glob
import os
import re
import sys

PATTERN = re.compile(r"\$\{\{\s*(?:github\.event\b|inputs\b|github\.head_ref\b)")
RUN_KEY = re.compile(r"^(?:-\s+)?run\s*:\s*(.*)$")
BLOCK_SCALAR = re.compile(r"^(?:[|>][0-9+-]*)?(?:\s*#.*)?$")

dir_path = sys.argv[1]
workflow_files = sorted(glob.glob(os.path.join(dir_path, "*.yml")) + glob.glob(os.path.join(dir_path, "*.yaml")))

if not workflow_files:
    print(f"WARN: no workflow files found in {dir_path}", file=sys.stderr)
    sys.exit(0)

fail = False
for f in workflow_files:
    try:
        with open(f, encoding="utf-8") as fp:
            lines = fp.readlines()
    except OSError as e:
        print(f"ERROR: could not read {f}: {e}", file=sys.stderr)
        fail = True
        continue

    in_run = False
    run_indent = 0
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        indent = len(line) - len(line.lstrip(" "))
        if not stripped:
            continue
        if in_run:
            if indent <= run_indent and not stripped.startswith("#"):
                in_run = False
            else:
                if PATTERN.search(line):
                    print(f"FAIL: {f}:{i}: workflow injection hazard in run block: {stripped}", file=sys.stderr)
                    fail = True
        if not in_run and not stripped.startswith("#"):
            m = RUN_KEY.match(line.lstrip(" "))
            if m:
                rest = m.group(1).strip()
                if PATTERN.search(rest):
                    print(f"FAIL: {f}:{i}: workflow injection hazard in run block: {stripped}", file=sys.stderr)
                    fail = True
                if BLOCK_SCALAR.match(rest):
                    in_run = True
                    run_indent = line.index("run")

if fail:
    sys.exit(1)
PYEOF
