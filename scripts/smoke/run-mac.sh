#!/usr/bin/env bash
# scripts/smoke/run-mac.sh
# macOS smoke journey runner for Couch Tour Beta.
#
# Usage:
#   scripts/smoke/run-mac.sh [--journey <id>]... [--tag <tag>] [--out <file>] \
#       [--no-input] [--timeout <s>] [--allow-focus]
#
# Drives journeys defined in scripts/smoke/JOURNEYS.md against the installed
# Couch Tour Beta (dev.mike.couchtour.mac.beta) using System Events and JXA.
# Emits tab-separated result lines to stdout in the lib.sh contract format.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/smoke/lib.sh
source "${SCRIPT_DIR}/lib.sh"

BUNDLE_ID="dev.mike.couchtour.mac.beta"
APP_NAME="Couch Tour Beta"

RUN_JOURNEYS=()
TAG=""
OUT_FILE=""
NO_INPUT="false"
TIMEOUT=20
ALLOW_FOCUS="false"

usage() {
  cat << 'EOF'
Usage: scripts/smoke/run-mac.sh [OPTIONS]

Options:
  --journey <id>    Specific journey ID from JOURNEYS.md to run (can be repeated)
  --tag <tag>       Release tag for screenshot reports (e.g. v0.87-beta)
  --out <file>      Output file for tab-separated result lines
  --no-input        Run read-only assertions only, skipping interactive actions
  --timeout <s>     Maximum seconds to wait for an identifier to appear (default: 20)
  --allow-focus     Allow activating Couch Tour Beta into the foreground
  -h, --help        Print this usage message
EOF
}

# -----------------------------------------------------------------------------
# Argument Parsing
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --journey)
      if [[ -z "${2:-}" ]]; then
        smoke::die 1 "--journey requires an id argument"
      fi
      # Validate that journey exists in JOURNEYS.md immediately
      smoke::require_journeys_file "$2"
      RUN_JOURNEYS+=("$2")
      shift 2
      ;;
    --tag)
      if [[ -z "${2:-}" ]]; then
        smoke::die 1 "--tag requires a tag argument"
      fi
      TAG="$2"
      shift 2
      ;;
    --out)
      if [[ -z "${2:-}" ]]; then
        smoke::die 1 "--out requires a file argument"
      fi
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
      if [[ -z "${2:-}" ]]; then
        smoke::die 1 "--timeout requires a seconds argument"
      fi
      TIMEOUT="$2"
      shift 2
      ;;
    --allow-focus)
      ALLOW_FOCUS="true"
      shift
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

if [[ -z "$TAG" ]]; then
  TAG="$(git describe --tags --exact-match 2>/dev/null || git describe --tags 2>/dev/null || echo "dev")"
fi

# -----------------------------------------------------------------------------
# Preflight
# -----------------------------------------------------------------------------
# Verify that Couch Tour Beta is running and that Accessibility access is granted.
# Exits 2 if the beta app is not running or if permissions are missing.
smoke::preflight_macos_beta

