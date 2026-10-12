#!/usr/bin/env bash
# Publish a Sparkle appcast to the unprotected `appcast` branch (D341).
# Usage: publish-appcast.sh <appcast-file> <appcast-name> <tag> [remote]
#
# Works in a throwaway worktree so a dirty build checkout (xcodebuild rewrites
# CouchTourKit's Package.resolved) can't break rebases, and never touches main:
# main requires the `test` check, which a direct push from CI can't satisfy.
set -euo pipefail

if [ $# -lt 3 ]; then
  echo "usage: $0 <appcast-file> <appcast-name> <tag> [remote]" >&2
  exit 1
fi
src=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
name=$2
tag=$3
remote=${4:-origin}
branch=${APPCAST_BRANCH:-appcast}
# Test seam: a command run just before each push, to simulate a concurrent push.
before_push=${PUBLISH_APPCAST_BEFORE_PUSH:-}

if [ "$branch" = "main" ] || [ "$branch" = "refs/heads/main" ]; then
  echo "error: refusing to publish the appcast to main (protected); use the appcast branch" >&2
  exit 1
fi
[ -f "$src" ] || { echo "error: appcast file not found: $src" >&2; exit 1; }

tmp=$(mktemp -d)
wt="$tmp/wt"
cleanup() {
  git worktree remove --force "$wt" >/dev/null 2>&1 || true
  git branch -D "publish-appcast-$$" >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

ident=()
git config user.name >/dev/null || ident+=(-c user.name=github-actions[bot])
git config user.email >/dev/null || ident+=(-c user.email=github-actions[bot]@users.noreply.github.com)

git worktree add --detach "$wt" HEAD >/dev/null

for attempt in 1 2 3 4 5; do
  exists=0
  if git ls-remote --exit-code --heads "$remote" "$branch" >/dev/null 2>&1; then
    exists=1
    git fetch --quiet "$remote" "$branch"
    # FETCH_HEAD is per-worktree, so resolve it here and hand over the SHA.
    tip=$(git rev-parse FETCH_HEAD)
    git -C "$wt" checkout --quiet --detach "$tip"
    git -C "$wt" reset --quiet --hard "$tip"
  else
    # First publish: orphan branch seeded with main's current feeds so existing
    # items stay in the feed.
    git fetch --quiet "$remote" main
    seed=$(git rev-parse FETCH_HEAD)
    git -C "$wt" checkout --quiet --orphan "publish-appcast-$$"
    git -C "$wt" rm -rfq . 2>/dev/null || true
    git -C "$wt" clean -fdxq
    for f in appcast.xml appcast-beta.xml; do
      if git cat-file -e "$seed:$f" 2>/dev/null; then
        git show "$seed:$f" > "$wt/$f"
        git -C "$wt" add "$f"
      fi
    done
    git -C "$wt" ${ident[@]+"${ident[@]}"} commit --quiet --allow-empty -m "Seed appcast branch from main"
  fi

  cp "$src" "$wt/$name"
  git -C "$wt" add -- "$name"
  if git -C "$wt" diff --cached --quiet; then
    echo "No appcast changes to commit."
    # On first publish the seed commit still has to land, so only stop early
    # when the branch already exists.
    [ "$exists" -eq 1 ] && exit 0
  else
    git -C "$wt" ${ident[@]+"${ident[@]}"} commit --quiet -m "Update $name for $tag"
  fi

  [ -z "$before_push" ] || eval "$before_push"
  if git -C "$wt" push --quiet "$remote" "HEAD:refs/heads/$branch"; then
    echo "Published $name for $tag to $branch."
    exit 0
  fi
  echo "Push rejected (attempt $attempt/5), refetching and retrying..." >&2
done

echo "error: could not publish $name to $branch after 5 attempts" >&2
exit 1
