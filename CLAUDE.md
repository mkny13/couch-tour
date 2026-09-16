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
against `wrangler dev` locally (D124-D127).

**Deploying happens through the merge-gate pipeline, not by hand** (D234, `.github/workflows/sync-deploy.yml`):
any push to `main` that touches `sync/**` typechecks, deploys to the staging Worker
(`couch-tour-sync-staging`, its own D1 database via `[env.staging]` in `wrangler.toml`), runs a
smoke test against it — `GET /health` (a real `SELECT 1`, so a dead D1 binding fails the gate)
plus a full `/pair/start` → `/pair/claim` round trip — and only then deploys to production. A
red smoke test stops the pipeline before prod; the failed workflow run is the notification.
Migrations against staging/prod (`npm run db:migrate:staging` / `db:migrate:remote`) run inside
the workflow automatically when `schema.sql` changed, so there is no manual deploy step to
remember. To run the pipeline on demand (e.g. after editing only the workflow itself), dispatch
it with `gh workflow run sync-deploy.yml`.

`wrangler login` is already done on this machine (`~/Library/Preferences/.wrangler/config/`);
`wrangler.toml`'s `database_id`s point at the real databases, not placeholders. CI
authenticates with a scoped `CLOUDFLARE_API_TOKEN` repo secret (Workers Scripts:Edit + D1:Edit).

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

## Working under Mahler

Mahler runs you as a background agent in a dedicated worktree under `.mahler-worktrees/`. These rules take precedence over the generic worktree advice above whenever a `.mahler-worktrees/` path is your working directory.

**Mahler owns the worktree and branch.**

- **Never** run `git worktree add` or `git worktree remove` — not on your own worktree, not on
  another task's. Mahler created yours and will clean it up. Removing it mid-task strands the
  issue and hides your work (this happened once already; see D208).
- **Stay in your worktree.** Never run `git reset` or `git checkout` outside it, to avoid
  wiping other sessions' work. You are already on your branch, so do not `git checkout -b`.
- **Your job ends at the push.** When you are done, commit, push your branch, and end with
  `STATUS: DONE <one-line summary>`. Do not open a PR, watch CI, merge, or comment on the
  issue — Mahler's conductor does that, in code, after you end.
- **End your final message with a status line** so Mahler knows the outcome:
  `STATUS: DONE <summary>` / `STATUS: NEEDS-YOU <question> [OPTIONS: ...]` / `STATUS: BLOCKED <reason>` / `STATUS: YIELDED <handoff>`.

**Decision IDs are a shared mutable resource.** Parallel tasks each branch from `main` and each
compute "the next `Dnnn`" from what they see there, so two tasks routinely pick the same number —
this is a real collision git cannot detect, because both sides are appends to different regions
of `DECISIONS.md` (D208 records how this bit us). So:

- **Use `mahler next-id`.** For shared sequential IDs, run `mahler next-id <project> <prefix>`
  (e.g., `mahler next-id couch-tour D`) to allocate the number and avoid collisions. Do not
  manually grep `DECISIONS.md` against main.

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

## Session continuity (primary checkout only)

These habits are about the **primary checkout** (`/Volumes/ExtSSD160/scripts/phish-in-app`), not
per-card worktrees — a card worktree is throwaway and doesn't need them.

- **Session start**: run `pickup` ("where was I") to reconstruct state from `TASKS.md` and git.
- **Task done or stepping away**: run `handoff` ("wrapping up") to write `TASKS.md` and commit.
- **Never cold-resume a big session, and never resume after switching tools** — run `pickup`
  instead of guessing.

`TASKS.md` here tracks primary-checkout state only (GitHub issues and `ROADMAP.md`'s build
order remain the plane of record for backlog and card work — see "Working under Mahler" above), and its `## Now` section is also read by `~/ai-tools/thread.py`'s portfolio scan.

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
