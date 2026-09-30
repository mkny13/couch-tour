#!/usr/bin/env bash
# Tests scripts/smoke-sync-reset.sh:
# - Asserts guard rejects prod database name from sync/wrangler.toml
# - Asserts guard rejects prod database id from sync/wrangler.toml
# - Asserts guard rejects other unknown databases
# - Asserts dry-run emits the expected reset statements against couch-tour-sync-staging
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESET_SCRIPT="$REPO_ROOT/scripts/smoke-sync-reset.sh"
WRANGLER_TOML="$REPO_ROOT/sync/wrangler.toml"

echo "=== Testing smoke-sync-reset.sh ==="

if [[ ! -f "$WRANGLER_TOML" ]]; then
    echo "FAIL: $WRANGLER_TOML not found" >&2
    exit 1
fi

PROD_DB_NAME="$(grep -E '^[[:space:]]*database_name[[:space:]]*=' "$WRANGLER_TOML" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
PROD_DB_ID="$(grep -E '^[[:space:]]*database_id[[:space:]]*=' "$WRANGLER_TOML" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"

if [[ -z "$PROD_DB_NAME" || -z "$PROD_DB_ID" ]]; then
    echo "FAIL: Could not extract prod database_name or database_id from $WRANGLER_TOML" >&2
    exit 1
fi

echo "Extracted prod database name: $PROD_DB_NAME"
echo "Extracted prod database id:   $PROD_DB_ID"

# 1. Guard rejects prod database name
echo "Testing refusal for prod database name..."
set +e
output_name="$("$RESET_SCRIPT" --database="$PROD_DB_NAME" 2>&1)"
exit_name=$?
set -eu
if [[ $exit_name -eq 0 ]]; then
    echo "FAIL: Expected non-zero exit for prod database name '$PROD_DB_NAME', got 0" >&2
    exit 1
fi
if [[ "$output_name" != *"Refusing to run against production database"* ]]; then
    echo "FAIL: Expected refusal message for prod database name, got: $output_name" >&2
    exit 1
fi
echo "  PASS: Prod database name rejected."

# 1b. Guard rejects prod database name even with --yes
echo "Testing refusal for prod database name with --yes..."
set +e
output_name_yes="$("$RESET_SCRIPT" --database="$PROD_DB_NAME" --yes 2>&1)"
exit_name_yes=$?
set -eu
if [[ $exit_name_yes -eq 0 ]]; then
    echo "FAIL: Expected non-zero exit for prod database name with --yes, got 0" >&2
    exit 1
fi
echo "  PASS: Prod database name rejected even with --yes."

# 2. Guard rejects prod database id
echo "Testing refusal for prod database id..."
set +e
output_id="$("$RESET_SCRIPT" --database="$PROD_DB_ID" 2>&1)"
exit_id=$?
set -eu
if [[ $exit_id -eq 0 ]]; then
    echo "FAIL: Expected non-zero exit for prod database id '$PROD_DB_ID', got 0" >&2
    exit 1
fi
if [[ "$output_id" != *"Refusing to run against production database"* ]]; then
    echo "FAIL: Expected refusal message for prod database id, got: $output_id" >&2
    exit 1
fi
echo "  PASS: Prod database id rejected."

# 3. Guard rejects unknown database
echo "Testing refusal for arbitrary non-staging database..."
set +e
output_other="$("$RESET_SCRIPT" --database="random-db-name" 2>&1)"
exit_other=$?
set -eu
if [[ $exit_other -eq 0 ]]; then
    echo "FAIL: Expected non-zero exit for non-staging database, got 0" >&2
    exit 1
fi
if [[ "$output_other" != *"Only 'couch-tour-sync-staging' is permitted"* ]]; then
    echo "FAIL: Expected staging-only message, got: $output_other" >&2
    exit 1
fi
echo "  PASS: Non-staging database rejected."

# 4. Dry run default (no args) emits expected statements
echo "Testing default dry-run output..."
dry_output="$("$RESET_SCRIPT")"
if [[ "$dry_output" != *"couch-tour-sync-staging"* ]]; then
    echo "FAIL: Expected 'couch-tour-sync-staging' in dry run output, got: $dry_output" >&2
    exit 1
fi
if [[ "$dry_output" != *"--remote"* ]]; then
    echo "FAIL: Expected '--remote' in dry run output, got: $dry_output" >&2
    exit 1
fi
expected_sql="DELETE FROM progress; DELETE FROM seqs; DELETE FROM pairings; DELETE FROM devices; DELETE FROM groups;"
if [[ "$dry_output" != *"$expected_sql"* ]]; then
    echo "FAIL: Expected '$expected_sql' in dry run output, got: $dry_output" >&2
    exit 1
fi
echo "  PASS: Dry-run emitted expected commands."

# 5. Explicit --dry-run with --database=couch-tour-sync-staging
echo "Testing explicit --dry-run..."
dry_explicit="$("$RESET_SCRIPT" --database=couch-tour-sync-staging --dry-run)"
if [[ "$dry_explicit" != *"$expected_sql"* ]]; then
    echo "FAIL: Expected '$expected_sql' in explicit dry run, got: $dry_explicit" >&2
    exit 1
fi
echo "  PASS: Explicit dry-run emitted expected commands."

echo "All smoke-sync-reset tests passed!"
