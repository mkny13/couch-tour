#!/usr/bin/env bash
# Audits sync backend dependencies (#839, updating D324, D358).
# Strictly enforces 0 high/critical production vulnerabilities (--omit=dev).
# Audits dev dependencies, permitting only documented/withdrawn/accepted advisories
# (specifically GHSA-fc5q-39xc-vjpf on picomatch, withdrawn upstream by GitHub).
#
# Environment variables:
#   SYNC_DIR: directory to audit (default: <repo_root>/sync)
#   AUDIT_PROD_CMD: command to run for production audit
#   AUDIT_DEV_CMD: command to run for dev audit
#   AUDIT_DEV_JSON_FILE: file to read dev audit JSON from (overrides running npm)
#   AUDIT_PROD_EXIT: mock exit code for prod audit (overrides running npm)
#   ALLOWED_ADVISORIES: space- or comma-separated list of accepted advisory IDs
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
sync_dir="${SYNC_DIR:-$repo_root/sync}"

# Default allowlist of accepted dev-only advisories:
# - GHSA-fc5q-39xc-vjpf: picomatch ReDoS through single-branch repeated extglobs.
#   Withdrawn upstream on GitHub as an invalid AI-generated report, but npm registry
#   still serves it with no patch available. Safe in dev-only vitest test runner.
default_allowed="GHSA-fc5q-39xc-vjpf"
allowed_advisories="${ALLOWED_ADVISORIES:-$default_allowed}"

echo "=== Step 1: Checking sync production runtime dependencies ==="
if [ -n "${AUDIT_PROD_EXIT:-}" ]; then
  prod_rc="$AUDIT_PROD_EXIT"
  if [ "$prod_rc" -ne 0 ]; then
    echo "FAIL: Production runtime dependencies have high/critical vulnerabilities (mocked exit $prod_rc)" >&2
    exit 1
  fi
elif [ -n "${AUDIT_PROD_CMD:-}" ]; then
  if ! eval "$AUDIT_PROD_CMD"; then
    echo "FAIL: Production runtime dependencies have high/critical vulnerabilities" >&2
    exit 1
  fi
else
  if ! (cd "$sync_dir" && npm audit --omit=dev --audit-level=high); then
    echo "FAIL: Production runtime dependencies have high/critical vulnerabilities" >&2
    exit 1
  fi
fi
echo "Production runtime audit clean: 0 vulnerabilities found."

echo "=== Step 2: Checking sync dev dependencies against allowlist ==="
json_data=""
if [ -n "${AUDIT_DEV_JSON_FILE:-}" ]; then
  if [ ! -f "$AUDIT_DEV_JSON_FILE" ]; then
    echo "FAIL: audit json file not found: $AUDIT_DEV_JSON_FILE" >&2
    exit 1
  fi
  json_data="$(cat "$AUDIT_DEV_JSON_FILE")"
elif [ -n "${AUDIT_DEV_CMD:-}" ]; then
  json_data="$(eval "$AUDIT_DEV_CMD" || true)"
else
  # npm audit exits non-zero if vulnerabilities are found, but still outputs JSON
  json_data="$( (cd "$sync_dir" && npm audit --audit-level=high --json) || true)"
fi

python3 - "$allowed_advisories" "$json_data" << 'PYEOF'
import json, re, sys

allowed_input = sys.argv[1]
allowed = {a.strip() for a in re.split(r'[, \t\n]+', allowed_input) if a.strip()}
raw_json = sys.argv[2]

if not raw_json.strip():
    print("FAIL: empty dev audit output (expected JSON audit report)", file=sys.stderr)
    sys.exit(1)

try:
    data = json.loads(raw_json)
except Exception as e:
    print(f"FAIL: could not parse npm audit JSON: {e}\nRaw output:\n{raw_json}", file=sys.stderr)
    sys.exit(1)

if not isinstance(data, dict):
    print(f"FAIL: npm audit JSON is not an object:\n{raw_json}", file=sys.stderr)
    sys.exit(1)

if "error" in data:
    err = data["error"]
    msg = data.get("message", "")
    print(f"FAIL: npm audit returned error: {err} {msg}".strip(), file=sys.stderr)
    sys.exit(1)

if "errors" in data:
    print(f"FAIL: npm audit returned errors: {data['errors']}", file=sys.stderr)
    sys.exit(1)

if "vulnerabilities" not in data:
    print(f"FAIL: npm audit JSON missing 'vulnerabilities' field:\n{raw_json}", file=sys.stderr)
    sys.exit(1)

vulnerabilities = data["vulnerabilities"]
if not isinstance(vulnerabilities, dict):
    print(f"FAIL: npm audit 'vulnerabilities' field is not an object:\n{raw_json}", file=sys.stderr)
    sys.exit(1)

if not vulnerabilities:
    print("Dev audit clean: 0 dev vulnerabilities found.")
    sys.exit(0)

unapproved_root_advisories = []
approved_root_advisories = []
approved_pkgs = set()

# Pass 1: inspect root advisories in via objects
for pkg, v in vulnerabilities.items():
    vias = v.get("via", [])
    dict_vias = [item for item in vias if isinstance(item, dict)]
    if not dict_vias:
        continue
    all_pkg_advisories_approved = True
    for adv in dict_vias:
        url = adv.get("url", "")
        m = re.search(r'GHSA-[a-zA-Z0-9_-]+', url)
        adv_id = m.group(0) if m else adv.get("source") or url
        record = (str(adv_id), pkg, adv)
        if adv_id in allowed:
            if record not in approved_root_advisories:
                approved_root_advisories.append(record)
        else:
            all_pkg_advisories_approved = False
            if record not in unapproved_root_advisories:
                unapproved_root_advisories.append(record)
    if all_pkg_advisories_approved and dict_vias:
        approved_pkgs.add(pkg)

# Pass 2: propagate approval to transitive dependencies whose vias are all approved packages
changed = True
while changed:
    changed = False
    for pkg, v in vulnerabilities.items():
        if pkg in approved_pkgs:
            continue
        vias = v.get("via", [])
        string_vias = [s for s in vias if isinstance(s, str)]
        dict_vias = [d for d in vias if isinstance(d, dict)]
        if not dict_vias and string_vias and all(s in approved_pkgs for s in string_vias):
            approved_pkgs.add(pkg)
            changed = True

unapproved_pkgs = set(vulnerabilities.keys()) - approved_pkgs

if unapproved_root_advisories or unapproved_pkgs:
    print(f"FAIL: Found {len(unapproved_root_advisories)} unapproved root advisories and {len(unapproved_pkgs)} unapproved vulnerable packages:", file=sys.stderr)
    for adv_id, pkg, adv in unapproved_root_advisories:
        print(f"  - [{adv.get('severity', 'high').upper()}] {adv_id} in {pkg}: {adv.get('title', '')} ({adv.get('url', '')})", file=sys.stderr)
    for pkg in sorted(unapproved_pkgs):
        if not any(pkg == a[1] for a in unapproved_root_advisories):
            print(f"  - Package '{pkg}' has unapproved transitive vulnerability (via {vulnerabilities[pkg].get('via')})", file=sys.stderr)
    sys.exit(1)

print(f"Dev audit passed with {len(approved_root_advisories)} approved dev-only advisories:")
for adv_id, pkg, adv in approved_root_advisories:
    print(f"  - Accepted: {adv_id} in {pkg} ({adv.get('title', '')} - {adv.get('url', '')})")
sys.exit(0)
PYEOF
