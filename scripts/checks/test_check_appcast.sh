#!/usr/bin/env bash
# scripts/checks/test_check_appcast.sh
# Hermetic unit tests for scripts/checks/check_appcast.py (issue #781).
set -euo pipefail

failures=0
ok() { printf 'ok   - %s\n' "$1"; }
bad() { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_PY="$SCRIPT_DIR/check_appcast.py"
FIXTURES_DIR="$SCRIPT_DIR/testdata/appcast-fixtures"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BIN_DIR="$TMP/bin"
mkdir -p "$BIN_DIR"

# Fake gh: handles `api repos/<repo>/releases/tags/<tag>` and `release download`
cat > "$BIN_DIR/gh" << 'EOF'
#!/bin/sh
case "$1 $2" in
    "api repos/"*)
        cat << 'JSON'
{
  "tag_name": "v0.99",
  "assets": [
    {
      "name": "CouchTour-v0.99.zip",
      "size": 12614154,
      "browser_download_url": "https://github.com/mkny13/couch-tour/releases/download/v0.99/CouchTour-v0.99.zip"
    }
  ]
}
JSON
        exit 0
        ;;
    "release download")
        dir="."
        pattern=""
        while [ $# -gt 0 ]; do
            case "$1" in
                --dir|-D) dir="$2"; shift 2 ;;
                --pattern|-p) pattern="$2"; shift 2 ;;
                *) shift ;;
            esac
        done
        mkdir -p "$dir"
        echo "fake-zip-binary-data" > "$dir/$pattern"
        exit 0
        ;;
    *)
        echo "mock gh unhandled: $@" >&2
        exit 1
        ;;
esac
EOF
chmod +x "$BIN_DIR/gh"

# Fake openssl: succeeds by default unless OPENSSL_FAIL=1
cat > "$BIN_DIR/openssl" << 'EOF'
#!/bin/sh
if [ "${OPENSSL_FAIL:-0}" = "1" ]; then
    echo "Signature Verification Failure" >&2
    exit 1
fi
echo "Signature Verified Successfully"
exit 0
EOF
chmod +x "$BIN_DIR/openssl"

export PATH="$BIN_DIR:$PATH"

# 1. Unique feed passes check
rc=0
python3 -I "$CHECK_PY" check "$FIXTURES_DIR/unique-feed.xml" >/dev/null 2>&1 || rc=$?
check "1. unique feed passes check" [ "$rc" -eq 0 ]

# 2. Duplicate 0.99 feed fails check and reports 0.99
rc=0
out2="$TMP/out2.log"
python3 -I "$CHECK_PY" check "$FIXTURES_DIR/dup-version.xml" >"$out2" 2>&1 || rc=$?
check "2. duplicate feed fails check" [ "$rc" -ne 0 ]
check "2. duplicate feed output names 0.99" grep -q "0.99" "$out2"

# 3. Legacy attribute-form item is parsed; duplicate legacy attribute fails check
rc=0
out3="$TMP/out3.log"
python3 -I "$CHECK_PY" check "$FIXTURES_DIR/legacy-attr.xml" >"$out3" 2>&1 || rc=$?
check "3. legacy attribute feed fails check on duplicate" [ "$rc" -ne 0 ]
check "3. legacy attribute error names 0.51" grep -q "0.51" "$out3"

# 4. drop-version removes only the specified version
F4="$TMP/feed4.xml"
cp "$FIXTURES_DIR/dup-version.xml" "$F4"
rc=0
python3 -I "$CHECK_PY" drop-version "$F4" "0.99" || rc=$?
check "4. drop-version exits 0" [ "$rc" -eq 0 ]
check "4. drop-version removed 0.99" bash -c "! grep -q '<sparkle:shortVersionString>0.99</sparkle:shortVersionString>' '$F4'"
check "4. drop-version preserved 0.97" grep -q "0.97" "$F4"
check "4. drop-version preserved 0.51" grep -q "0.51" "$F4"
rc_check=0
python3 -I "$CHECK_PY" check "$F4" || rc_check=$?
check "4. feed passes check after drop-version" [ "$rc_check" -eq 0 ]

# 5. repair keeps the length-matching item (build 53) and drops build 54
F5="$TMP/feed5.xml"
cp "$FIXTURES_DIR/dup-version.xml" "$F5"
rc=0
out5="$TMP/out5.log"
python3 -I "$CHECK_PY" repair "$F5" --repo mkny13/couch-tour >"$out5" 2>&1 || rc=$?
check "5. repair exits 0" [ "$rc" -eq 0 ]
check "5. repair removed build 54 (length 12614160)" bash -c "! grep -q 'length=\"12614160\"' '$F5'"
check "5. repair kept build 53 (length 12614154)" grep -q 'length="12614154"' "$F5"
check "5. repair kept 0.97" grep -q "0.97" "$F5"
check "5. repair kept 0.51" grep -q "0.51" "$F5"
rc_check5=0
python3 -I "$CHECK_PY" check "$F5" || rc_check5=$?
check "5. feed passes check after repair" [ "$rc_check5" -eq 0 ]

# 6. repair refuses and changes nothing when no item matches asset size
F6="$TMP/feed6.xml"
cp "$FIXTURES_DIR/no-match.xml" "$F6"
orig_hash="$(sha256sum "$F6" | cut -d' ' -f1)"
rc=0
out6="$TMP/out6.log"
python3 -I "$CHECK_PY" repair "$F6" --repo mkny13/couch-tour >"$out6" 2>&1 || rc=$?
new_hash="$(sha256sum "$F6" | cut -d' ' -f1)"
check "6. repair fails when no item matches" [ "$rc" -ne 0 ]
check "6. repair changes nothing on failure" [ "$orig_hash" = "$new_hash" ]

# 7. repair fails if signature verification fails
F7="$TMP/feed7.xml"
cp "$FIXTURES_DIR/dup-version.xml" "$F7"
orig_hash7="$(sha256sum "$F7" | cut -d' ' -f1)"
rc=0
out7="$TMP/out7.log"
OPENSSL_FAIL=1 python3 -I "$CHECK_PY" repair "$F7" --repo mkny13/couch-tour >"$out7" 2>&1 || rc=$?
new_hash7="$(sha256sum "$F7" | cut -d' ' -f1)"
check "7. repair fails when openssl verification fails" [ "$rc" -ne 0 ]
check "7. repair changes nothing when signature fails" [ "$orig_hash7" = "$new_hash7" ]

if [ "$failures" -gt 0 ]; then
    echo "$failures test(s) failed." >&2
    exit 1
fi
echo "All check_appcast tests passed."
