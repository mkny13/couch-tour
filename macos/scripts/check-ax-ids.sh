#!/bin/bash
# Check that all AXIdentifiers constants are actually used in the codebase

FAILED=0
CONSTANTS=$(grep -oE 'static let [a-zA-Z0-9_]+' macos/CouchTour/AXIdentifiers.swift | awk '{print $3}')
UNREFERENCED=""

for const in $CONSTANTS; do
    if ! grep -rqw "$const" --exclude="AXIdentifiers.swift" macos/CouchTour; then
        UNREFERENCED="$UNREFERENCED $const"
        FAILED=1
    fi
done

if [ $FAILED -ne 0 ]; then
    echo "Unreferenced AXIdentifiers found:"
    for const in $UNREFERENCED; do
        echo "  - $const"
    done
    exit 1
fi

echo "All AXIdentifiers are referenced."
exit 0
