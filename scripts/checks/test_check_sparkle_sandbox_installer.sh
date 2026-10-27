#!/usr/bin/env bash
# Self-test for check-sparkle-sandbox-installer.sh: verifies that check-sparkle-sandbox-installer.sh
# passes when required Sparkle sandbox configurations are present, fails when any of them
# are missing or disabled for sandboxed targets, and allows non-sandboxed targets without them.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-sparkle-sandbox-installer.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 1. Valid sandboxed target with list format passes
cat > "$tmp/valid_list.yml" << 'EOF'
targets:
  AppTarget:
    type: application
    info:
      properties:
        SUEnableInstallerLauncherService: true
    entitlements:
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.temporary-exception.mach-lookup.global-name:
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spks"
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spki"
EOF
"$check" "$tmp/valid_list.yml" >/dev/null || {
  echo "FAIL: valid list format should pass" >&2
  exit 1
}

# 2. Valid sandboxed target with inline bracket array format passes
cat > "$tmp/valid_inline.yml" << 'EOF'
targets:
  AppTarget:
    type: application
    info:
      properties:
        SUEnableInstallerLauncherService: true
    entitlements:
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.temporary-exception.mach-lookup.global-name: ["$(PRODUCT_BUNDLE_IDENTIFIER)-spks", "$(PRODUCT_BUNDLE_IDENTIFIER)-spki"]
EOF
"$check" "$tmp/valid_inline.yml" >/dev/null || {
  echo "FAIL: valid inline array format should pass" >&2
  exit 1
}

# 3. Missing SUEnableInstallerLauncherService fails
cat > "$tmp/missing_launcher.yml" << 'EOF'
targets:
  AppTarget:
    type: application
    info:
      properties:
        CFBundleDisplayName: "App"
    entitlements:
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.temporary-exception.mach-lookup.global-name:
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spks"
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spki"
EOF
if "$check" "$tmp/missing_launcher.yml" 2>/dev/null; then
  echo "FAIL: missing SUEnableInstallerLauncherService should fail" >&2
  exit 1
fi

# 4. SUEnableInstallerLauncherService: false fails
cat > "$tmp/false_launcher.yml" << 'EOF'
targets:
  AppTarget:
    type: application
    info:
      properties:
        SUEnableInstallerLauncherService: false
    entitlements:
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.temporary-exception.mach-lookup.global-name:
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spks"
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spki"
EOF
if "$check" "$tmp/false_launcher.yml" 2>/dev/null; then
  echo "FAIL: SUEnableInstallerLauncherService=false should fail" >&2
  exit 1
fi

# 5. Missing mach-lookup entitlement fails
cat > "$tmp/missing_mach_lookup.yml" << 'EOF'
targets:
  AppTarget:
    type: application
    info:
      properties:
        SUEnableInstallerLauncherService: true
    entitlements:
      properties:
        com.apple.security.app-sandbox: true
EOF
if "$check" "$tmp/missing_mach_lookup.yml" 2>/dev/null; then
  echo "FAIL: missing mach-lookup entitlement should fail" >&2
  exit 1
fi

# 6. Incomplete mach-lookup (missing -spki) fails
cat > "$tmp/missing_spki.yml" << 'EOF'
targets:
  AppTarget:
    type: application
    info:
      properties:
        SUEnableInstallerLauncherService: true
    entitlements:
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.temporary-exception.mach-lookup.global-name:
          - "$(PRODUCT_BUNDLE_IDENTIFIER)-spks"
EOF
if "$check" "$tmp/missing_spki.yml" 2>/dev/null; then
  echo "FAIL: missing spki service should fail" >&2
  exit 1
fi

# 7. Non-sandboxed target passes without Sparkle launcher config
cat > "$tmp/unsandboxed.yml" << 'EOF'
targets:
  HelperTool:
    type: tool
    entitlements:
      properties:
        com.apple.security.app-sandbox: false
  AnotherTarget:
    type: bundle.unit-testing
EOF
"$check" "$tmp/unsandboxed.yml" >/dev/null || {
  echo "FAIL: non-sandboxed target should pass without Sparkle launcher config" >&2
  exit 1
}

# 8. Missing file fails
if "$check" "$tmp/nonexistent.yml" 2>/dev/null; then
  echo "FAIL: nonexistent file should fail" >&2
  exit 1
fi

echo "ok"
