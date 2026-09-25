# Couch Tour

Unofficial native client for [phish.in](https://phish.in) and Relisten's catalog.
- **Android**: Kotlin, Jetpack Compose, Media3, Room.
- **macOS**: Swift, SwiftUI, AVFoundation, GRDB.
- **Sync**: `sync/`, Cloudflare Worker + D1 backend.

See [README.md](README.md) (overview), [DECISIONS.md](DECISIONS.md) (architecture log), and [ROADMAP.md](ROADMAP.md) (backlog).

## Building (Android)

**Java on `PATH` is absent; require Android Studio's bundled JDK:**
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest
```
Tests are local (Robolectric + MockWebServer); run after any change.

- **Cutting a beta release:** Dispatches `.github/workflows/build-debug-apk.yml` with `side_install: true` (`dev.mike.couchtour.beta`) and `prerelease: true`. Wrapper: `scripts/cut-beta.sh "notes"`. Cut a beta after every batch.
- **Promoting beta to production:** Never automatic; requires explicit owner confirmation. Runs against the *confirmed* tag (`--ref <confirmed-tag>`), setting `prerelease=false` and `side_install=false` (`dev.mike.couchtour`). Wrapper: `scripts/promote-beta.sh <confirmed-beta-tag> <next-tag> "notes"`.
- **Google TV:** The TV surface (`TvMainActivity`) is a second Activity in this same `:app` module, not a separate module (D233). It builds and installs from the same `assembleDebug` / `installDebug`, and `app/src/main/AndroidManifest.xml` carries both launcher entries, so manifest edits affect phone and TV together.

## Building (macOS)

- **CouchTourKit package:** `cd macos/Packages/CouchTourKit && swift test`. If it fails with `__allocating_init`, Xcode itself (not just Command Line Tools) is required (D115).
- **App target:** Regenerate project with `cd macos && xcodegen generate`, then build:
  ```bash
  xcodebuild -project CouchTour.xcodeproj -scheme CouchTour -configuration Debug -destination 'platform=macOS' build
  ```
- **Install & Relaunch:** `macos/scripts/install.sh` (builds, ad-hoc signs, installs to `/Applications`, relaunches).

## Building (sync backend)

`sync/` is a Cloudflare Worker + D1 service (`https://couch-tour-sync.mkastellec.workers.dev`).
- **Local dev:** `cd sync && npm install && npm run db:migrate:local && npm run dev` (runs local D1 at `http://localhost:8787`).
- **Typecheck:** `cd sync && npm run typecheck`.
- **Tests:** `cd sync && npm test` (14 tests, real Miniflare D1, not a mock). CI runs typecheck and tests before any deploy.
- **Deployments:** Never deploy by hand. `.github/workflows/sync-deploy.yml` deploys to staging, runs smoke tests, applies migrations, and promotes to prod on push to `main` for `sync/**`. Dispatch on demand with `gh workflow run sync-deploy.yml`.

## Names that look wrong and are not

Renamed from "Phish.in for Android" to "Couch Tour" (`c2b99e2`) for user-facing UI only. Do not rename internal names:
- `"phishin.db"` & `"phishin_auth"`: On-disk names (both Android and macOS at `~/Library/Application Support/dev.mike.couchtour/phishin.db`). Renaming orphans listening history and auth.
- `PhishInApi`: Client for upstream phish.in service.
- `PhishInDb`: Anchors Room schema export path (`app/schemas/dev.mike.couchtour.PhishInDb/`).
- References and attributions to phish.in in code, docs, and API endpoints are required and correct.

## Room migrations

The `progress` table stores listening history. Destructive migrations are never permitted.
- `PhishInDb` is declared in `app/src/main/java/dev/mike/couchtour/Progress.kt`, alongside the tables it manages. Add `MIGRATION_n_n+1` there and register in `addMigrations(...)`.
- Bump DB `version`, commit generated schema JSON in `app/schemas/dev.mike.couchtour.PhishInDb/`, and test in `MigrationTest.kt`.

## Project conventions

- **DECISIONS.md:** Log decisions sequentially with `Dnnn` IDs. When reversing a decision, append a new entry marking the prior one superseded rather than rewriting history.
- **ROADMAP.md:** Track backlog features and open questions.
- **Comments:** Explain *why*, not *what*.
- **README.md:** Update test counts when adding or removing tests.
- **UAT.md:** Record items requiring human verification (`scripts/uat-server.py`). Items marked "needs work" are active bug reports.

## Working under Mahler

- **Worktrees & Branches:** Mahler owns worktrees and branches. Never run `git worktree add/remove`, `git reset`, or `git checkout -b`. Stay within your assigned worktree.
- **Completion:** Job ends at git push. Do not open PRs, watch CI, or comment on issues.
- **Status Lines:** End final agent response with exactly one status line:
  `STATUS: DONE <summary>` / `STATUS: NEEDS-YOU <question> [OPTIONS: ...]` / `STATUS: BLOCKED <reason>` / `STATUS: YIELDED <handoff>`.
- **Decision IDs:** Allocate sequential IDs with `mahler next-id <project> <prefix>` (e.g. `mahler next-id couch-tour D`). Do not manually grep `DECISIONS.md`.
- **Android SDK:** Fresh worktrees require:
  ```bash
  echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
  ```
- **macOS Build Hazards:**
  - `macos/CouchTour.xcodeproj` may be a symlink to the main checkout. Remove symlink before running `xcodegen generate`.
  - Ensure `xcodebuild` resolves local `CouchTourKit` from the worktree, not the main checkout.
  - `swift test` covers `CouchTourKit` package only, not the app UI (track UI checks in `UAT.md`).

## Session continuity (primary checkout only)

For primary checkout (`/Volumes/ExtSSD160/scripts/phish-in-app`) only:
- Start session: `pickup`. Pause/end: `handoff`. Never cold-resume without `pickup`.
- `TASKS.md` tracks primary checkout state (`## Now` scanned by portfolio tooling).

## Publishing constraints

- **Brand & Title:** Unofficial client. Keep the band name out of the store title (trademark); descriptive use in description is permitted.
- **Scrobbling:** No built-in Last.fm scrobbler; preserve MediaSession metadata for the official Last.fm app.
- **License:** PolyForm Noncommercial 1.0.0 (source available, not OSI open source).
