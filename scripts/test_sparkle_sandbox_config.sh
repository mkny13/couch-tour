#!/usr/bin/env bash
# Guards #774: the macOS apps are sandboxed (D240), and a sandboxed Sparkle client can only
# launch its installer with SUEnableInstallerLauncherService plus mach-lookup entitlements for
# the -spks / -spki XPC services. Without them every update fails with "An error occurred
# while launching the installer".
#
# Fails unless each sandboxed target in macos/project.yml has all three.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
YML="$REPO_ROOT/macos/project.yml"

sandboxed=$(grep -c 'com.apple.security.app-sandbox: true' "$YML" || true)
[ "$sandboxed" -ge 1 ] || { echo "FAIL: no sandboxed target found in $YML; update this guard" >&2; exit 1; }

for needle in 'SUEnableInstallerLauncherService: true' \
              'mach-lookup.global-name:' \
              '$(PRODUCT_BUNDLE_IDENTIFIER)-spks' \
              '$(PRODUCT_BUNDLE_IDENTIFIER)-spki'; do
    n=$(grep -cF -- "$needle" "$YML" || true)
    [ "$n" -ge "$sandboxed" ] \
        || { echo "FAIL: '$needle' appears $n time(s) in project.yml, but $sandboxed target(s) are sandboxed" >&2; exit 1; }
done

echo "PASS: every sandboxed macOS target can launch the Sparkle installer"
