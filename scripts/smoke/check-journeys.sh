#!/usr/bin/env bash
# scripts/smoke/check-journeys.sh
# Drift guard: JOURNEYS.md and each runner's ALL_JOURNEYS table must agree per platform, so a
# journey can't silently stop running (or run without being specified).
# Exits 0 when every symmetric difference is empty, 1 otherwise.

set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC="${CCTV_SMOKE_JOURNEYS:-$DIR/JOURNEYS.md}"

[[ -f "$SPEC" ]] || { echo "ERROR: $SPEC not found" >&2; exit 2; }

# spec_ids <platform>: ids whose "- **platforms**:" line mentions the platform.
spec_ids() {
  awk -v plat="$1" '
    /^[[:space:]]*-[[:space:]]*\*\*id\*\*:/ { id=$0; gsub(/^[^:]*:[[:space:]]*`?|`?[[:space:]]*$/, "", id); next }
    /^[[:space:]]*-[[:space:]]*\*\*platforms\*\*:/ {
      p=$0; sub(/^[^:]*:/, "", p); sub(/\(.*/, "", p); gsub(/[`[:space:]]/, "", p)
      n=split(p, a, ",")
      for (i = 1; i <= n; i++) if (a[i] == plat) print id
    }' "$SPEC" | sort -u
}

# runner_ids <file>: entries of the ALL_JOURNEYS=( ... ) array.
runner_ids() {
  awk '/^ALL_JOURNEYS=\(/ {on=1; next} on && /^\)/ {on=0} on {gsub(/[[:space:]]/, ""); if ($0 != "") print}' "$1" | sort -u
}

status=0
for plat in mac android; do
  runner="$DIR/run-$plat.sh"
  spec="$(spec_ids "$plat")"; run="$(runner_ids "$runner")"
  [[ -n "$spec" ]] || { echo "ERROR: no $plat journeys parsed from $SPEC" >&2; status=1; continue; }
  [[ -n "$run" ]] || { echo "ERROR: no ALL_JOURNEYS parsed from $runner" >&2; status=1; continue; }
  missing="$(comm -23 <(echo "$spec") <(echo "$run"))"
  extra="$(comm -13 <(echo "$spec") <(echo "$run"))"
  if [[ -n "$missing" ]]; then
    echo "$plat: in JOURNEYS.md but not in run-$plat.sh:" >&2; echo "$missing" | sed 's/^/  /' >&2; status=1
  fi
  if [[ -n "$extra" ]]; then
    echo "$plat: in run-$plat.sh but not in JOURNEYS.md (for $plat):" >&2; echo "$extra" | sed 's/^/  /' >&2; status=1
  fi
  [[ $status -ne 0 ]] || echo "$plat: $(echo "$spec" | wc -l | tr -d ' ') journeys, spec and runner agree"
done
exit $status
