#!/usr/bin/env bash
# Guards against an exported component (MainActivity) forwarding a sync base URL intent
# extra to persistent configuration without a BuildConfig.DEBUG gate (D323).
#
# Fails if:
#   1. EXTRA_SYNC_BASE_URL is forwarded to applyConfiguredBaseUrl directly in MainActivity
#      (bypassing the debug-gated maybeApplyBaseUrlOverride).
#   2. No maybeApplyBaseUrlOverride call is present in MainActivity (the gate is missing).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAIN_ACTIVITY="$REPO_ROOT/app/src/main/java/dev/mike/couchtour/MainActivity.kt"

if [[ ! -f "$MAIN_ACTIVITY" ]]; then
    echo "FAIL: $MAIN_ACTIVITY not found" >&2
    exit 1
fi

# 1. EXTRA_SYNC_BASE_URL must never be forwarded to applyConfiguredBaseUrl in MainActivity
#    without going through the debug-gated maybeApplyBaseUrlOverride. The constant definition
#    and the gated call itself are exempt.
VIOLATIONS=$(grep -n "EXTRA_SYNC_BASE_URL" "$MAIN_ACTIVITY" \
    | grep "applyConfiguredBaseUrl" \
    | grep -v "maybeApplyBaseUrlOverride" \
    || true)

if [[ -n "$VIOLATIONS" ]]; then
    echo "FAIL: EXTRA_SYNC_BASE_URL is forwarded to applyConfiguredBaseUrl without the debug gate:" >&2
    echo "$VIOLATIONS" >&2
    echo "" >&2
    echo "Route intent extras through SyncApi.maybeApplyBaseUrlOverride instead." >&2
    exit 1
fi

# 2. The debug-gated path must be present.
if ! grep -q "maybeApplyBaseUrlOverride" "$MAIN_ACTIVITY"; then
    echo "FAIL: No debug-gated override path (maybeApplyBaseUrlOverride) in MainActivity" >&2
    exit 1
fi

echo "PASS: MainActivity syncBaseUrl override is debug-gated."