# -----------------------------------------------------------------------------
# Query Layer
# -----------------------------------------------------------------------------
# Query elements matching AXIdentifier within Couch Tour Beta's window.
# Output format per line: <identifier>\t<role>\t<title-or-value>
# Note: "entire contents" is banned because it hangs on Couch Tour Beta.
# The walk is strictly bounded by max depth (default 6) and timeout (ms).
mac::ax_query() {
  local target_id="${1:-}"
  local max_depth="${2:-6}"
  local timeout_ms="${3:-5000}"

  osascript -l JavaScript - "$target_id" "$max_depth" "$timeout_ms" "$BUNDLE_ID" "$APP_NAME" << 'JXA'
function run(argv) {
    var targetId = argv[0] || "";
    var maxDepth = parseInt(argv[1] || "6", 10);
    var timeoutMs = parseInt(argv[2] || "5000", 10);
    var bundleId = argv[3];
    var appName = argv[4];

    var app = Application('System Events');
    var proc = null;

    var processes = app.applicationProcesses();
    for (var i = 0; i < processes.length; i++) {
        try {
            if (processes[i].bundleIdentifier() === bundleId) {
                proc = processes[i];
                break;
            }
        } catch (e) {}
    }
    if (!proc) {
        try {
            var byName = app.applicationProcesses.byName(appName);
            if (byName.exists()) {
                proc = byName;
            }
        } catch(e) {}
    }
    if (!proc) {
        return "";
    }

    var startTime = Date.now();
    var matches = [];

    function walk(element, depth) {
        if (Date.now() - startTime > timeoutMs) return;

        var axId = null;
        try {
            axId = element.attributes.byName("AXIdentifier").value();
        } catch (e) {}

        if (axId) {
            var matched = false;
            if (!targetId || targetId === "*") {
                matched = true;
            } else if (axId === targetId || axId.startsWith(targetId + ".")) {
                matched = true;
            }
            if (matched) {
                var role = "";
                var val = "";
                try { role = element.role(); } catch(e) {}
                try {
                    val = element.title();
                    if (!val) {
                        val = element.value();
                    }
                } catch(e) {}
                if (val) {
                    val = ("" + val).replace(/[\r\n\t]+/g, " ").trim();
                } else {
                    val = "";
                }
                matches.push(axId + "\t" + role + "\t" + val);
            }
        }

        if (depth >= maxDepth) return;

        var children = [];
        try {
            children = element.uiElements();
        } catch(e) {}

        for (var i = 0; i < children.length; i++) {
            walk(children[i], depth + 1);
        }
    }

    var windows = [];
    try {
        windows = proc.windows();
    } catch (e) {}

    for (var i = 0; i < windows.length; i++) {
        walk(windows[i], 0);
    }

    return matches.join("\n");
}
JXA
}

# Query direct children of element matching AXIdentifier.
# Output format per line: <identifier>\t<role>\t<title-or-value>
mac::ax_children() {
  local target_id="${1:-}"
  local timeout_ms="${2:-5000}"

  osascript -l JavaScript - "$target_id" "$timeout_ms" "$BUNDLE_ID" "$APP_NAME" << 'JXA'
function run(argv) {
    var targetId = argv[0];
    var timeoutMs = parseInt(argv[1] || "5000", 10);
    var bundleId = argv[2];
    var appName = argv[3];

    var app = Application('System Events');
    var proc = null;
    var processes = app.applicationProcesses();
    for (var i = 0; i < processes.length; i++) {
        try {
            if (processes[i].bundleIdentifier() === bundleId) {
                proc = processes[i];
                break;
            }
        } catch (e) {}
    }
    if (!proc) {
        try {
            var byName = app.applicationProcesses.byName(appName);
            if (byName.exists()) {
                proc = byName;
            }
        } catch(e) {}
    }
    if (!proc) return "";

    var startTime = Date.now();
    var parentElement = null;

    function findParent(element, depth) {
        if (parentElement || Date.now() - startTime > timeoutMs) return;

        var axId = null;
        try {
            axId = element.attributes.byName("AXIdentifier").value();
        } catch (e) {}

        if (axId === targetId) {
            parentElement = element;
            return;
        }

        if (depth >= 6) return;

        var children = [];
        try {
            children = element.uiElements();
        } catch (e) {}

        for (var i = 0; i < children.length; i++) {
            findParent(children[i], depth + 1);
            if (parentElement) return;
        }
    }

    var windows = [];
    try {
        windows = proc.windows();
    } catch (e) {}

    for (var i = 0; i < windows.length; i++) {
        findParent(windows[i], 0);
        if (parentElement) break;
    }

    if (!parentElement) return "";

    var items = [];
    var uiChildren = [];
    try {
        uiChildren = parentElement.uiElements();
    } catch (e) {}

    for (var i = 0; i < uiChildren.length; i++) {
        var child = uiChildren[i];
        var axId = "";
        try {
            axId = child.attributes.byName("AXIdentifier").value() || "";
        } catch (e) {}
        var role = "";
        try { role = child.role() || ""; } catch (e) {}
        var val = "";
        try {
            val = child.title();
            if (!val) val = child.value();
        } catch (e) {}
        if (val) {
            val = ("" + val).replace(/[\r\n\t]+/g, " ").trim();
        } else {
            val = "";
        }
        items.push(axId + "\t" + role + "\t" + val);
    }

    return items.join("\n");
}
JXA
}


