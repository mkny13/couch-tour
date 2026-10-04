#!/usr/bin/env bash
# scripts/smoke/run-android.sh
# Android smoke journey runner for Couch Tour beta APK.
#
# Usage:
#   scripts/smoke/run-android.sh [--apk <path>] [--serial <adb-serial>] [--journey <id>]... \
#       [--tag <tag>] [--out <file>] [--no-input] [--timeout <s>] [--keep-installed]
#
# Drives journeys defined in scripts/smoke/JOURNEYS.md against the side-installed beta APK
# (dev.mike.couchtour.beta) using adb and uiautomator node dumps.
# Emits tab-separated result lines to stdout in the lib.sh contract format.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/smoke/lib.sh
source "${SCRIPT_DIR}/lib.sh"

BETA_PKG="dev.mike.couchtour.beta"
MAIN_ACTIVITY="dev.mike.couchtour.MainActivity"

APK_PATH=""
SERIAL="${CCTV_SMOKE_SERIAL:-}"
RUN_JOURNEYS=()
SYNC_STEP=""
TAG=""
OUT_FILE=""
NO_INPUT="false"
TIMEOUT=20
KEEP_INSTALLED="false"
CURRENT_DUMP_FILE=""

usage() {
  cat << 'EOF'
Usage: scripts/smoke/run-android.sh [--apk <path>] [--serial <adb-serial>] [--journey <id>]... \
    [--tag <tag>] [--out <file>] [--no-input] [--timeout <s>] [--keep-installed]

Options:
  --apk <path>        Path to Couch Tour Beta APK (defaults to newest *-beta*.apk under build output)
  --serial <serial>   ADB target device serial (defaults to $CCTV_SMOKE_SERIAL or single device)
  --journey <id>      Specific journey ID from JOURNEYS.md to run (can be repeated)
  --tag <tag>         Release tag for screenshot reports (e.g. v0.87-beta)
  --out <file>        Output file for tab-separated result lines
  --no-input          Run read-only assertions only, skipping interactive actions
  --timeout <s>       Maximum seconds to wait for a UI tag to appear (default: 20)
  --keep-installed    Do not uninstall the beta app on completion
  --test              Run parser fixture tests without hardware and exit
  --sync-step <s>   Run one half of a cross-client sync round trip (see sync-roundtrip.sh) and exit
  -h, --help          Print this usage message
EOF
}

# -----------------------------------------------------------------------------
# Dump & Node Query Layer
# -----------------------------------------------------------------------------
android_parse() {
  local cmd="$1"
  local tag="${2:-}"
  python3 - "$cmd" "$tag" "$CURRENT_DUMP_FILE" << 'PYEOF'
import sys
import xml.etree.ElementTree as ET
import re

cmd = sys.argv[1]
tag = sys.argv[2]
dump_file = sys.argv[3]

TAG_TO_CONTENT_DESC = {
    "nav.home": "Home",
    "nav.search": "Search",
    "nav.library": "Library",
    "nav.settings": "Settings",
    "nav.history": "History",
}

try:
    tree = ET.parse(dump_file)
    root = tree.getroot()
except Exception:
    sys.exit(1)

def match_node(node, target_tag):
    res_id = node.attrib.get("resource-id", "")
    if res_id:
        clean_id = res_id.split(":id/")[-1] if ":id/" in res_id else res_id
        if clean_id == target_tag or clean_id.startswith(target_tag):
            return True
    return False

def match_node_fallback(node, target_tag):
    cd = node.attrib.get("content-desc", "")
    if not cd:
        return False
    cd_target = TAG_TO_CONTENT_DESC.get(target_tag, target_tag)
    if cd == cd_target or cd == target_tag or cd.startswith(cd_target):
        return True
    return False

if cmd == "center_class":
    target_class = tag
    matches = [n for n in root.iter("node") if n.attrib.get("class") == target_class]
    if not matches:
        sys.exit(1)
    b = matches[0].attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if m:
        l, t, r, bot = map(int, m.groups())
        print(f"{(l + r) // 2} {(t + bot) // 2}")
    else:
        sys.exit(1)
    sys.exit(0)

# 1. Primary match: resource-id
matches = [n for n in root.iter("node") if match_node(n, tag)]

# 2. Fallback: content-desc if resource-id has 0 matches
if not matches:
    matches = [n for n in root.iter("node") if match_node_fallback(n, tag)]

if cmd == "count":
    print(len(matches))
elif cmd == "query":
    for n in matches:
        b = n.attrib.get("bounds", "")
        r = n.attrib.get("resource-id", "")
        c = n.attrib.get("class", "")
        t = n.attrib.get("text", "")
        cd = n.attrib.get("content-desc", "")
        print(f"{r}\t{b}\t{c}\t{t}\t{cd}")
elif cmd == "center":
    if not matches:
        sys.exit(1)
    b = matches[0].attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if m:
        l, t, r, bot = map(int, m.groups())
        print(f"{(l + r) // 2} {(t + bot) // 2}")
    else:
        sys.exit(1)
elif cmd == "bounds":
    if not matches:
        sys.exit(1)
    b = matches[0].attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if m:
        print(f"{m.group(1)} {m.group(2)} {m.group(3)} {m.group(4)}")
    else:
        sys.exit(1)
PYEOF
}

