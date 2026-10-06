#!/usr/bin/env bash
# scripts/smoke/test_android_sync_steps.sh
# Headless test of run-android.sh's favorite sync steps (#533): a stub `adb` on PATH simulates a
# Home -> Browse artists -> Artists list UI with uiautomator dumps, so the runner's real
# navigation, scrolling and selectors run with no device. Neither CCTV_SMOKE_ANDROID_FAVORITE_TOGGLE
# nor CCTV_SMOKE_ANDROID_ARTIST_ENTRY_TAG is set: the defaults must be enough.

set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
failures=0
ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

mkdir -p "$TMP/bin" "$TMP/state"
: > "$TMP/apk.apk"
# Stub adb. State lives in $STUB_STATE: screen, hscroll/ascroll (swipes done on Home / Artists),
# fav (file exists when favorited), log (one line per input event). Home's "Browse artists" row
# appears after 2 swipes and the artist's star after 3, so the runner must scroll to find them.
cat > "$TMP/bin/adb" << 'STUB'
#!/usr/bin/env bash
S="$STUB_STATE"
[[ "${1:-}" == -s ]] && shift 2
read_n() { cat "$S/$1" 2>/dev/null || echo 0; }
node() { # <resource-id> <bounds>
  echo "<node index=\"0\" text=\"\" resource-id=\"dev.mike.couchtour.beta:id/$1\" class=\"android.view.View\" content-desc=\"\" bounds=\"$2\" />"
}
case "$1" in
  devices) printf 'List of devices attached\nstub\tdevice\n' ;;
  install|uninstall) echo Success ;;
  exec-out)
    screen="$(cat "$S/screen")"
    echo "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
    node nav.home "[50,2186][200,2300]"
    if [[ "$screen" == home ]]; then
      [[ -e "$S/fav" ]] && node "favorites.row.$STUB_KEY" "[40,1950][200,2000]"
      (( $(read_n hscroll) >= 2 && $(read_n hscroll) <= 5 )) && node home.browse-artists "[0,1000][1080,1100]"
    else
      node artists.list "[0,200][1080,2100]"
      (( $(read_n ascroll) >= 3 )) && node "artists.favorite.$STUB_KEY" "[900,1000][1000,1100]"
    fi
    echo "</hierarchy>" ;;
  shell)
    case "$2" in
      input)
        case "$3" in
          swipe) screen="$(cat "$S/screen")"; k=hscroll; [[ "$screen" == artists ]] && k=ascroll
                 # y2 < y1 is a finger moving up = list scrolls down; the reverse scrolls back up.
                 d=1; (( $7 > $5 )) && d=-1
                 n=$(( $(read_n $k) + d )); (( n < 0 )) && n=0
                 echo "$n" > "$S/$k"; echo "swipe $screen" >> "$S/log" ;;
          keyevent) echo home > "$S/screen"; echo "back" >> "$S/log" ;;
          tap)
            screen="$(cat "$S/screen")"; xy="$4,$5"
            if [[ "$xy" == 125,2243 ]]; then echo home > "$S/screen"; echo "tap nav.home" >> "$S/log"
            elif [[ "$screen" == home && "$xy" == 540,1050 && $(read_n hscroll) -ge 2 && $(read_n hscroll) -le 5 ]]; then
              echo artists > "$S/screen"; echo 0 > "$S/ascroll"; echo "tap home.browse-artists" >> "$S/log"
            elif [[ "$screen" == artists && "$xy" == 950,1050 && $(read_n ascroll) -ge 3 ]]; then
              if [[ -e "$S/fav" ]]; then rm "$S/fav"; else : > "$S/fav"; fi
              echo "tap artists.favorite.$STUB_KEY" >> "$S/log"
            else echo "tap miss $xy" >> "$S/log"; fi ;;
        esac ;;
      am) ;;
    esac ;;
esac
exit 0
STUB
chmod +x "$TMP/bin/adb"

# step <name> <sync-step> <initially-favorited yes|no>: runs the step; RC and $TMP/state/* hold results.
step() {
  local name="$1" syncstep="$2" fav="$3"
  rm -f "$TMP/state/"*; echo home > "$TMP/state/screen"; : > "$TMP/state/log"
  [[ "$fav" == yes ]] && : > "$TMP/state/fav"
  [[ -n "${H0:-}" ]] && echo "$H0" > "$TMP/state/hscroll"
  PATH="$TMP/bin:$PATH" STUB_STATE="$TMP/state" STUB_KEY=moe CCTV_SMOKE_SERIAL=stub \
    CCTV_SMOKE_SYNC_ARTIST_ANDROID=moe CCTV_SMOKE_ANDROID_MAX_SCROLLS=6 \
    env -u CCTV_SMOKE_ANDROID_FAVORITE_TOGGLE -u CCTV_SMOKE_ANDROID_ARTIST_ENTRY_TAG \
    "$DIR/run-android.sh" --apk "$TMP/apk.apk" --timeout 3 --sync-step "$syncstep" >/dev/null 2>"$TMP/$name.err"
  RC=$?
}
faved() { [[ -e "$TMP/state/fav" ]]; }
tapped() { grep -qx "tap $1" "$TMP/state/log"; }

step add favorite-add no
check "favorite-add: exit 0" test "$RC" = 0
check "favorite-add: scrolled Home and tapped home.browse-artists" tapped home.browse-artists
check "favorite-add: scrolled the Artists list and tapped the star" tapped artists.favorite.moe
check "favorite-add: artist is now favorited" faved

# Home retained a scroll position below the "Browse artists" row: the runner must swipe back up.
H0=9 step below favorite-add no
check "Home scrolled past the row: exit 0" test "$RC" = 0
check "Home scrolled past the row: tapped home.browse-artists" tapped home.browse-artists
check "Home scrolled past the row: artist is now favorited" faved

step remove favorite-remove yes
check "favorite-remove: exit 0" test "$RC" = 0
check "favorite-remove: tapped the star" tapped artists.favorite.moe
check "favorite-remove: artist is no longer favorited" bash -c "! test -e '$TMP/state/fav'"

step ensure-fav favorite-ensure-absent yes
check "ensure-absent (stale favorite): exit 0" test "$RC" = 0
check "ensure-absent (stale favorite): tapped the star" tapped artists.favorite.moe
check "ensure-absent (stale favorite): favorite dropped" bash -c "! test -e '$TMP/state/fav'"

step ensure-none favorite-ensure-absent no
check "ensure-absent (already absent): exit 0" test "$RC" = 0
check "ensure-absent (already absent): no taps on the star" bash -c "! grep -q 'artists.favorite' '$TMP/state/log'"

# A star that never shows up is "control unavailable" (exit 4), never a PASS.
sed -i.bak 's/>= 3/>= 99/' "$TMP/bin/adb"
step nostar favorite-add no
check "missing star: exit 4 (unavailable)" test "$RC" = 4
check "missing star: error names the tag" grep -q 'artists.favorite.moe' "$TMP/nostar.err"

if [[ $failures -eq 0 ]]; then echo "All android sync-step tests passed."; else echo "$failures failure(s)."; exit 1; fi
