# Couch Tour

An unofficial native client for [phish.in](https://phish.in), the open-source live Phish
archive, and for Relisten's other-artist catalog. Two clients live in this repo: an Android
app (Kotlin, Jetpack Compose, Media3, Room) and a macOS app (Swift, SwiftUI, AVFoundation,
GRDB), plus `sync/`, a Cloudflare Worker + D1 backend the two sync progress through — pairing,
push/pull, and history/resume now verified working live between a real phone and Mac
(D116-D148). See
[README.md](README.md) for what the app does, [DECISIONS.md](DECISIONS.md) for why it does it
that way, and [ROADMAP.md](ROADMAP.md) for what's not built yet — one log covers all three;
entries are tagged by platform where it isn't obvious from context.

## Building (Android)

**There is no Java on `PATH`.** Every Gradle invocation needs the JDK bundled with Android
Studio, or it fails with "Unable to locate a Java Runtime":

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest
```

The whole suite is local — Robolectric and MockWebServer, no device or emulator. It runs in
well under a minute, so run it after any change.

**Cutting a beta release** (`.github/workflows/build-debug-apk.yml`, `workflow_dispatch`):
`side_install: true` builds `dev.mike.couchtour.beta` ("Couch Tour Beta", D137) so it installs
alongside the regular app instead of updating over it — the way to let Mike try something
risky without touching his daily-driver install. Pair it with `prerelease: true` so it doesn't
become the GitHub "Latest" release. `release_tag`/`release_notes` create the release itself;
omit `release_tag` to just build and upload the APK as a workflow artifact.
[scripts/cut-beta.sh](scripts/cut-beta.sh) wraps this: it fetches tags, bumps the latest `vX.Y`
by one, dispatches with the right flags, and prints the run URL — `scripts/cut-beta.sh "notes
summarizing what merged"`, or `-t vX.Y` to pin the tag explicitly.

**Promoting a beta to production is Mike's call, never automatic.** Every batch cuts a beta
(above) — that beta build, installed on Mike's actual devices, *is* the manual testing step;
nothing else exercises the app end to end before a real user does. Cut all the betas a batch
needs, but don't also cut a matching non-beta release "to be safe" — that defeats the point of
having a separate beta channel to test in first. Only promote when Mike explicitly confirms a
beta (or a run of them) is good, and when he does:

```
gh workflow run build-debug-apk.yml --ref <the confirmed beta's tag, e.g. v0.31> \
  -f release_tag=<next tag, e.g. v0.32> -f release_notes="..." \
  -f prerelease=false -f side_install=false
```

Or [scripts/promote-beta.sh](scripts/promote-beta.sh) `<confirmed-beta-tag> <next-tag>
"notes"`, which wraps the same call — it exists purely to save typing the flags correctly, not
to change when this runs; it still only gets invoked once Mike has explicitly confirmed a tag.

`--ref` matters — it must point at the *confirmed* beta's own tag, not just "current main."
Later betas may have shipped (and be mid-testing, or already found broken) since the one Mike
actually confirmed; promoting "whatever's on main right now" instead of the exact confirmed
commit would ship code he never tested. `prerelease=false` and `side_install=false` (both are
the workflow's defaults, but pass them explicitly here so the intent reads clearly next to the
`--ref`) is what makes this a real release: builds the regular `dev.mike.couchtour` package
and marks it the GitHub "Latest" release, updating Mike's daily-driver install in place.

## Building (macOS)

Everything under `macos/Packages/CouchTourKit` is a plain SwiftPM package — API clients, the
backend-neutral catalog model, the queue-key grammar, and progress storage (GRDB). It needs
no Xcode project to build or test:

```
cd macos/Packages/CouchTourKit && swift test
```

This also runs in CI (`.github/workflows/macos-tests.yml`, `macos-14` runner) on any PR
touching `macos/Packages/CouchTourKit/**` — the app target (`macos/CouchTour`) isn't covered,
since building/installing it needs local ad-hoc signing; that stays a manual check.

**If `swift build`/`swift test` fails with a `PackageDescription.Package.__allocating_init`
linker error, Xcode itself isn't installed** — Command Line Tools alone can't compile *any*
SwiftPM manifest (confirmed with an empty, unrelated package during this repo's own macOS
bring-up; see D115 in DECISIONS.md). Installing Xcode fixes it; don't spend time debugging the
Command Line Tools install instead.

The app target (`macos/CouchTour`) needs Xcode. `CouchTour.xcodeproj` is generated, not
committed (D103) — regenerate it after adding/removing source files:

```
cd macos && xcodegen generate
xcodebuild -project CouchTour.xcodeproj -scheme CouchTour -configuration Debug -destination 'platform=macOS' build
```

To build, install to `/Applications`, and relaunch in one step:

```
macos/scripts/install.sh
```

It's ad-hoc signed (D113) — no paid Apple Developer account is configured, and none is needed
for local use; Gatekeeper only quarantines files downloaded from the internet, never a
locally built `.app`.

## Building (sync backend)

`sync/` is a Cloudflare Worker + D1 service (D119-D127) that both clients sync progress
through (client wiring: D128-D135), deployed at
`https://couch-tour-sync.mkastellec.workers.dev` under Mike's Cloudflare account. `npm install`
once, then day-to-day work runs locally with no Cloudflare account needed:

```
cd sync && npm install
npm run db:migrate:local   # apply schema.sql to a local D1 instance
npm run dev                 # wrangler dev on http://localhost:8787
```

`wrangler dev`'s local mode never contacts Cloudflare's API. `npm run typecheck` runs
`tsc --noEmit`; there's no automated test suite yet — the endpoints were verified by hand
against `wrangler dev` locally, then smoke-tested against the real deployment (D124-D127).

Redeploying after a change to `src/` or `schema.sql`:

```
npm run db:migrate:remote   # only if schema.sql changed
npm run deploy
```

`wrangler login` is already done on this machine (`~/Library/Preferences/.wrangler/config/`);
`wrangler.toml`'s `database_id` points at the real database, not a placeholder.

## Names that look wrong and are not

The app was renamed from "Phish.in for Android" to "Couch Tour" in `c2b99e2`. The rename was
deliberately scoped to user-visible identity. Do not "finish" it — each of these is load
bearing:

- **`"phishin.db"`** and **`"phishin_auth"`** are on-disk names. Renaming either orphans the
  listening history and login of every install that already exists.
- **`PhishInApi`** is the client for phish.in's API. It is named for the service on the other
  end of the socket, not for this app.
- **`PhishInDb`** anchors the Room schema export directory,
  `app/schemas/dev.mike.couchtour.PhishInDb/`, which the migration tests read by path.

The macOS client's database file is also named **`phishin.db`** (at
`~/Library/Application Support/dev.mike.couchtour/`), on purpose and for the same reason
(D97): same filename, same schema, so a future sync or import step is a row-copy, not a
translation. Don't "fix" it to something macOS-flavored either.