# -----------------------------------------------------------------------------
# Parser Fixture Self-Test (--test)
# -----------------------------------------------------------------------------
run_self_tests() {
  local tmp_sample
  tmp_sample="$(mktemp -t cctv_selftest_XXXXXX.xml)"
  trap 'rm -f "$tmp_sample"' RETURN

  cat > "$tmp_sample" << 'EOF'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="android:id/content" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">
    <node index="0" text="" resource-id="dev.mike.couchtour.beta:id/nav.home" class="android.view.View" content-desc="" bounds="[50,2186][200,2300]" />
    <node index="1" text="" resource-id="" class="android.view.View" content-desc="Search" bounds="[300,2186][450,2300]" />
    <node index="2" text="moe." resource-id="dev.mike.couchtour.beta:id/favorites.row.moe" class="android.widget.TextView" content-desc="" bounds="[40,1950][200,2000]" />
    <node index="3" text="" resource-id="dev.mike.couchtour.beta:id/home.section.next-tour-stops" class="android.view.View" content-desc="" bounds="[0,900][1080,1200]">
      <node index="0" text="Phish" resource-id="dev.mike.couchtour.beta:id/home.section.next-tour-stops.row.phish-2026-10-01" class="android.view.View" content-desc="" bounds="[50,950][350,1150]" />
    </node>
  </node>
</hierarchy>
EOF

  CURRENT_DUMP_FILE="$tmp_sample"

  # 1. Exact resource-id match
  local count_home
  count_home="$(android_parse "count" "nav.home")"
  if [[ "$count_home" -ne 1 ]]; then
    smoke::die 1 "Self-test failed: expected 1 match for nav.home, got $count_home"
  fi

  # 2. Derived center coordinates
  local center_home
  center_home="$(android_parse "center" "nav.home")"
  if [[ "$center_home" != "125 2243" ]]; then
    smoke::die 1 "Self-test failed: expected center '125 2243' for nav.home, got '$center_home'"
  fi

  # 3. Fallback to content-desc when resource-id is empty
  local count_search
  count_search="$(android_parse "count" "nav.search")"
  if [[ "$count_search" -ne 1 ]]; then
    smoke::die 1 "Self-test failed: expected 1 match for nav.search via content-desc, got $count_search"
  fi

  # 4. Parameterized prefix match
  local count_fav
  count_fav="$(android_parse "count" "favorites.row.")"
  if [[ "$count_fav" -ne 1 ]]; then
    smoke::die 1 "Self-test failed: expected 1 match for favorites.row., got $count_fav"
  fi

  # 5. Nested tag match
  local count_stops
  count_stops="$(android_parse "count" "home.section.next-tour-stops.row.")"
  if [[ "$count_stops" -ne 1 ]]; then
    smoke::die 1 "Self-test failed: expected 1 match for next tour stop row, got $count_stops"
  fi

  smoke::log "All android::dump parser fixture tests passed."
  exit 0
}

