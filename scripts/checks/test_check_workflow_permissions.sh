#!/usr/bin/env bash
# Self-test for check-workflow-permissions.sh: passes on the real workflows, fails on a
# workflow without a top-level permissions block, and ignores a job-level one.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-workflow-permissions.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

"$check" || { echo "FAIL: real workflows should pass" >&2; exit 1; }

printf 'name: ok\non: push\npermissions:\n  contents: read\njobs:\n  a:\n    runs-on: ubuntu-latest\n' > "$tmp/ok.yml"
WORKFLOWS_DIR="$tmp" "$check" || { echo "FAIL: workflow with permissions should pass" >&2; exit 1; }

printf 'name: bad\non: push\njobs:\n  a:\n    runs-on: ubuntu-latest\n' > "$tmp/bad.yml"
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow without permissions should fail" >&2; exit 1
fi

printf 'name: joblevel\non: push\njobs:\n  a:\n    runs-on: ubuntu-latest\n    permissions:\n      contents: read\n' > "$tmp/bad.yml"
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: job-level permissions alone should not satisfy the check" >&2; exit 1
fi
echo "ok"