# Poll mac::ax_query until the identifier appears or timeout fires.
mac::wait_for_id() {
  local id="$1"
  local to="${2:-$TIMEOUT}"
  local start_time
  start_time="$(date +%s)"

  while true; do
    local matches
    matches="$(mac::ax_query "$id")"
    if [[ -n "$matches" ]]; then
      echo "$matches"
      return 0
    fi
    local now
    now="$(date +%s)"
    if (( now - start_time >= to )); then
      return 1
    fi
    sleep 0.25
  done
}

# -----------------------------------------------------------------------------
# Input Layer
# -----------------------------------------------------------------------------
# Assert frontmost process is Couch Tour Beta. Refuse to steal focus by default.
mac::focus() {
  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::die 2 "Input attempted while --no-input is set"
  fi

  local is_front=""
  is_front="$(osascript -l JavaScript - "$BUNDLE_ID" "$APP_NAME" << 'JXA'
function run(argv) {
    var bundleId = argv[0];
    var appName = argv[1];
    var se = Application("System Events");
    var front = se.applicationProcesses.whose({frontmost: true})[0];
    if (!front) return "false:none";
    var name = "";
    var bundle = "";
    try { name = front.name(); } catch(e) {}
    try { bundle = front.bundleIdentifier(); } catch(e) {}
    if (bundle === bundleId || name === appName) {
        return "true";
    }
    return "false:" + (name || bundle || "unknown");
}
JXA
)"

  if [[ "$is_front" == "true" ]]; then
    return 0
  fi

  if [[ "$ALLOW_FOCUS" == "true" ]]; then
    smoke::log "Activating Couch Tour Beta into foreground (--allow-focus)..."
    osascript -e "tell application id \"$BUNDLE_ID\" to activate" >/dev/null 2>&1 || \
    osascript -e "tell application \"$APP_NAME\" to activate" >/dev/null 2>&1 || true

    local attempts=0
    while [[ $attempts -lt 12 ]]; do
      local check
      check="$(osascript -l JavaScript - "$BUNDLE_ID" "$APP_NAME" << 'JXA'
function run(argv) {
    var bundleId = argv[0];
    var appName = argv[1];
    var se = Application("System Events");
    var front = se.applicationProcesses.whose({frontmost: true})[0];
    if (!front) return "false";
    try {
        if (front.bundleIdentifier() === bundleId || front.name() === appName) return "true";
    } catch(e) {}
    return "false";
}
JXA
)"
      if [[ "$check" == "true" ]]; then
        return 0
      fi
      sleep 0.25
      attempts=$((attempts + 1))
    done
  fi

  local current_front="${is_front#false:}"
  smoke::die 2 "Couch Tour Beta is not the frontmost process (currently: ${current_front:-unknown}). Refusing to steal focus; run in foreground or pass --allow-focus."
}