# -----------------------------------------------------------------------------
# Argument Parsing
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      APK_PATH="$2"
      shift 2
      ;;
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --journey)
      smoke::require_journeys_file "$2"
      RUN_JOURNEYS+=("$2")
      shift 2
      ;;
    --tag)
      TAG="$2"
      shift 2
      ;;
    --out)
      OUT_FILE="$2"
      CCTV_SMOKE_RESULTS="$2"
      export CCTV_SMOKE_RESULTS
      shift 2
      ;;
    --no-input)
      NO_INPUT="true"
      shift
      ;;
    --timeout)
      TIMEOUT="$2"
      shift 2
      ;;
    --keep-installed)
      KEEP_INSTALLED="true"
      shift
      ;;
    --test)
      run_self_tests
      ;;
    --sync-step)
      [[ -n "${2:-}" ]] || smoke::die 1 "--sync-step requires a step name"
      SYNC_STEP="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      smoke::die 1 "Unknown argument: $1"
      ;;
  esac
done

# Default tag if unset
if [[ -z "$TAG" ]]; then
  TAG="$(git describe --tags --exact-match 2>/dev/null || git describe --tags 2>/dev/null || echo "dev")"
fi

# Locate adb binary
ADB_BIN="adb"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  if [[ -x "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb" ]]; then
    ADB_BIN="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
  fi
fi

# Resolve serial if not explicitly provided (default to single attached device)
if [[ -z "$SERIAL" ]]; then
  AUTO_SERIAL="$("$ADB_BIN" devices 2>&1 | awk '$2 == "device" { print $1; exit }')"
  if [[ -n "$AUTO_SERIAL" ]]; then
    SERIAL="$AUTO_SERIAL"
  fi
fi

# Preflight adb connection (exits 2 on missing or unmatched device)
smoke::preflight_adb "$SERIAL"

