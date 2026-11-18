#!/usr/bin/env bash
# Self-test for check-sync-audit.sh:
# 1. Real repository sync directory passes (clean prod, allowlisted dev advisory GHSA-fc5q-39xc-vjpf).
# 2. Clean audit fixture (0 prod, 0 dev vulnerabilities) passes.
# 3. Allowlisted dev advisory fixture passes.
# 4. Unapproved dev advisory fixture fails.
# 5. Production vulnerability failure causes check to fail immediately.
# 6. Custom ALLOWED_ADVISORIES override is respected.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-sync-audit.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$check" ]; then
  echo "FAIL: $check does not exist or is not executable" >&2
  exit 1
fi

# Fixture: clean audit JSON
cat > "$tmp/clean.json" << 'EOF'
{
  "auditReportVersion": 2,
  "vulnerabilities": {},
  "metadata": {
    "vulnerabilities": {
      "info": 0, "low": 0, "moderate": 0, "high": 0, "critical": 0, "total": 0
    }
  }
}
EOF

# Fixture: allowed dev advisory (GHSA-fc5q-39xc-vjpf on picomatch with transitive dependents)
cat > "$tmp/allowed_dev.json" << 'EOF'
{
  "auditReportVersion": 2,
  "vulnerabilities": {
    "picomatch": {
      "name": "picomatch",
      "severity": "high",
      "isDirect": false,
      "via": [
        {
          "source": 1247710,
          "name": "picomatch",
          "dependency": "picomatch",
          "title": "picomatch vulnerable to ReDoS through single-branch repeated extglobs",
          "url": "https://github.com/advisories/GHSA-fc5q-39xc-vjpf",
          "severity": "high"
        }
      ],
      "effects": ["vitest"]
    },
    "vitest": {
      "name": "vitest",
      "severity": "high",
      "isDirect": true,
      "via": ["picomatch"],
      "effects": []
    }
  },
  "metadata": {
    "vulnerabilities": {
      "info": 0, "low": 0, "moderate": 0, "high": 2, "critical": 0, "total": 2
    }
  }
}
EOF

# Fixture: unapproved dev advisory (e.g. malicious-pkg with GHSA-9999-9999-9999)
cat > "$tmp/unapproved_dev.json" << 'EOF'
{
  "auditReportVersion": 2,
  "vulnerabilities": {
    "malicious-pkg": {
      "name": "malicious-pkg",
      "severity": "high",
      "isDirect": true,
      "via": [
        {
          "source": 999999,
          "name": "malicious-pkg",
          "dependency": "malicious-pkg",
          "title": "Remote code execution in malicious-pkg",
          "url": "https://github.com/advisories/GHSA-9999-9999-9999",
          "severity": "high"
        }
      ],
      "effects": []
    }
  },
  "metadata": {
    "vulnerabilities": {
      "info": 0, "low": 0, "moderate": 0, "high": 1, "critical": 0, "total": 1
    }
  }
}
EOF

# 1. Clean audit fixture must pass
echo "Testing clean audit fixture..."
AUDIT_PROD_EXIT=0 AUDIT_DEV_JSON_FILE="$tmp/clean.json" "$check" >/dev/null

# 2. Allowlisted dev advisory must pass with default allowlist
echo "Testing allowlisted dev advisory fixture..."
AUDIT_PROD_EXIT=0 AUDIT_DEV_JSON_FILE="$tmp/allowed_dev.json" "$check" >/dev/null

# 3. Unapproved dev advisory must fail with default allowlist
echo "Testing unapproved dev advisory fixture..."
if AUDIT_PROD_EXIT=0 AUDIT_DEV_JSON_FILE="$tmp/unapproved_dev.json" "$check" >/dev/null 2>&1; then
  echo "FAIL: unapproved dev advisory should have been rejected" >&2
  exit 1
fi

# 4. Production vulnerability must fail even if dev audit is clean
echo "Testing production vulnerability failure..."
if AUDIT_PROD_EXIT=1 AUDIT_DEV_JSON_FILE="$tmp/clean.json" "$check" >/dev/null 2>&1; then
  echo "FAIL: production audit failure should have caused check to exit non-zero" >&2
  exit 1
fi

# 5. Production vulnerability must fail even if dev advisory is allowlisted
if AUDIT_PROD_EXIT=1 AUDIT_DEV_JSON_FILE="$tmp/allowed_dev.json" "$check" >/dev/null 2>&1; then
  echo "FAIL: production audit failure should have caused check to exit non-zero" >&2
  exit 1
fi

# 6. Custom ALLOWED_ADVISORIES override should permit custom advisory and reject default
echo "Testing custom ALLOWED_ADVISORIES override..."
ALLOWED_ADVISORIES="GHSA-9999-9999-9999" AUDIT_PROD_EXIT=0 AUDIT_DEV_JSON_FILE="$tmp/unapproved_dev.json" "$check" >/dev/null
if ALLOWED_ADVISORIES="GHSA-9999-9999-9999" AUDIT_PROD_EXIT=0 AUDIT_DEV_JSON_FILE="$tmp/allowed_dev.json" "$check" >/dev/null 2>&1; then
  echo "FAIL: default advisory should have been rejected when ALLOWED_ADVISORIES is overridden" >&2
  exit 1
fi

# 7. Real repository sync directory must pass
echo "Testing real sync backend directory..."
"$check" >/dev/null

echo "ok"
