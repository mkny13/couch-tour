#!/usr/bin/env bash
# Self-test for check-mockup-data.sh: plants violations in a temp tree via REPO_ROOT.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-mockup-data.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fails=0

fresh() {
  rm -rf "$tmp/r"; mkdir -p "$tmp/r/app/src/main" "$tmp/r/macos/CouchTour" "$tmp/r/scripts/checks"
}
expect() { # expect <0|1> <name>
  local want="$1" name="$2" rc=0
  REPO_ROOT="$tmp/r" "$check" >"$tmp/out" 2>&1 || rc=$?
  if [ "$rc" -ne "$want" ]; then echo "FAIL: $name (exit $rc, wanted $want)"; cat "$tmp/out"; fails=1
  else echo "ok: $name"; fi
}

fresh; expect 0 "clean tree passes"

fresh; printf 'val a = listOf("Goose")\n' >"$tmp/r/app/src/main/A.kt"
expect 1 "Goose literal in Kotlin fails"
grep -q 'A.kt:1:' "$tmp/out" || { echo "FAIL: no path:line in message"; fails=1; }

fresh; printf 'let a = ["Goose"]\n' >"$tmp/r/macos/CouchTour/A.swift"
expect 1 "Goose literal in Swift fails"

fresh; printf '@Preview\n@Composable\nfun P() {\n    Foo(listOf("Goose"))\n}\nval ok = 1\n' >"$tmp/r/app/src/main/A.kt"
expect 0 "Goose inside @Preview passes"

fresh; printf '#Preview {\n    V(a: ["Goose"])\n}\nstruct X_Previews: PreviewProvider {\n    static var previews: some View {\n        V(a: ["Goose"])\n    }\n}\n' >"$tmp/r/macos/CouchTour/A.swift"
expect 0 "Goose inside #Preview / PreviewProvider passes"

fresh; printf '@Preview\nfun P() {\n}\nval a = "Goose"\n' >"$tmp/r/app/src/main/A.kt"
expect 1 "hit after a preview ends still fails"

fresh; mkdir -p "$tmp/r/app/src/test" "$tmp/r/macos/Packages/CouchTourKit/Tests"
printf 'val a = "Goose"\n' >"$tmp/r/app/src/test/A.kt"
printf 'let a = "Goose"\n' >"$tmp/r/macos/Packages/CouchTourKit/Tests/A.swift"
expect 0 "test directories are ignored"

fresh; printf 'val a = "Goose"\n' >"$tmp/r/app/src/main/A.kt"
printf 'app/src/main/A.kt:"Goose"  # real artist table\n' >"$tmp/r/scripts/checks/mockup-allowlist.txt"
expect 0 "allowlisted hit passes"

fresh; printf 'val a = "Goose"\n' >"$tmp/r/app/src/main/A.kt"
printf 'app/src/main/A.kt:"Goose"\n' >"$tmp/r/scripts/checks/mockup-allowlist.txt"
expect 1 "allowlist entry without a reason fails"

exit "$fails"
