#!/usr/bin/env bash
# Hermetic test for scripts/publish-appcast.sh against a local bare repo (D341).
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
script="$root/scripts/publish-appcast.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
ok() { echo "ok   - $1"; }
bad() { echo "FAIL - $1"; fails=$((fails + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
git init -q --bare "$tmp/remote.git"
git clone -q "$tmp/remote.git" "$tmp/work" 2>/dev/null
cd "$tmp/work"
git checkout -q -b main
mkdir -p macos/Packages/CouchTourKit
echo "legacy-prod" > appcast.xml
echo "legacy-beta" > appcast-beta.xml
echo "resolved" > macos/Packages/CouchTourKit/Package.resolved
echo "x" > unrelated.txt
git add -A && git commit -qm init && git push -q origin main

echo "beta-v1" > "$tmp/new-beta.xml"

# (a) creates the orphan appcast branch seeded from main
bash "$script" "$tmp/new-beta.xml" appcast-beta.xml v1 origin >/dev/null 2>&1
check "(a) appcast branch created" "git -C '$tmp/remote.git' rev-parse --verify -q refs/heads/appcast >/dev/null"
check "(a) seeded with main's prod feed" "[ \"\$(git -C '$tmp/remote.git' show appcast:appcast.xml)\" = legacy-prod ]"
check "(a) beta feed updated" "[ \"\$(git -C '$tmp/remote.git' show appcast:appcast-beta.xml)\" = beta-v1 ]"
check "(a) orphan: no shared history with main" "! git -C '$tmp/remote.git' merge-base appcast main >/dev/null 2>&1"
check "(a) branch holds only appcast files" "[ \"\$(git -C '$tmp/remote.git' ls-tree --name-only appcast | tr '\n' ' ')\" = 'appcast-beta.xml appcast.xml ' ]"

# (b) only the appcast is committed despite a dirty caller tree
echo "dirty" >> macos/Packages/CouchTourKit/Package.resolved
echo "staged" > staged.txt && git add staged.txt
echo "beta-v2" > "$tmp/new-beta.xml"
bash "$script" "$tmp/new-beta.xml" appcast-beta.xml v2 origin >/dev/null 2>&1
check "(b) publish succeeded" "[ \"\$(git -C '$tmp/remote.git' show appcast:appcast-beta.xml)\" = beta-v2 ]"
check "(b) commit touches only the appcast" "[ \"\$(git -C '$tmp/remote.git' show --name-only --format= appcast)\" = appcast-beta.xml ]"
check "(b) caller's staged file untouched" "git diff --cached --name-only | grep -qx staged.txt"
check "(b) caller's Package.resolved still dirty" "git diff --name-only | grep -qx macos/Packages/CouchTourKit/Package.resolved"
git reset -q && git checkout -q -- . && rm -f staged.txt

# (c) non-fast-forward: a competing commit lands before our first push
echo "beta-v3" > "$tmp/new-beta.xml"
hook="$tmp/hook.sh"
cat > "$hook" <<HOOK
if [ ! -e "$tmp/hook-ran" ]; then
  touch "$tmp/hook-ran"
  c=\$(mktemp -d)
  git clone -q "$tmp/remote.git" "\$c/c" 2>/dev/null
  git -C "\$c/c" checkout -q appcast
  echo competing > "\$c/c/appcast.xml"
  git -C "\$c/c" commit -qam competing
  git -C "\$c/c" push -q origin appcast
  rm -rf "\$c"
fi
HOOK
PUBLISH_APPCAST_BEFORE_PUSH="bash $hook" bash "$script" "$tmp/new-beta.xml" appcast-beta.xml v3 origin >/dev/null 2>&1
check "(c) retry landed our commit" "[ \"\$(git -C '$tmp/remote.git' show appcast:appcast-beta.xml)\" = beta-v3 ]"
check "(c) competing commit kept" "[ \"\$(git -C '$tmp/remote.git' show appcast:appcast.xml)\" = competing ]"

# (d) no change exits 0 without a new commit
before=$(git -C "$tmp/remote.git" rev-parse appcast)
bash "$script" "$tmp/new-beta.xml" appcast-beta.xml v3 origin >/dev/null 2>&1
check "(d) no-change exits 0" "[ \$? -eq 0 ]"
check "(d) no new commit" "[ \"\$(git -C '$tmp/remote.git' rev-parse appcast)\" = \"$before\" ]"

# (e) refuses main
mainbefore=$(git -C "$tmp/remote.git" rev-parse main)
APPCAST_BRANCH=main bash "$script" "$tmp/new-beta.xml" appcast-beta.xml v4 origin >/dev/null 2>&1
rc=$?
check "(e) refuses main (non-zero)" "[ $rc -ne 0 ]"
check "(e) main untouched" "[ \"\$(git -C '$tmp/remote.git' rev-parse main)\" = \"$mainbefore\" ]"

# (f) static guards on workflow and project config
check "(f) macos-release.yml never pushes to main" "! grep -q 'git push origin main' '$root/.github/workflows/macos-release.yml'"
check "(f) macos-release.yml publishes via script" "grep -q 'scripts/publish-appcast.sh' '$root/.github/workflows/macos-release.yml'"
check "(f) both SUFeedURLs target the appcast branch" "[ \"\$(grep 'SUFeedURL' '$root/macos/project.yml' | grep -c '/couch-tour/appcast/appcast')\" = 2 ] && [ \"\$(grep -c 'SUFeedURL' '$root/macos/project.yml')\" = 2 ]"

[ "$fails" -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
