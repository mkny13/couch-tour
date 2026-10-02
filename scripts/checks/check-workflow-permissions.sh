#!/usr/bin/env bash
# Fails when a workflow has no top-level `permissions:` block (#476). Without one, the
# GITHUB_TOKEN gets the repo default, which can be read-write. A job-level block does not
# count: a newly added job would silently fall back to the default.
# WORKFLOWS_DIR overrides the directory (used by test_check_workflow_permissions.sh).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dir="${WORKFLOWS_DIR:-$here/../../.github/workflows}"
fail=0
shopt -s nullglob
for f in "$dir"/*.yml "$dir"/*.yaml; do
  if ! grep -qE '^permissions:' "$f"; then
    echo "FAIL: $f has no top-level 'permissions:' block" >&2
    fail=1
  fi
done
exit "$fail"
