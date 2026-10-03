#!/usr/bin/env bash
# scripts/smoke/test_sync_roundtrip.sh
# Headless test of sync-roundtrip.sh orchestration: stub runners and a stub reset script stand in
# for the Mac beta, the Android device, and the staging Worker. Needs no hardware or network.

set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
failures=0
ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

# Stub runner: exit code for "<platform> <step>" comes from $STUB_RC_<platform>_<step> (default 0,
# with '-' as '_'); exit 2 for every step when $STUB_PREFLIGHT_<platform> is set.
for plat in mac android; do
  cat > "$TMP/run-$plat.sh" << STUB
#!/usr/bin/env bash
step=""; while [[ \$# -gt 0 ]]; do [[ "\$1" == --sync-step ]] && step="\$2"; shift; done
[[ -z "\${STUB_PREFLIGHT_$plat:-}" ]] || { echo "ERROR: $plat not ready" >&2; exit 2; }
echo "$plat \$step" >> "$TMP/calls"
v="STUB_RC_${plat}_\${step//-/_}"; exit "\${!v:-0}"
STUB
  chmod +x "$TMP/run-$plat.sh"
done
cat > "$TMP/reset.sh" << STUB
#!/usr/bin/env bash
echo "reset \$*" >> "$TMP/calls"; exit "\${STUB_RESET_RC:-0}"
STUB
chmod +x "$TMP/reset.sh"

# run <name> [VAR=val...] -> exit code in $RC, results in $TMP/<name>.tsv, calls in $TMP/calls
run() {
  local name="$1"; shift; : > "$TMP/calls"
  env CCTV_SMOKE_RUN_MAC="$TMP/run-mac.sh" CCTV_SMOKE_RUN_ANDROID="$TMP/run-android.sh" \
      CCTV_SMOKE_SYNC_RESET="$TMP/reset.sh" "$@" \
      "$DIR/sync-roundtrip.sh" --tag t --out "$TMP/$name.tsv" --timeout 5 >/dev/null 2>"$TMP/$name.err"
  RC=$?
}
status() { awk -F'\t' -v id="$2" '$2==id{print $3}' "$TMP/$1.tsv"; }
reset_called() { grep -qx 'reset --yes' "$TMP/calls"; }

# 1. Successful round trip: four PASS lines, reset after the last step.
run happy
check "happy: exit 0" test "$RC" = 0
for id in favorite-syncs-android-to-mac favorite-syncs-mac-to-android in-progress-syncs-android-to-mac in-progress-syncs-mac-to-android; do
  check "happy: $id PASS" test "$(status happy $id)" = PASS
done
check "happy: all lines use platform 'sync'" test "$(cut -f1 "$TMP/happy.tsv" | sort -u)" = sync
check "happy: staging reset ran with --yes" reset_called
check "happy: reset ran last" test "$(tail -1 "$TMP/calls")" = "reset --yes"

# 2. Android->Mac favorite never arrives (the #351 shape): that direction FAILs, the dependent
#    Mac->Android removal SKIPs, progress directions are unaffected, and the reset still runs.
run fav-timeout STUB_RC_mac_favorite_present=3
check "fav-timeout: exit 0 (report decides)" test "$RC" = 0
check "fav-timeout: android->mac FAIL" test "$(status fav-timeout favorite-syncs-android-to-mac)" = FAIL
check "fav-timeout: evidence names the timeout" grep -q 'not satisfied within 5s' "$TMP/fav-timeout.tsv"
check "fav-timeout: dependent direction SKIP" test "$(status fav-timeout favorite-syncs-mac-to-android)" = SKIP
check "fav-timeout: progress android->mac still PASS" test "$(status fav-timeout in-progress-syncs-android-to-mac)" = PASS
check "fav-timeout: reset ran" reset_called

# 3. Per-direction attribution: only the Mac->Android removal times out.
run rm-timeout STUB_RC_android_favorite_absent=3
check "rm-timeout: android->mac PASS" test "$(status rm-timeout favorite-syncs-android-to-mac)" = PASS
check "rm-timeout: mac->android FAIL" test "$(status rm-timeout favorite-syncs-mac-to-android)" = FAIL

# 4. Missing control (exit 4) is a SKIP, never a PASS or FAIL.
run unavailable STUB_RC_android_progress_start=4
check "unavailable: SKIP" test "$(status unavailable in-progress-syncs-android-to-mac)" = SKIP
check "unavailable: no PASS for that direction" test "$(status unavailable in-progress-syncs-android-to-mac)" != PASS

# 5. Action failure is a FAIL attributed to the action.
run action-fail STUB_RC_android_favorite_add=1
check "action-fail: FAIL" test "$(status action-fail favorite-syncs-android-to-mac)" = FAIL
check "action-fail: evidence names the action" grep -q "android action 'favorite-add'" "$TMP/action-fail.tsv"

# 6. Preflight failure: exit 2, later directions SKIP, teardown still runs.
run preflight STUB_PREFLIGHT_mac=1
check "preflight: exit 2" test "$RC" = 2
check "preflight: reset still ran" reset_called
check "preflight: no PASS lines" test "$(grep -c "	PASS	" "$TMP/preflight.tsv")" = 0

# 7. Reset failure after a clean run surfaces as exit 2.
run reset-fail STUB_RESET_RC=1
check "reset-fail: exit 2" test "$RC" = 2
check "reset-fail: error names the staging group" grep -q 'staging reset failed' "$TMP/reset-fail.err"

# 8. Usage errors exit 1 and never touch staging.
: > "$TMP/calls"
CCTV_SMOKE_SYNC_RESET="$TMP/reset.sh" "$DIR/sync-roundtrip.sh" --bogus >/dev/null 2>&1; rc=$?
check "usage: unknown flag exits 1" test "$rc" = 1
check "usage: reset not called" test ! -s "$TMP/calls"

# 9. Result lines are accepted by the report writer.
"$DIR/report.sh" --results "$TMP/happy.tsv" --tag t --out "$TMP/report.md" --commit testsha >/dev/null 2>&1
check "report.sh accepts sync result lines" grep -q '^Smoke: PASS$' "$TMP/report.md"

if [[ $failures -eq 0 ]]; then echo "All sync-roundtrip tests passed."; else echo "$failures failure(s)."; exit 1; fi
