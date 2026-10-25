#!/usr/bin/env bash
# Guards #757: the production APK is built with assembleDebug, whose SYNC_BASE_URL defaults
# to staging (D314). The workflow must pass -PsyncProd=true for non-side-install builds, and
# the side-install (beta) branch must not.
#
# Fails if build.gradle.kts lacks the syncProd switch, or the workflow's else-branch of the
# SIDE_INSTALL check doesn't set it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$REPO_ROOT/app/build.gradle.kts"
WORKFLOW="$REPO_ROOT/.github/workflows/build-debug-apk.yml"

grep -q 'findProperty("syncProd") == "true"' "$GRADLE" \
    || { echo "FAIL: build.gradle.kts has no syncProd switch for the debug SYNC_BASE_URL" >&2; exit 1; }

# The -PsyncProd=true line must sit after the `else` that follows the sideInstall check.
awk '
  /-PsideInstall=true/ { seen_side=1 }
  seen_side && /^[[:space:]]*else[[:space:]]*$/ { in_else=1 }
  in_else && /-PsyncProd=true/ { ok=1 }
  in_else && /^[[:space:]]*fi[[:space:]]*$/ { in_else=0 }
  END { exit ok ? 0 : 1 }
' "$WORKFLOW" \
    || { echo "FAIL: $WORKFLOW does not pass -PsyncProd=true for non-side-install (production) builds" >&2; exit 1; }

echo "PASS: production APK builds are pointed at the prod sync backend"
