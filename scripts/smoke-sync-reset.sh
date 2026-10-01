#!/usr/bin/env bash
# Empties the staging D1 database (progress, seqs, pairings, devices, groups)
# for smoke tests.
#
# Hard-guarded: refuses to run against any database other than
# couch-tour-sync-staging, and requires an explicit --yes to perform the remote write.
# Defaults to a dry run printing the commands to execute.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DATABASE="couch-tour-sync-staging"
CONFIRMED=false
DRY_RUN=false

usage() {
    echo "Usage: $0 [--database=<name>] [--yes] [--dry-run]"
    echo ""
    echo "Options:"
    echo "  --database=<name>  Target database (must be 'couch-tour-sync-staging')"
    echo "  --yes              Perform the remote reset (default is dry run)"
    echo "  --dry-run          Explicitly request a dry run"
    echo "  -h, --help         Show this help message"
    exit 1
}

# Read prod database name and id from sync/wrangler.toml for hard guard
WRANGLER_TOML="$REPO_ROOT/sync/wrangler.toml"
PROD_DB_NAME="couch-tour-sync"
PROD_DB_ID="20840aeb-15d2-4dd8-ba5a-c1e78233cea2"

if [[ -f "$WRANGLER_TOML" ]]; then
    parsed_name="$(grep -E '^[[:space:]]*database_name[[:space:]]*=' "$WRANGLER_TOML" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
    parsed_id="$(grep -E '^[[:space:]]*database_id[[:space:]]*=' "$WRANGLER_TOML" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
    [[ -n "$parsed_name" ]] && PROD_DB_NAME="$parsed_name"
    [[ -n "$parsed_id" ]] && PROD_DB_ID="$parsed_id"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database=*)
            DATABASE="${1#*=}"
            shift
            ;;
        --database)
            DATABASE="$2"
            shift 2
            ;;
        --db=*)
            DATABASE="${1#*=}"
            shift
            ;;
        --db)
            DATABASE="$2"
            shift 2
            ;;
        --yes)
            CONFIRMED=true
            shift
            ;;
        --dry-run|-n)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            if [[ "$1" != -* && "$DATABASE" == "couch-tour-sync-staging" ]]; then
                DATABASE="$1"
                shift
            else
                echo "Unknown option: $1" >&2
                usage
            fi
            ;;
    esac
done

# Hard guard: refuse to run against prod database name, prod database id, or anything other than staging
if [[ "$DATABASE" == "$PROD_DB_NAME" || "$DATABASE" == "$PROD_DB_ID" ]]; then
    echo "ERROR: Refusing to run against production database '$DATABASE'!" >&2
    exit 1
fi

if [[ "$DATABASE" != "couch-tour-sync-staging" ]]; then
    echo "ERROR: Refusing to run against database '$DATABASE'. Only 'couch-tour-sync-staging' is permitted." >&2
    exit 1
fi

SQL_COMMAND="DELETE FROM progress; DELETE FROM seqs; DELETE FROM pairings; DELETE FROM devices; DELETE FROM groups;"

if [[ "$CONFIRMED" != "true" || "$DRY_RUN" == "true" ]]; then
    echo "Dry run: smoke sync reset for $DATABASE"
    echo "Command to execute:"
    echo "  wrangler d1 execute $DATABASE --remote --command=\"$SQL_COMMAND\""
    echo ""
    echo "Pass --yes to execute remotely against $DATABASE."
    exit 0
fi

echo "Resetting staging sync database: $DATABASE..."
cd "$REPO_ROOT/sync"
if command -v wrangler >/dev/null 2>&1; then
    wrangler d1 execute "$DATABASE" --remote --command="$SQL_COMMAND"
else
    npx wrangler d1 execute "$DATABASE" --remote --command="$SQL_COMMAND"
fi
echo "Staging database $DATABASE reset successfully."
