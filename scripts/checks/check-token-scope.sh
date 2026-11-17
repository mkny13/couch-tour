#!/usr/bin/env bash
# Asserts CLOUDFLARE_API_TOKEN is strictly step-scoped under env: in sync-deploy.yml (#805, part of #801).
# Workflow-level or job-level token exposure allows untrusted or unneeded steps access to deploy secrets.
# SYNC_DEPLOY_WORKFLOW or argument overrides the file to check (used by test_check_token_scope.sh).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workflow="${1:-${SYNC_DEPLOY_WORKFLOW:-$here/../../.github/workflows/sync-deploy.yml}}"

if [ ! -f "$workflow" ]; then
  echo "FAIL: workflow file not found: $workflow" >&2
  exit 1
fi

python3 - "$workflow" << 'PYEOF'
import sys

workflow_file = sys.argv[1]

try:
    with open(workflow_file, encoding="utf-8") as f:
        lines = f.readlines()
except OSError as e:
    print(f"FAIL: could not read {workflow_file}: {e}", file=sys.stderr)
    sys.exit(1)

in_steps = False
in_step_env = False
step_env_indent = 0
errors = []
token_count = 0

for idx, raw_line in enumerate(lines, 1):
    line = raw_line.rstrip()
    stripped = line.strip()
    if not stripped or stripped.startswith("#"):
        continue
    indent = len(line) - len(line.lstrip(" "))

    if "CLOUDFLARE_API_TOKEN" in stripped:
        token_count += 1
        if not in_step_env or indent <= step_env_indent:
            errors.append(f"{workflow_file}:{idx}: CLOUDFLARE_API_TOKEN found outside step env: {stripped}")

    if stripped.startswith("jobs:"):
        in_steps = False
        in_step_env = False
    elif stripped.startswith("steps:"):
        in_steps = True
        in_step_env = False
    elif in_steps:
        if stripped.startswith("- "):
            in_step_env = False
        elif indent <= 4 and not stripped.startswith("env:"):
            in_steps = False
            in_step_env = False

        if stripped.startswith("env:") or stripped.startswith("- env:"):
            in_step_env = True
            step_env_indent = indent
        elif in_step_env and indent <= step_env_indent:
            in_step_env = False

if errors:
    for err in errors:
        print(f"FAIL: {err}", file=sys.stderr)
    sys.exit(1)

if token_count == 0:
    print(f"FAIL: {workflow_file} contains no references to CLOUDFLARE_API_TOKEN", file=sys.stderr)
    sys.exit(1)

PYEOF
