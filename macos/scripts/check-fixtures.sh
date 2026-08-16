#!/usr/bin/env bash
# Guards against the macOS and Android test fixtures drifting apart. Both clients parse the
# same trimmed real API responses (see DECISIONS.md D35 and the plan's M1 notes), so a
# fixture that changes on one side without the other would let the two clients silently start
# testing against different data.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
android_fixtures="$repo_root/app/src/test/resources/fixtures"
macos_fixtures="$repo_root/macos/Packages/CouchTourKit/Tests/CouchTourKitTests/Fixtures"

status=0
for f in "$macos_fixtures"/*.json; do
    name="$(basename "$f")"
    android_f="$android_fixtures/$name"
    if [ ! -f "$android_f" ]; then
        echo "macOS fixture '$name' has no Android counterpart at $android_f"
        status=1
        continue
    fi
    if ! diff -q "$f" "$android_f" > /dev/null; then
        echo "Fixture '$name' differs between macOS and Android copies:"
        echo "  $android_f"
        echo "  $f"
        status=1
    fi
done

if [ "$status" -eq 0 ]; then
    echo "Fixtures match."
fi
exit $status
