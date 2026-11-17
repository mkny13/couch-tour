#!/usr/bin/env bash
# Runs shellcheck over shell scripts in the repository (#805, part of #801).
# Catches shell syntax errors, quoting mistakes, and array indexing ambiguities (e.g. SC1087).
# TARGET_DIR or explicit file arguments override what is scanned (used by test_check_shellcheck.sh).
set -euo pipefail

if ! command -v shellcheck >/dev/null 2>&1; then
  echo "FAIL: shellcheck command not found on PATH" >&2
  exit 1
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

severity="${SHELLCHECK_SEVERITY:-error}"

if [ "$#" -gt 0 ]; then
  exec shellcheck --severity="$severity" "$@"
fi

files=()
if [ -n "${TARGET_DIR:-}" ]; then
  while IFS= read -r -d '' file; do
    files+=("$file")
  done < <(find "$TARGET_DIR" -type f -name "*.sh" -print0 | sort -z)
else
  search_dirs=("$repo_root/scripts")
  if [ -d "$repo_root/macos/scripts" ]; then
    search_dirs+=("$repo_root/macos/scripts")
  fi
  while IFS= read -r -d '' file; do
    files+=("$file")
  done < <(find "${search_dirs[@]}" -type f -name "*.sh" -print0 | sort -z)
fi

if [ "${#files[@]}" -eq 0 ]; then
  echo "No shell scripts found to check"
  exit 0
fi

exec shellcheck --severity="$severity" "${files[@]}"