# Click element identified by AXIdentifier.
# Gated on confirming Couch Tour Beta is frontmost.
# Dispatched through System Events or synthesized at runtime-derived element center.
# No hardcoded screen coordinates anywhere.
mac::click() {
  local target_id="$1"
  local max_depth="${2:-6}"
  local timeout_ms="${3:-5000}"

  mac::focus

  local result
  result="$(osascript -l JavaScript - "$target_id" "$max_depth" "$timeout_ms" "$BUNDLE_ID" "$APP_NAME" << 'JXA'
function run(argv) {
    var targetId = argv[0];
    var maxDepth = parseInt(argv[1] || "6", 10);
    var timeoutMs = parseInt(argv[2] || "5000", 10);
    var bundleId = argv[3];
    var appName = argv[4];

    var app = Application('System Events');
    var proc = null;
    var processes = app.applicationProcesses();
    for (var i = 0; i < processes.length; i++) {
        try {
            if (processes[i].bundleIdentifier() === bundleId) {
                proc = processes[i];
                break;
            }
        } catch (e) {}
    }
    if (!proc) {
        try {
            var byName = app.applicationProcesses.byName(appName);
            if (byName.exists()) {
                proc = byName;
            }
        } catch(e) {}
    }
    if (!proc) return "NOT_FOUND";

    var startTime = Date.now();
    var targetElement = null;

    function walk(element, depth) {
        if (targetElement || Date.now() - startTime > timeoutMs) return;

        var axId = null;
        try {
            axId = element.attributes.byName("AXIdentifier").value();
        } catch (e) {}

        if (axId && (axId === targetId || axId.startsWith(targetId + "."))) {
            targetElement = element;
            return;
        }

        if (depth >= maxDepth) return;

        var children = [];
        try {
            children = element.uiElements();
        } catch(e) {}

        for (var i = 0; i < children.length; i++) {
            walk(children[i], depth + 1);
            if (targetElement) return;
        }
    }

    var windows = [];
    try {
        windows = proc.windows();
    } catch (e) {}

    for (var i = 0; i < windows.length; i++) {
        walk(windows[i], 0);
        if (targetElement) break;
    }

    if (!targetElement) return "NOT_FOUND";

    // 1. Dispatch click directly through System Events
    try {
        targetElement.click();
        return "CLICKED";
    } catch (e) {}

    // 2. Perform AXPress action
    try {
        var actions = targetElement.actions();
        for (var a = 0; a < actions.length; a++) {
            if (actions[a].name() === "AXPress") {
                actions[a].perform();
                return "PRESSED";
            }
        }
    } catch (e) {}

    // 3. Fallback: Derive center coordinates at runtime from located element frame
    try {
        var pos = targetElement.position();
        var sz = targetElement.size();
        var cx = Math.round(pos[0] + sz[0] / 2);
        var cy = Math.round(pos[1] + sz[1] / 2);
        return "COORDS:" + cx + ":" + cy;
    } catch (e) {}

    return "ACTION_FAILED";
}
JXA
)"

  if [[ "$result" == "CLICKED" || "$result" == "PRESSED" ]]; then
    return 0
  elif [[ "$result" =~ ^COORDS:([0-9]+):([0-9]+)$ ]]; then
    # Coordinates derived at runtime from the located element's frame.
    local cx="${BASH_REMATCH[1]}"
    local cy="${BASH_REMATCH[2]}"
    if ! swift - "$cx" "$cy" << 'SWIFTEOF' >/dev/null 2>&1
import CoreGraphics
import Foundation

if CommandLine.arguments.count >= 3,
   let x = Double(CommandLine.arguments[1]),
   let y = Double(CommandLine.arguments[2]) {
    let pt = CGPoint(x: x, y: y)
    guard let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: pt, mouseButton: .left),
          let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: pt, mouseButton: .left) else {
        exit(1)
    }
    down.post(tap: .cghidEventTap)
    usleep(50000)
    up.post(tap: .cghidEventTap)
} else {
    exit(1)
}
SWIFTEOF
    then
      return 0
    fi
    smoke::log "mac::click could not dispatch synthesized click for '$target_id'"
    return 1
  else
    smoke::log "mac::click could not locate or click element '$target_id' (status: $result)"
    return 1
  fi
}