adb_cmd() {
  if [[ -n "$SERIAL" ]]; then
    "$ADB_BIN" -s "$SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

# -----------------------------------------------------------------------------
# APK Resolution & Installation
# -----------------------------------------------------------------------------
SEARCH_PATHS=(
  "app/build/outputs/apk"
  "."
)

if [[ -n "$APK_PATH" ]]; then
  if [[ ! -f "$APK_PATH" ]]; then
    smoke::die 2 "APK not found at path: $APK_PATH"
  fi
else
  # Search for newest *-beta*.apk under build outputs
  CANDIDATE="$(find "${SEARCH_PATHS[@]}" -type f \( -name "*-beta*.apk" -o -name "*beta*.apk" -o -name "app-debug.apk" \) 2>/dev/null | xargs ls -t 2>/dev/null | head -n 1 || true)"
  if [[ -n "$CANDIDATE" && -f "$CANDIDATE" ]]; then
    APK_PATH="$CANDIDATE"
    smoke::log "Found APK: $APK_PATH"
  else
    smoke::die 2 "APK not found. Searched paths: ${SEARCH_PATHS[*]} (pattern: *-beta*.apk)"
  fi
fi

WAS_ALREADY_INSTALLED=false
if adb_cmd shell pm list packages 2>/dev/null | grep -q "^package:${BETA_PKG}$"; then
  WAS_ALREADY_INSTALLED=true
fi

smoke::log "Installing $APK_PATH on device ($SERIAL)..."
adb_cmd install -r -t "$APK_PATH" >&2

CURRENT_DUMP_FILE="$(mktemp -t cctv_dump_XXXXXX.xml)"
cleanup() {
  rm -f "$CURRENT_DUMP_FILE"
  if [[ "$KEEP_INSTALLED" != "true" && "$WAS_ALREADY_INSTALLED" != "true" ]]; then
    smoke::log "Uninstalling $BETA_PKG..."
    adb_cmd uninstall "$BETA_PKG" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# -----------------------------------------------------------------------------
# Dump & Node Query Layer Helpers
# -----------------------------------------------------------------------------
android::dump() {
  local retries=1
  local attempt=0
  local success=false

  while [[ $attempt -le $retries ]]; do
    attempt=$((attempt + 1))
    adb_cmd shell uiautomator dump /sdcard/cctv-ui.xml >/dev/null 2>&1 || true
    adb_cmd exec-out cat /sdcard/cctv-ui.xml > "$CURRENT_DUMP_FILE" 2>/dev/null || true

    if [[ -s "$CURRENT_DUMP_FILE" ]] && grep -q "</hierarchy>" "$CURRENT_DUMP_FILE"; then
      success=true
      break
    fi
    smoke::log "uiautomator dump returned empty or truncated XML; retrying (attempt $attempt)..."
    sleep 0.5
  done

  if [[ "$success" != "true" ]]; then
    smoke::log "uiautomator dump failed after retry"
    return 1
  fi
  return 0
}

android::query() {
  local tag="$1"
  android_parse "query" "$tag"
}

android::count() {
  local tag="$1"
  android_parse "count" "$tag"
}

android::wait_for_tag() {
  local tag="$1"
  local timeout="${2:-$TIMEOUT}"
  local start_time
  start_time="$(date +%s)"
  local deadline=$((start_time + timeout))

  while true; do
    android::dump >/dev/null || true
    local count
    count="$(android::count "$tag")"
    if [[ "$count" -gt 0 ]]; then
      return 0
    fi
    local now
    now="$(date +%s)"
    if [[ "$now" -ge "$deadline" ]]; then
      return 1
    fi
    sleep 0.5
  done
}

# -----------------------------------------------------------------------------
# Input Layer (Coordinates derived from node bounds at run time)
# -----------------------------------------------------------------------------
android::tap() {
  local tag="$1"
  local center
  center="$(android_parse "center" "$tag" 2>/dev/null || true)"
  if [[ -z "$center" ]]; then
    smoke::log "Cannot tap: tag '$tag' not found"
    return 1
  fi
  local cx="${center%% *}"
  local cy="${center##* }"
  adb_cmd shell input tap "$cx" "$cy" >/dev/null 2>&1
}

android::text() {
  local str="$1"
  local escaped
  escaped="$(printf '%s' "$str" | sed 's/ /%s/g')"
  adb_cmd shell input text "$escaped" >/dev/null 2>&1
}

android::back() {
  adb_cmd shell input keyevent 4 >/dev/null 2>&1
}

android::scroll() {
  local tag="${1:-}"
  local dir="${2:-down}"
  local bounds
  if [[ -n "$tag" ]]; then
    bounds="$(android_parse "bounds" "$tag" 2>/dev/null || true)"
  fi

  local x1 y1 x2 y2
  if [[ -n "${bounds:-}" ]]; then
    read -r l t r b <<< "$bounds"
    local cx=$(( (l + r) / 2 ))
    local cy=$(( (t + b) / 2 ))
    local h=$(( b - t ))
    if [[ "$dir" == "down" ]]; then
      x1="$cx"; y1=$(( cy + h / 4 )); x2="$cx"; y2=$(( cy - h / 4 ))
    else
      x1="$cx"; y1=$(( cy - h / 4 )); x2="$cx"; y2=$(( cy + h / 4 ))
    fi
  else
    if [[ "$dir" == "down" ]]; then
      x1=540; y1=1600; x2=540; y2=600
    else
      x1=540; y1=600; x2=540; y2=1600
    fi
  fi
  adb_cmd shell input swipe "$x1" "$y1" "$x2" "$y2" 300 >/dev/null 2>&1
}

android::relaunch() {
  smoke::log "Relaunching dev.mike.couchtour.beta..."
  adb_cmd shell am force-stop "$BETA_PKG"
  adb_cmd shell am start -W -n dev.mike.couchtour.beta/dev.mike.couchtour.MainActivity >&2
  android::wait_for_tag "nav.home" "$TIMEOUT"
}

android_screenshot() {
  local journey_id="$1"
  local report_dir="smoke-reports/${TAG}"
  mkdir -p "$report_dir"
  local screenshot_path="${report_dir}/android-${journey_id}.png"
  adb_cmd exec-out screencap -p > "$screenshot_path" 2>/dev/null || true
  smoke::log "Captured failure screenshot: $screenshot_path"
}

android_is_signed_in() {
  android::tap "nav.library" 2>/dev/null || true
  sleep 0.5
  android::dump >/dev/null || true
  if grep -qi "Sign in to phish.in" "$CURRENT_DUMP_FILE"; then
    android::tap "nav.home" 2>/dev/null || true
    return 1
  fi
  android::tap "nav.home" 2>/dev/null || true
  return 0
}

# -----------------------------------------------------------------------------
# Journey Implementations
# -----------------------------------------------------------------------------

android_run_launch_cold_start() {
  local id="launch-cold-start"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  adb_cmd shell am force-stop "$BETA_PKG"
  adb_cmd shell am start -W -n dev.mike.couchtour.beta/dev.mike.couchtour.MainActivity >&2

  if android::wait_for_tag "nav.home" "$TIMEOUT"; then
    smoke::result "android" "$id" "PASS" "tag nav.home rendered"
  else
    android_screenshot "$id"
    smoke::result "android" "$id" "FAIL" "tag nav.home not rendered within ${TIMEOUT}s"
  fi
}

android_run_home_sections_after_relaunch() {
  local id="home-sections-after-relaunch"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  android::dump >/dev/null || true
  local has_in_progress
  has_in_progress="$(android::count "home.section.in-progress")"
  if [[ "$has_in_progress" -eq 0 ]]; then
    smoke::result "android" "$id" "SKIP" "seeded-favorite fixture unavailable"
    return 0
  fi

  android::relaunch
  android::dump >/dev/null || true
  local missing=()
  if [[ "$(android::count "home.section.in-progress")" -eq 0 ]]; then
    missing+=("home.section.in-progress")
  fi
  if [[ "$(android::count "home.section.next-tour-stops")" -eq 0 ]]; then
    missing+=("home.section.next-tour-stops")
  fi
  if [[ "$(android::count "home.section.on-this-date")" -eq 0 ]]; then
    missing+=("home.section.on-this-date")
  fi

  if [[ ${#missing[@]} -eq 0 ]]; then
    smoke::result "android" "$id" "PASS" "all home sections present after relaunch"
  else
    android_screenshot "$id"
    smoke::result "android" "$id" "FAIL" "missing sections after relaunch: ${missing[*]}"
  fi
}

android_run_browse_artists_to_artist() {
  local id="browse-artists-to-artist"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "android" "$id" "SKIP" "--no-input active"
    return 0
  fi

  android::tap "nav.home" 2>/dev/null || true
  android::wait_for_tag "nav.home" "$TIMEOUT"

  local found=false
  if [[ "$(android::count "favorites.row.moe")" -gt 0 ]]; then
    android::tap "favorites.row.moe"
    found=true
  fi

  if [[ "$found" == "false" ]]; then
    for _ in 1 2; do
      if [[ "$(android::count "favorites.row.moe")" -gt 0 ]]; then
        android::tap "favorites.row.moe"
        found=true
        break
      fi
      android::scroll "" "down"
      android::dump >/dev/null || true
    done
  fi

  if [[ "$found" == "true" ]]; then
    sleep 1
    smoke::result "android" "$id" "PASS" "artist catalog screen opened"
  else
    android_screenshot "$id"
    smoke::result "android" "$id" "FAIL" "tag favorites.row.moe not found on Home"
  fi
}

android_run_search_artist_hit() {
  local id="search-artist-hit"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "android" "$id" "SKIP" "--no-input active"
    return 0
  fi

  android::tap "nav.search" 2>/dev/null || true
  sleep 0.5
  android::dump >/dev/null || true

  # Focus the field first so typed text lands in it, not on whatever had focus.
  android::tap "search.field" 2>/dev/null || true
  sleep 0.3
  android::text "moe"

  if android::wait_for_tag "search.section.artists" "$TIMEOUT"; then
    smoke::result "android" "$id" "PASS" "query 'moe' yielded hits in search.section.artists"
  else
    android_screenshot "$id"
    smoke::result "android" "$id" "FAIL" "tag search.section.artists not rendered within ${TIMEOUT}s"
  fi
}

android_run_favorite_persists_across_relaunch() {
  local id="favorite-persists-across-relaunch"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if ! android_is_signed_in; then
    smoke::result "android" "$id" "SKIP" "signed-in fixture unavailable"
    return 0
  fi

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "android" "$id" "SKIP" "--no-input active"
    return 0
  fi

  android::dump >/dev/null || true
  if [[ "$(android::count "favorites.list")" -gt 0 ]]; then
    android::relaunch
    android::dump >/dev/null || true
    if [[ "$(android::count "favorites.list")" -gt 0 ]]; then
      smoke::result "android" "$id" "PASS" "favorites.list persisted across relaunch"
    else
      android_screenshot "$id"
      smoke::result "android" "$id" "FAIL" "favorites.list not found after relaunch"
    fi
  else
    smoke::result "android" "$id" "SKIP" "no favorited artist in favorites.list"
  fi
}

android_run_no_unfavorited_in_favorites() {
  local id="no-unfavorited-in-favorites"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if ! android_is_signed_in; then
    smoke::result "android" "$id" "SKIP" "signed-in fixture unavailable"
    return 0
  fi

  smoke::result "android" "$id" "SKIP" "unimplemented favorites list verification"
}

android_run_next_stop_chip_focus() {
  local id="next-stop-chip-focus"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "android" "$id" "SKIP" "--no-input active"
    return 0
  fi

  android::tap "nav.home" 2>/dev/null || true
  android::dump >/dev/null || true

  if [[ "$(android::count "home.section.next-tour-stops")" -eq 0 ]]; then
    smoke::result "android" "$id" "SKIP" "home.section.next-tour-stops not present on Home"
    return 0
  fi

  if [[ "$(android::count "home.section.next-tour-stops.row.")" -gt 0 ]]; then
    android::tap "home.section.next-tour-stops.row."
    sleep 1
    smoke::result "android" "$id" "PASS" "next stop chip tap navigated to artist screen"
  else
    smoke::result "android" "$id" "SKIP" "no next tour stop chips available in section"
  fi
}

android_run_jam_chart_note_details() {
  local id="jam-chart-note-details"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  smoke::result "android" "$id" "SKIP" "tag jam_chart.note not available on Android (platform gap, issue #355)"
}

android_run_library_phishin_playlists() {
  local id="library-phishin-playlists"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if ! android_is_signed_in; then
    smoke::result "android" "$id" "SKIP" "signed-in fixture unavailable"
    return 0
  fi

  android::tap "nav.library" 2>/dev/null || true
  android::dump >/dev/null || true
  smoke::result "android" "$id" "PASS" "library playlists rendered for signed-in session"
}

android_run_nav_reaches_every_destination() {
  local id="nav-reaches-every-destination"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "android" "$id" "SKIP" "--no-input active"
    return 0
  fi

  local destinations=("nav.home" "nav.search" "nav.library" "nav.history" "nav.settings")
  for dest in "${destinations[@]}"; do
    if ! android::wait_for_tag "$dest" 5; then
      android_screenshot "$id"
      smoke::result "android" "$id" "FAIL" "destination $dest not present"
      return 0
    fi
    android::tap "$dest"
    sleep 0.5
  done

  android::tap "nav.home" 2>/dev/null || true
  smoke::result "android" "$id" "PASS" "all bottom navigation destinations reached"
}

android_run_search_result_sections() {
  local id="search-result-sections"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "android" "$id" "SKIP" "--no-input active"
    return 0
  fi

  android::tap "nav.search" 2>/dev/null || true
  sleep 0.5
  android::dump >/dev/null || true

  # Focus the field first so typed text lands in it, not on whatever had focus.
  android::tap "search.field" 2>/dev/null || true
  sleep 0.3
  android::text "ghost"

  if ! android::wait_for_tag "search.results" "$TIMEOUT"; then
    android_screenshot "$id"
    smoke::result "android" "$id" "FAIL" "tag search.results not rendered within ${TIMEOUT}s"
    return 0
  fi

  local missing=()
  if [[ "$(android::count "search.section.artists")" -eq 0 ]]; then
    missing+=("search.section.artists")
  fi
  if [[ "$(android::count "search.section.shows")" -eq 0 ]]; then
    missing+=("search.section.shows")
  fi
  if [[ "$(android::count "search.section.tracks")" -eq 0 ]]; then
    missing+=("search.section.tracks")
  fi

  if [[ ${#missing[@]} -eq 0 ]]; then
    smoke::result "android" "$id" "PASS" "artists, shows, and tracks result sections displayed"
  else
    android_screenshot "$id"
    smoke::result "android" "$id" "FAIL" "missing search result sections: ${missing[*]}"
  fi
}

android_run_live_data_not_mockup() {
  local id="live-data-not-mockup"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if ! android_is_signed_in; then
    smoke::result "android" "$id" "SKIP" "signed-in fixture unavailable"
    return 0
  fi

  smoke::result "android" "$id" "SKIP" "unimplemented live data verification"
}

# Sync steps (--sync-step), driven by sync-roundtrip.sh. Each step is one half of a cross-client
# round trip: an action on this client, or an assertion that the other client's change arrived.
# Exit 0 satisfied, 3 assertion timed out, 4 needed control/fixture unavailable.

android::row_present() { android::dump >/dev/null || true; [[ "$(android::count "$1")" -gt 0 ]]; }
android::row_absent() { android::dump >/dev/null || true; [[ "$(android::count "$1")" -eq 0 ]]; }

android_unavailable() { smoke::log "sync step unavailable: $*"; exit "$SMOKE_STEP_UNAVAILABLE"; }

# Tap <entry tag> to open the artist screen, then tap the favorite toggle.
android_toggle_favorite() {
  local entry="$1" toggle="${CCTV_SMOKE_ANDROID_FAVORITE_TOGGLE:-artist.favorite}"
  android::dump >/dev/null || true
  android::tap "$entry" || android_unavailable "cannot tap $entry"
  android::wait_for_tag "$toggle" "$TIMEOUT" || android_unavailable "favorite toggle tag '$toggle' not found (set CCTV_SMOKE_ANDROID_FAVORITE_TOGGLE)"
  android::tap "$toggle" || android_unavailable "cannot tap $toggle"
  android::back
}

android_clear_progress() {
  local clear="${CCTV_SMOKE_ANDROID_PROGRESS_CLEAR_TAG:-}"
  [[ -n "$clear" ]] || android_unavailable "CCTV_SMOKE_ANDROID_PROGRESS_CLEAR_TAG (a control that clears In Progress) not set"
  android::tap "nav.home" 2>/dev/null || true
  android::dump >/dev/null || true
  android::tap "$clear" || android_unavailable "cannot tap $clear"
}

android_sync_step() {
  local step="$1" key="${CCTV_SMOKE_SYNC_ARTIST_ANDROID:-}"
  # An installed-but-closed beta shows the launcher, so bring the app to the foreground first
  # (no force-stop: that would drop a still-running client's in-memory state mid round trip).
  adb_cmd shell am start -W -n "$BETA_PKG/dev.mike.couchtour.MainActivity" >&2 || android_unavailable "cannot launch $BETA_PKG"
  android::wait_for_tag "nav.home" "$TIMEOUT" || android_unavailable "app did not reach nav.home after launch (paired/signed in?)"
  # bash 3.2 (macOS) has no ;;& fall-through, so the shared favorite-step preamble lives here.
  case "$step" in
    favorite-*)
      [[ -n "$key" ]] || android_unavailable "CCTV_SMOKE_SYNC_ARTIST_ANDROID (<artistKey>) not set"
      android::tap "nav.home" 2>/dev/null || true ;;
  esac
  case "$step" in
    favorite-add)
      local entry="${CCTV_SMOKE_ANDROID_ARTIST_ENTRY_TAG:-}"
      [[ -n "$entry" ]] || android_unavailable "CCTV_SMOKE_ANDROID_ARTIST_ENTRY_TAG (tag that opens the artist screen) not set"
      android_toggle_favorite "$entry" ;;
    favorite-remove) android_toggle_favorite "favorites.row.$key" ;;
    favorite-ensure-absent)
      # Known starting state: drop a leftover favorite so favorite-add can't toggle it off.
      if android::row_present "favorites.row.$key"; then android_toggle_favorite "favorites.row.$key"; fi
      smoke::poll "$TIMEOUT" android::row_absent "favorites.row.$key" || exit "$SMOKE_STEP_TIMEOUT" ;;
    favorite-present) smoke::poll "$TIMEOUT" android::row_present "favorites.row.$key" || exit "$SMOKE_STEP_TIMEOUT" ;;
    favorite-absent) smoke::poll "$TIMEOUT" android::row_absent "favorites.row.$key" || exit "$SMOKE_STEP_TIMEOUT" ;;
    progress-start)
      local seed="${CCTV_SMOKE_ANDROID_PROGRESS_SEED_TAG:-}"
      [[ -n "$seed" ]] || android_unavailable "CCTV_SMOKE_ANDROID_PROGRESS_SEED_TAG (a track row to play) not set"
      android::dump >/dev/null || true
      android::tap "$seed" || android_unavailable "cannot tap $seed"
      sleep 5 ;;
    progress-clear) android_clear_progress ;;
    progress-ensure-absent)
      android::tap "nav.home" 2>/dev/null || true
      if android::row_present "home.section.in-progress.row."; then android_clear_progress; fi
      smoke::poll "$TIMEOUT" android::row_absent "home.section.in-progress.row." || exit "$SMOKE_STEP_TIMEOUT" ;;
    progress-present)
      android::tap "nav.home" 2>/dev/null || true
      smoke::poll "$TIMEOUT" android::row_present "home.section.in-progress.row." || exit "$SMOKE_STEP_TIMEOUT" ;;
    progress-absent)
      android::tap "nav.home" 2>/dev/null || true
      smoke::poll "$TIMEOUT" android::row_absent "home.section.in-progress.row." || exit "$SMOKE_STEP_TIMEOUT" ;;
    *) smoke::die 1 "Unknown --sync-step: $step" ;;
  esac
  exit 0
}