References to phish.in in comments, docs, and API URLs are correct and should stay. The
attribution in the README is required framing, not a leftover disclaimer.

## Room migrations

The `progress` table *is* the feature — it holds listening position and history, which is the
thing the app exists to never lose. Migrations are always written out properly; a destructive
migration is never the right answer here. Add a `MIGRATION_n_n+1`, register it in
`addMigrations(...)`, bump `version`, commit the generated schema JSON, and cover it in
`MigrationTest.kt`.

## Project conventions

- **Log decisions in [DECISIONS.md](DECISIONS.md).** It is organised by iteration with `Dnn`
  identifiers. When a decision reverses an earlier one, add a new entry marking the old one
  superseded rather than editing history.
- **Track not-yet-built features and open questions in [ROADMAP.md](ROADMAP.md)**, not
  DECISIONS.md — DECISIONS.md is a log of choices already made, not a backlog.
- **Comments explain why, not what.** The existing code is deliberately literate about
  tradeoffs and surprises — match that when adding to it.
- **The README states a unit-test count.** It goes stale; update it when adding or removing
  tests.
- **Anything that can only be confirmed by a human using the app goes in [UAT.md](UAT.md)**,
  not buried in a DECISIONS entry's testing section where it is never seen again. Add items as
  you finish a batch. Mike checks them off in the local UAT board (`scripts/uat-server.py`),
  and an item he marks **needs work** is a real bug report addressed to whoever picks it up
  next — read `UAT.md` before starting work on a feature you might be re-touching.