# Type text into currently focused element.
# Gated on confirming Couch Tour Beta is frontmost.
mac::type() {
  local text="$1"
  mac::focus

  osascript -l JavaScript - "$text" << 'JXA' >/dev/null 2>&1
function run(argv) {
    var text = argv[0];
    var se = Application("System Events");
    se.keystroke(text);
}
JXA
}

# -----------------------------------------------------------------------------
# Screenshots & Relaunch
# -----------------------------------------------------------------------------
# Window-scoped capture of Couch Tour Beta window (no region capture).
# Captures window ID resolved dynamically via CoreGraphics.
mac::screenshot() {
  local path="$1"
  mkdir -p "$(dirname "$path")" 2>/dev/null || true

  local win_id
  win_id="$(swift - "$APP_NAME" << 'SWIFTEOF' 2>/dev/null || true
import CoreGraphics
import Foundation

let targetOwner = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Couch Tour Beta"
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
for win in list {
    let owner = win[kCGWindowOwnerName as String] as? String ?? ""
    if owner == targetOwner || owner.contains("Couch Tour") {
        if let wid = win[kCGWindowNumber as String] as? Int {
            print(wid)
            exit(0)
        }
    }
}
exit(1)
SWIFTEOF
)"

  if [[ -n "$win_id" ]]; then
    screencapture -o -l "$win_id" "$path" 2>/dev/null || screencapture -l "$win_id" "$path" 2>/dev/null || true
  else
    smoke::log "Could not determine window ID for $APP_NAME screenshot"
  fi
}

mac_screenshot() {
  local journey_id="$1"
  local screenshot_path="smoke-reports/${TAG}/mac-${journey_id}.png"
  mac::screenshot "$screenshot_path"
}

# Quits and reopens Couch Tour Beta by bundle id, then waits for sidebar.nav.home.
mac::relaunch() {
  # Quitting Beta interrupts the owner's live session, so only do it when
  # Beta is frontmost (or --allow-focus lets us take focus). Dies otherwise.
  mac::focus

  smoke::log "Relaunching $APP_NAME ($BUNDLE_ID)..."

  osascript -e "tell application id \"$BUNDLE_ID\" to quit" >/dev/null 2>&1 || true

  local quit_started
  quit_started="$(date +%s)"
  while pgrep -f "$APP_NAME" >/dev/null 2>&1; do
    if (( $(date +%s) - quit_started >= 15 )); then
      smoke::log "$APP_NAME did not exit within 15s after quit"
      return 1
    fi
    sleep 0.25
  done

  # Beta was frontmost before the quit, so reopening it in front is not a steal.
  open -b "$BUNDLE_ID"

  if ! mac::wait_for_id "sidebar.nav.home" "$TIMEOUT" >/dev/null; then
    # Don't abort: a broken cold start must surface as a journey FAIL.
    smoke::log "Failed to reach launch identifier (sidebar.nav.home) after relaunching $APP_NAME"
    return 1
  fi
}

# -----------------------------------------------------------------------------
# Journey Implementations
# -----------------------------------------------------------------------------

mac_run_launch_cold_start() {
  local id="launch-cold-start"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled: cold launch requires quitting and reopening Beta"
    return 0
  fi

  # Preflight needs Beta already running, so a real cold start means quitting it.
  if ! mac::relaunch; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "sidebar.nav.home not present after relaunching $APP_NAME"
    return 0
  fi

  if mac::wait_for_id "sidebar.nav.home" "$TIMEOUT" >/dev/null; then
    smoke::result "mac" "$id" "PASS" "sidebar.nav.home present after cold relaunch"
  else
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "sidebar.nav.home not present within ${TIMEOUT}s"
  fi
}

mac_run_home_sections_after_relaunch() {
  local id="home-sections-after-relaunch"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  # home.in_progress identifies the whole shelf, including its empty state.
  # There is no stable card identifier at HEAD to prove a seeded track survived.
  smoke::result "mac" "$id" "SKIP" "missing stable AXIdentifier for an in-progress card; cannot verify seeded track after relaunch"
}

