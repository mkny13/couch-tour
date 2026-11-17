#!/usr/bin/env bash
# Self-test for check-shellcheck.sh: passes on repo scripts, passes on a clean script,
# and fails on a script with shell array ambiguity or syntax errors (e.g. SC1087).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-shellcheck.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$check" ]; then
  echo "FAIL: $check does not exist or is not executable" >&2
  exit 1
fi

# 1. Real repository scripts should pass
"$check" || { echo "FAIL: real repository scripts should pass shellcheck" >&2; exit 1; }

# 2. Clean script in temp directory should pass
cat > "$tmp/clean.sh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
msg="hello"
echo "$msg"
EOF
TARGET_DIR="$tmp" "$check" || { echo "FAIL: clean script should pass shellcheck" >&2; exit 1; }

# 3. Script with array ambiguity / syntax error (SC1087, from #805) should fail
cat > "$tmp/bad.sh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
fail_id="abc"
echo "$fail_id["
EOF
if TARGET_DIR="$tmp" "$check" >/dev/null 2>&1; then
  echo "FAIL: script with SC1087 array ambiguity should fail shellcheck" >&2
  exit 1
fi

echo "ok"