## Working under the Cline Kanban board

The Kanban board (`http://127.0.0.1:3484`, one project per repo) is the primary development
plane. It creates a **worktree per task** under `~/.cline/worktrees/<hash>/phish-in-app` and
starts `claude --permission-mode auto` inside it. These rules take precedence over the generic
worktree advice above whenever a `~/.cline/worktrees/` path is your working directory.

**The board owns the worktree; you own the branch.**

- **Never** run `git worktree add` or `git worktree remove` — not on your own worktree, not on
  another task's. The board created yours and will clean it up. Removing it mid-task strands the
  card and hides your work (this happened once already; see D208).
- Your worktree starts on a **detached HEAD**. That is normal, not a problem to fix — but do not
  commit and stop there, because a detached-HEAD commit is unreachable from any branch and
  invisible to everyone. `git checkout -b <descriptive-branch>` first.
- **Own git end-to-end**: branch, commit, push, open a PR, wait for CI
  ([scripts/ci-wait.sh](scripts/ci-wait.sh)), self-review the diff, merge with
  `--delete-branch`. This is the same autonomous loop as "Working through open issues" above.
  Leave the board's own **Automatically → Make commit / Make PR** setting **off** — with it on,
  you and the board both try to open a PR for the same work.
- **End your final message with a status line** so the card's summary states the outcome
  unambiguously — the board's column does not track whether you merged:
  `STATUS: MERGED #124` / `STATUS: PR-OPEN #124` / `STATUS: BLOCKED <one-line reason>`.

**Decision IDs are a shared mutable resource.** Parallel tasks each branch from `main` and each
compute "the next `Dnnn`" from what they see there, so two tasks routinely pick the same number —
this is a real collision git cannot detect, because both sides are appends to different regions
of `DECISIONS.md` (D208 records how this bit us). So:

- Write the `DECISIONS.md` entry as your **last** commit, and immediately before writing it run
  `git fetch origin && git show origin/main:DECISIONS.md | grep -c '^### D'` to allocate the
  number against `main` *as it is at merge time*, not as it was when you branched.
- If you still collide at merge, **renumber your own entry** (the later-merging one) and never
  edit the other batch's content.

**Fresh worktrees are missing machine-local files.** `local.properties` is gitignored, so Gradle
fails with "SDK location not found" until you recreate it:

