#!/usr/bin/env bash
# scripts/smoke/test-run-smoke.sh
# Headless test of run-smoke.sh's flag pass-through to the platform runners (#610) and of the
# release-APK download. Stub runners and a stub gh record their argv; no device, app or network.

set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
TAG="v9.99"
failures=0

ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

# Stub runner: logs argv to $STUB_LOG.<plat>, writes one PASS line to --out.
for plat in mac android; do
  cat > "$TMP/run-$plat" << STUB
#!/usr/bin/env bash
echo "\$*" >> "$TMP/calls.$plat"
out=""; while [[ \$# -gt 0 ]]; do [[ "\$1" == --out ]] && out="\$2"; shift; done
printf '$plat\tlaunch-cold-start\tPASS\tstub\n' > "\$out"
STUB
done
# Stub gh: logs argv; 'release download' creates the asset unless GH_FAIL=1; lists are empty.
cat > "$TMP/gh" << STUB
#!/usr/bin/env bash
echo "\$*" >> "$TMP/calls.gh"
case "\$1 \$2" in
  "release download")
    [[ "\${GH_FAIL:-}" == 1 ]] && { echo "release not found" >&2; exit 1; }
    dir=""; while [[ \$# -gt 0 ]]; do [[ "\$1" == --dir ]] && dir="\$2"; shift; done
    : > "\$dir/couch-tour-$TAG.apk" ;;
  "issue list") echo '[]' ;;
esac
STUB
chmod +x "$TMP"/run-mac "$TMP"/run-android "$TMP"/gh

# run <name> [VAR=val...] -- <run-smoke args...>; exit code in $RC, calls in $TMP/<name>/calls.*
run() {
  local name="$1"; shift; local envs=()
  while [[ "$1" != -- ]]; do envs+=("$1"); shift; done; shift
  mkdir -p "$TMP/$name"; rm -f "$TMP"/calls.*
  env CCTV_SMOKE_RUN_MAC="$TMP/run-mac" CCTV_SMOKE_RUN_ANDROID="$TMP/run-android" CCTV_SMOKE_GH="$TMP/gh" \
      ${envs[@]+"${envs[@]}"} "$DIR/run-smoke.sh" --tag "$TAG" --no-file-bugs --no-sync --out "$TMP/$name/out" "$@" \
      >"$TMP/$name/stdout" 2>"$TMP/$name/stderr"
  RC=$?
  for f in "$TMP"/calls.*; do [[ -e "$f" ]] && mv "$f" "$TMP/$name/$(basename "$f")"; done
}
called() { [[ -s "$TMP/$1/calls.$2" ]]; }
argv() { cat "$TMP/$1/calls.$2" 2>/dev/null; }

# (a) --allow-focus reaches mac only when given
run a1 -- --platform mac --allow-focus
check "a: --allow-focus reaches the mac runner" grep -q -- '--allow-focus' "$TMP/a1/calls.mac"
run a2 -- --platform mac
check "a: absent without the flag" bash -c "! grep -q -- --allow-focus '$TMP/a2/calls.mac'"

# (b) env var does the same
run b CCTV_SMOKE_ALLOW_FOCUS=1 -- --platform mac
check "b: CCTV_SMOKE_ALLOW_FOCUS=1 passes --allow-focus" grep -q -- '--allow-focus' "$TMP/b/calls.mac"

# (c) --apk/--serial reach android, no download
run c -- --platform android --apk /x/my.apk --serial emu-1
check "c: android gets --apk and --serial" bash -c "grep -q -- '--apk /x/my.apk' '$TMP/c/calls.android' && grep -q -- '--serial emu-1' '$TMP/c/calls.android'"
check "c: gh release download not called" bash -c "! grep -q 'release download' '$TMP/c/calls.gh' 2>/dev/null"

# (d) default: download the tag's asset and hand it to android
run d -- --platform android
check "d: gh release download <tag> --pattern couch-tour-<tag>.apk" grep -Eq "^release download $TAG .*--pattern couch-tour-$TAG.apk" "$TMP/d/calls.gh"
check "d: android gets the downloaded --apk" grep -q -- "--apk $TMP/d/out/$TAG/couch-tour-$TAG.apk" "$TMP/d/calls.android"
check "d: exit 0" test "$RC" = 0

# (e) failed download: exit 2, android not run, reason names the download
run e GH_FAIL=1 -- --platform both
check "e: exit 2" test "$RC" = 2
check "e: android runner not invoked" bash -c "[[ ! -s '$TMP/e/calls.android' ]]"
check "e: mac still ran" called e mac
check "e: report names the download" grep -q "could not download couch-tour-$TAG.apk" "$TMP/e/out/$TAG.md"

# (f) --build-apk: no --apk, no gh call
run f -- --platform android --build-apk
check "f: android gets no --apk" bash -c "! grep -q -- --apk '$TMP/f/calls.android'"
check "f: no gh call" bash -c "[[ ! -s '$TMP/f/calls.gh' ]] || ! grep -q 'release download' '$TMP/f/calls.gh'"

# (g) --apk + --build-apk is a usage error
run g -- --platform android --apk /x/a.apk --build-apk
check "g: exit 1" test "$RC" = 1

# (h) mac-only never downloads
run h -- --platform mac
check "h: no gh release download" bash -c "! grep -q 'release download' '$TMP/h/calls.gh' 2>/dev/null"

# --help lists the new flags
help="$("$DIR/run-smoke.sh" --help)"
for flag in --allow-focus --apk --serial --build-apk; do
  check "help lists $flag" grep -q -- "$flag" <<< "$help"
done

[[ $failures -eq 0 ]] && echo "all ok" || { echo "$failures failure(s)"; exit 1; }