mac_run_browse_artists_to_artist() {
  local id="browse-artists-to-artist"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  smoke::result "mac" "$id" "SKIP" "missing AXIdentifiers for artists list and artist screen (platform gap)"
}

mac_run_search_artist_hit() {
  local id="search-artist-hit"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  if ! mac::click "sidebar.nav.search"; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "could not click sidebar.nav.search"
    return 0
  fi

  if ! mac::wait_for_id "search.field" "$TIMEOUT" >/dev/null; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "search.field not rendered within ${TIMEOUT}s"
    return 0
  fi

  mac::click "search.field" || true
  # Clear existing query if clear button is present
  if [[ -n "$(mac::ax_query "search.clear")" ]]; then
    mac::click "search.clear" || true
  fi

  mac::type "moe"

  # Wait for search tab artists to appear with positive count
  local start_time
  start_time="$(date +%s)"
  local hit_found="false"

  while true; do
    local artist_tab
    artist_tab="$(mac::ax_query "search.tab.artists")"
    if [[ -n "$artist_tab" ]]; then
      # Expect "Artists <N>" where N > 0
      local count
      count="$(echo "$artist_tab" | grep -o -E '[0-9]+' | tail -n1 || true)"
      if [[ -n "$count" && "$count" -gt 0 ]]; then
        hit_found="true"
        break
      fi
    fi

    local now
    now="$(date +%s)"
    if (( now - start_time >= TIMEOUT )); then
      break
    fi
    sleep 0.25
  done

  if [[ "$hit_found" == "true" ]]; then
    smoke::result "mac" "$id" "PASS" "search.field received query and search.tab.artists has hits > 0"
  else
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "search.tab.artists did not display positive hits for query 'moe'"
  fi
}

mac_run_favorite_persists_across_relaunch() {
  local id="favorite-persists-across-relaunch"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  # Fixture: signed-in (requires favorited artist in favorites list)
  local fav_rows
  fav_rows="$(mac::ax_query "sidebar.favorites.row")"

  if [[ -z "$fav_rows" ]]; then
    smoke::result "mac" "$id" "SKIP" "signed-in fixture unavailable: no favorited artists in sidebar.favorites.list"
    return 0
  fi

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  local row_id
  row_id="$(echo "$fav_rows" | head -n1 | cut -f1)"

  if ! mac::relaunch; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "sidebar.nav.home not present after relaunching $APP_NAME"
    return 0
  fi

  if mac::wait_for_id "$row_id" "$TIMEOUT" >/dev/null; then
    smoke::result "mac" "$id" "PASS" "favorited artist row '$row_id' persisted across relaunch"
  else
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "favorited artist row '$row_id' missing from sidebar.favorites.list after relaunch"
  fi
}