```
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

**Two macOS build hazards specific to worktrees — read these before trusting any macOS result:**

- **`macos/CouchTour.xcodeproj` may be a symlink into the main checkout** (D206). If it is,
  `xcodebuild` compiles *that* checkout's sources while reporting success, so your edits are
  never tested. Check with `ls -la macos/ | grep xcodeproj` and remove the symlink before
  running `xcodegen generate`.
- **`xcodebuild` may resolve the local `CouchTourKit` package from the main checkout's path
  rather than your worktree's copy** (D207, confirmed by deliberately breaking a file and
  watching the build still succeed; clearing `DerivedData` did not fix it). If a batch's
  correctness rests on an app-target build, verify the build actually sees your changes — break
  something on purpose and confirm it fails — before reporting success.

`swift test` on the package is unaffected by both and remains trustworthy. But note it covers
`CouchTourKit` **only**: the app target is not in the package and not built by
`macos-tests.yml`, so a green `swift test` is never evidence that macOS UI is reachable (D208).
macOS UI work needs a real click-through — which is what [UAT.md](UAT.md) tracks.

## Working through open issues

Mike drives the backlog by asking for "the next issue" or "the next batch," works from the
prompts given for each one, and can't review code himself — so the merge/release loop is
autonomous by default, not a proposal he approves each time:

- **Merge PRs without asking first**, once CI is green and a self-review of the diff (read the
  actual changes, not just the description) turns up nothing that looks wrong — matches the
  issue, touches only what it should, has real tests, doesn't contradict something documented
  elsewhere (this file, DECISIONS.md). Only hold a PR for Mike when something in the diff looks
  genuinely risky or ambiguous, not merely "could be nicer." [scripts/ci-wait.sh](scripts/ci-wait.sh)
  `<pr-number>` replaces the manual poll-then-diagnose loop — it blocks until checks finish and,
  on a failure, pulls the failing job's log tail in the same call.
- **Clean up your own worktree and branch immediately after your PR merges — but only a
  worktree you created yourself.** If you are running under the Kanban board, the board owns
  the worktree and removing it corrupts the card; see "Working under the Cline Kanban board"
  below, which takes precedence over this bullet. Otherwise: `git worktree
  remove` on the worktree you were using (if any) and delete the branch, both locally and on
  the remote (`gh pr merge --delete-branch` handles the remote side in one step). This is what
  actually keeps `.claude/worktrees/` and the branch list usable; skipping it is exactly how 4
  stale worktrees and 20+ merged-but-undeleted branches piled up before a 2026-08-20 cleanup
  pass caught them. A worktree directory's name is only ever accurate at the moment it's
  created — the same folder gets reused for unrelated later branches (its name then means
  nothing), so don't rely on it to identify what's inside; `git -C <path> branch --show-current`
  is the source of truth. If you're mid-task and about to run out of room to finish (context
  limit, told to stop), commit and push whatever's done rather than leaving it uncommitted in
  the worktree — an open PR (even a rough one) is far more likely to get picked up than a diff
  nobody knows to look for.
- **Cut a beta release at the end of each batch of work**, not just when asked: `workflow_dispatch`
  on `build-debug-apk.yml` with `side_install: true` and `prerelease: true` (see "Cutting a
  beta release" above), tag bumped by one (`v0.NN`), release notes summarizing what merged.
  This is how Mike gets a build onto his own devices to test — treat it as part of finishing
  the batch, not a separate ask. **Stop at the beta.** Promoting one to production (see
  "Promoting a beta to production" above) needs Mike to have actually tested it — that's the
  entire point of a separate beta channel — so it only happens when he explicitly confirms one,
  never automatically at the end of a batch alongside the beta.
- **Multiple worktrees can run in parallel without active coordination.** Don't have one
  agent's prompt tell it to "coordinate" with another running elsewhere — that spends tokens
  re-deriving context for no real benefit. Instead, when handing out a batch for a second
  worktree, name the specific file(s)/function(s) the other one is already working in and tell
  the new one to avoid those; git resolves everything else at merge time. If a real conflict
  does show up, it gets resolved then, not preemptively negotiated between agents.
- **ROADMAP.md's "Suggested build order"** is the standing prioritization — pick the next
  unclaimed item from there rather than re-deriving priority from scratch each time, and update
  it (plus the Feature ideas issue-number cross-references) when the picture changes enough to
  matter.

## Publishing constraints

Being prepared for a Google Play release. Two constraints come from outside the code:

- The phish.in maintainer permitted the API use but asked that this not be branded as
  official. Nothing in the store listing, app title, or icon may imply a first-party
  relationship.
- Keep the band's name out of the store title — it is their trademark. Descriptive use in the
  listing body is fine.

Built-in Last.fm scrobbling was removed before release to shrink the privacy surface
(`c8d2308`). Scrobbling still works via the official Last.fm app, which reads the
MediaSession metadata directly — keep that metadata correct.

Licensed [PolyForm Noncommercial 1.0.0](LICENSE): source available, not OSI open source.