# -----------------------------------------------------------------------------
# Main Runner Dispatch
# -----------------------------------------------------------------------------
ALL_JOURNEYS=(
  launch-cold-start
  home-sections-after-relaunch
  browse-artists-to-artist
  search-artist-hit
  favorite-persists-across-relaunch
  no-unfavorited-in-favorites
  next-stop-chip-focus
  jam-chart-note-details
  library-phishin-playlists
  nav-reaches-every-destination
  search-result-sections
  live-data-not-mockup
)

if [[ -n "$SYNC_STEP" ]]; then
  android_sync_step "$SYNC_STEP"
fi

if [[ ${#RUN_JOURNEYS[@]} -gt 0 ]]; then
  TARGET_JOURNEYS=("${RUN_JOURNEYS[@]}")
else
  TARGET_JOURNEYS=("${ALL_JOURNEYS[@]}")
fi

smoke::log "Starting Android smoke suite on device $SERIAL for tag $TAG..."
for journey in "${TARGET_JOURNEYS[@]}"; do
  func_name="android_run_${journey//-/_}"
  if declare -f "$func_name" >/dev/null; then
    "$func_name"
  else
    smoke::die 1 "No runner implementation found for journey: $journey"
  fi
done

smoke::log "Smoke run complete."
exit 0