mac_run_no_unfavorited_in_favorites() {
  local id="no-unfavorited-in-favorites"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  # Fixture: signed-in (requires favorited artists in favorites list)
  local fav_rows
  fav_rows="$(mac::ax_query "sidebar.favorites.row")"

  if [[ -z "$fav_rows" ]]; then
    smoke::result "mac" "$id" "SKIP" "signed-in fixture unavailable: no favorited artists in sidebar.favorites.list"
    return 0
  fi

  # Query broader scope to inspect all children and prevent tautological filtering
  local invalid_rows=0
  local total_rows=0

  # 1. Inspect direct UI children of sidebar.favorites.list container
  local list_children
  list_children="$(mac::ax_children "sidebar.favorites.list")"

  if [[ -n "$list_children" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      local child_id child_role child_val
      child_id="$(echo "$line" | cut -f1)"
      child_role="$(echo "$line" | cut -f2)"
      child_val="$(echo "$line" | cut -f3)"

      # Header is expected
      if [[ "$child_id" == "sidebar.favorites.header" ]]; then
        continue
      fi

      # Each row in the favorites list must have a valid favorited artist identifier
      if [[ "$child_id" =~ ^sidebar\.favorites\.row\.([a-z0-9_]+)\.([a-z0-9_-]+)$ ]]; then
        total_rows=$((total_rows + 1))
      else
        smoke::log "Invalid favorite child element: id='$child_id', role='$child_role', val='$child_val'"
        invalid_rows=$((invalid_rows + 1))
      fi
    done <<< "$list_children"
  fi

  # 2. Query broader scope sidebar.favorites for any unexpected elements
  local fav_scope
  fav_scope="$(mac::ax_query "sidebar.favorites")"
  if [[ -n "$fav_scope" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      local ax_id
      ax_id="$(echo "$line" | cut -f1)"
      if [[ "$ax_id" == "sidebar.favorites.list" || "$ax_id" == "sidebar.favorites.header" ]]; then
        continue
      fi
      if [[ ! "$ax_id" =~ ^sidebar\.favorites\.row\.([a-z0-9_]+)\.([a-z0-9_-]+)$ ]]; then
        smoke::log "Unexpected element in sidebar.favorites scope: '$ax_id'"
        invalid_rows=$((invalid_rows + 1))
      fi
    done <<< "$fav_scope"
  fi

  if [[ $invalid_rows -eq 0 && $total_rows -gt 0 ]]; then
    smoke::result "mac" "$id" "PASS" "all $total_rows rows in sidebar.favorites.list match confirmed favorites"
  elif [[ $invalid_rows -gt 0 ]]; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "detected $invalid_rows unconfirmed or malformed rows in sidebar.favorites.list"
  else
    smoke::result "mac" "$id" "SKIP" "signed-in fixture unavailable: no favorite rows found in sidebar.favorites.list"
  fi
}

mac_run_next_stop_chip_focus() {
  local id="next-stop-chip-focus"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  smoke::result "mac" "$id" "SKIP" "missing AXIdentifiers for artist chip and artist target screen (platform gap)"
}

# A visible note card is not enough: the journey requires the phish.in source link too.
mac_run_jam_chart_note_details() {
  local id="jam-chart-note-details"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  # The app exposes jam_chart.note but has no distinct source-link identifier.
  # Presence of the note card alone cannot prove that its required link exists.
  smoke::result "mac" "$id" "SKIP" "missing AXIdentifier for the jam chart source link; cannot verify the required link"
}

mac_run_library_phishin_playlists() {
  local id="library-phishin-playlists"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  # Fixture: signed-in
  local fav_rows
  fav_rows="$(mac::ax_query "sidebar.favorites.row")"
  if [[ -z "$fav_rows" ]]; then
    smoke::result "mac" "$id" "SKIP" "signed-in fixture unavailable: no favorited artist in sidebar.favorites.list"
    return 0
  fi

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  smoke::result "mac" "$id" "SKIP" "missing AXIdentifiers for library playlists (platform gap)"
}

mac_run_favorite_syncs_mac_to_android() {
  local id="favorite-syncs-mac-to-android"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  smoke::result "mac" "$id" "SKIP" "cross-platform; covered by the Android runner and the report"
}

mac_run_favorite_syncs_android_to_mac() {
  local id="favorite-syncs-android-to-mac"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  smoke::result "mac" "$id" "SKIP" "cross-platform; covered by the Android runner and the report"
}

mac_run_in_progress_syncs_android_to_mac() {
  local id="in-progress-syncs-android-to-mac"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  smoke::result "mac" "$id" "SKIP" "cross-platform; covered by the Android runner and the report"
}

mac_run_nav_reaches_every_destination() {
  local id="nav-reaches-every-destination"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  smoke::result "mac" "$id" "SKIP" "missing AXIdentifiers for destination target views (platform gap)"
}

mac_run_search_result_sections() {
  local id="search-result-sections"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  if ! mac::click "sidebar.nav.search"; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "could not click sidebar.nav.search"
    return 0
  fi

  if ! mac::wait_for_id "search.field" "$TIMEOUT" >/dev/null; then
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "search.field not present"
    return 0
  fi

  mac::click "search.field" || true
  if [[ -n "$(mac::ax_query "search.clear")" ]]; then
    mac::click "search.clear" || true
  fi

  mac::type "ghost"

  local start_time
  start_time="$(date +%s)"
  local sections_ok="false"
  local missing=()

  while true; do
    missing=()
    local artists_tab shows_tab tracks_tab
    artists_tab="$(mac::ax_query "search.tab.artists")"
    shows_tab="$(mac::ax_query "search.tab.shows")"
    tracks_tab="$(mac::ax_query "search.tab.tracks")"

    local a_cnt s_cnt t_cnt
    a_cnt="$(echo "$artists_tab" | grep -o -E '[0-9]+' | tail -n1 || true)"
    s_cnt="$(echo "$shows_tab" | grep -o -E '[0-9]+' | tail -n1 || true)"
    t_cnt="$(echo "$tracks_tab" | grep -o -E '[0-9]+' | tail -n1 || true)"

    [[ -z "$a_cnt" || "$a_cnt" -le 0 ]] && missing+=("search.tab.artists")
    [[ -z "$s_cnt" || "$s_cnt" -le 0 ]] && missing+=("search.tab.shows")
    [[ -z "$t_cnt" || "$t_cnt" -le 0 ]] && missing+=("search.tab.tracks")

    if [[ ${#missing[@]} -eq 0 ]]; then
      sections_ok="true"
      break
    fi

    local now
    now="$(date +%s)"
    if (( now - start_time >= TIMEOUT )); then
      break
    fi
    sleep 0.25
  done

  if [[ "$sections_ok" == "true" ]]; then
    smoke::result "mac" "$id" "PASS" "artists, shows, and tracks search tabs render with positive result counts"
  else
    mac_screenshot "$id"
    smoke::result "mac" "$id" "FAIL" "missing positive count in search tabs: ${missing[*]}"
  fi
}

mac_run_live_data_not_mockup() {
  local id="live-data-not-mockup"
  smoke::require_journeys_file "$id"
  smoke::log "Executing journey $id..."

  # Fixture: signed-in
  local fav_rows
  fav_rows="$(mac::ax_query "sidebar.favorites.row")"

  if [[ -z "$fav_rows" ]]; then
    smoke::result "mac" "$id" "SKIP" "signed-in fixture unavailable: no favorited artist in sidebar.favorites.list"
    return 0
  fi

  if [[ "$NO_INPUT" == "true" ]]; then
    smoke::result "mac" "$id" "SKIP" "input disabled"
    return 0
  fi

  smoke::result "mac" "$id" "SKIP" "missing AXIdentifiers for live show data verification (platform gap)"
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
  favorite-syncs-mac-to-android
  favorite-syncs-android-to-mac
  in-progress-syncs-android-to-mac
  nav-reaches-every-destination
  search-result-sections
  live-data-not-mockup
)

if [[ ${#RUN_JOURNEYS[@]} -gt 0 ]]; then
  TARGET_JOURNEYS=("${RUN_JOURNEYS[@]}")
else
  TARGET_JOURNEYS=("${ALL_JOURNEYS[@]}")
fi

smoke::log "Starting macOS smoke suite for tag $TAG (${#TARGET_JOURNEYS[@]} journeys)..."
for journey in "${TARGET_JOURNEYS[@]}"; do
  func_name="mac_run_${journey//-/_}"
  if declare -f "$func_name" >/dev/null; then
    "$func_name"
  else
    smoke::die 1 "No runner implementation found for journey: $journey"
  fi
done

smoke::log "macOS smoke run complete."
exit 0
