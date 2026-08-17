# Couch Tour

An unofficial native client for [phish.in](https://phish.in), the open-source live Phish
archive, and for Relisten's other-artist catalog. Two clients live in this repo: an Android
app (Kotlin, Jetpack Compose, Media3, Room) and a macOS app (Swift, SwiftUI, AVFoundation,
GRDB), plus `sync/`, a Cloudflare Worker + D1 backend the two sync progress through — pairing,
push/pull, and history/resume now verified working live between a real phone and Mac
(D116-D143). See
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

## Building (macOS)

Everything under `macos/Packages/CouchTourKit` is a plain SwiftPM package — API clients, the
backend-neutral catalog model, the queue-key grammar, and progress storage (GRDB). It needs
no Xcode project to build or test:

```
cd macos/Packages/CouchTourKit && swift test
```

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
