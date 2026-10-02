#!/usr/bin/env bash
# Fails when mockup/placeholder markers appear in shipping code (#444).
# REPO_ROOT overrides the repo root (used by test_check_mockup_data.sh).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO_ROOT="${REPO_ROOT:-$(cd "$here/../.." && pwd)}"
exec python3 "$here/check_mockup_data.py"
