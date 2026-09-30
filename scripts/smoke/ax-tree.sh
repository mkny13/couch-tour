#!/bin/bash
set -euo pipefail

# This script dumps the accessibility tree of Couch Tour Beta.
# The walk is bounded by both a max depth and a timeout.
# Note: "entire contents" is banned because it hangs on this app.

MAX_DEPTH=6
ALL=0
IDS_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --max-depth)
      MAX_DEPTH="$2"
      shift 2
      ;;
    --all)
      ALL=1
      shift
      ;;
    --identifiers-only)
      IDS_ONLY=1
      shift
      ;;
    --help)
      echo "Usage: ax-tree.sh [--max-depth N] [--all] [--identifiers-only]"
      exit 0
      ;;
    *)
      echo "Unknown option $1" >&2
      exit 1
      ;;
  esac
done

BUNDLE_ID="dev.mike.couchtour.mac.beta"

# Exit 2 if not running
if [ -z "$(lsappinfo info -app "$BUNDLE_ID")" ]; then
  echo "Couch Tour Beta is not running." >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SWIFT_FILE="$SCRIPT_DIR/../../macos/CouchTour/AXIdentifiers.swift"

if [[ ! -f "$SWIFT_FILE" ]]; then
  echo "Error: AXIdentifiers.swift not found at $SWIFT_FILE" >&2
  exit 1
fi

# Parse valid identifiers from swift file at runtime
VALID_IDS=$(grep -o 'public static let [a-zA-Z0-9_]* = "[^"]*"' "$SWIFT_FILE" | cut -d'"' -f2 | tr '\n' ',' | sed 's/,$//')

# Use JXA for the UI tree walk. We pass the identifiers and options.
# JXA handles the max-depth and timeout bounds.
OUTPUT=$(osascript -l JavaScript - "$MAX_DEPTH" "$ALL" "$IDS_ONLY" "$VALID_IDS" "$BUNDLE_ID" << 'JXA'
ObjC.import('Foundation');
ObjC.import('stdlib');

function run(argv) {
    var maxDepth = parseInt(argv[0], 10);
    var all = argv[1] === "1";
    var idsOnly = argv[2] === "1";
    var validIds = argv[3].split(",");
    var bundleId = argv[4];

    var validIdSet = {};
    for (var i = 0; i < validIds.length; i++) {
        if (validIds[i]) validIdSet[validIds[i]] = true;
    }

    var app = Application('System Events');
    var proc = null;
    
    // Find process by bundle id
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
        // Fallback to name if bundle id check failed
        try {
            var byName = app.applicationProcesses.byName("Couch Tour Beta");
            if (byName.exists()) {
                proc = byName;
            }
        } catch(e) {}
    }

    if (!proc) {
        $.exit(3);
    }

    var startTime = Date.now();
    var timeoutMs = 5000;
    var matches = [];

    function walk(element, depth) {
        if (Date.now() - startTime > timeoutMs) {
            return;
        }

        var axId = null;
        try {
            axId = element.attributes.byName("AXIdentifier").value();
        } catch (e) {}
        
        if (axId) {
            if (all || validIdSet[axId]) {
                if (idsOnly) {
                    matches.push(axId);
                } else {
                    var role = "";
                    var val = "";
                    try { role = element.role(); } catch(e) {}
                    try {
                        val = element.title();
                        if (!val) {
                            val = element.value();
                        }
                    } catch(e) {}
                    matches.push(axId + "\t" + role + "\t" + (val ? val : ""));
                }
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
    
    if (matches.length === 0) {
        $.exit(3);
    }
    
    // Sort matches
    matches.sort();
    return matches.join("\n");
}
JXA
)

# Output from osascript will be empty if it exited 3, or if we caught an error.
# But osascript exits with 0 if it returns a string, or with exit code if $.exit() is called.
EXIT_CODE=$?
if [ $EXIT_CODE -eq 0 ]; then
  if [ -n "$OUTPUT" ]; then
    echo "$OUTPUT"
  else
    exit 3
  fi
else
  exit $EXIT_CODE
fi
