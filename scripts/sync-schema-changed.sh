#!/usr/bin/env bash
# Prints "true" if sync/schema.sql changed between HEAD^ and HEAD, else "false".
# `git diff --name-only` prints repo-root paths (sync/schema.sql) no matter the cwd, so an exact
# match on "schema.sql" never hits when the workflow runs from sync/ — that silently skipped
# every prod migration, including favorite_artists (#570). --relative makes paths cwd-relative.
# Run from sync/ (as sync-deploy.yml does).
set -euo pipefail
if git diff --name-only --relative HEAD^ HEAD -- schema.sql | grep -qx schema.sql; then
  echo true
else
  echo false
fi
