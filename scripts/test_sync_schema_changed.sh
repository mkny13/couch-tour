#!/usr/bin/env bash
# Tests scripts/sync-schema-changed.sh against a throwaway repo shaped like this one (sync/ subdir),
# run from sync/ the way sync-deploy.yml does (#570).
set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sync-schema-changed.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q . && git config user.email t@t && git config user.name t
mkdir sync && echo a > sync/schema.sql && echo a > sync/index.ts
git add . && git commit -qm base

echo b > sync/schema.sql && git commit -qam "schema change"
got="$(cd sync && "$SCRIPT")"
[[ "$got" == true ]] || { echo "FAIL: schema change reported '$got', want true" >&2; exit 1; }

echo c > sync/index.ts && git commit -qam "code only"
got="$(cd sync && "$SCRIPT")"
[[ "$got" == false ]] || { echo "FAIL: code-only change reported '$got', want false" >&2; exit 1; }
echo "PASS: sync-schema-changed"
