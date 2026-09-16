# Couch Tour

An unofficial native client for [phish.in](https://phish.in) and Relisten.
- **Android**: Kotlin, Jetpack Compose, Media3, Room.
- **macOS**: Swift, SwiftUI, AVFoundation, GRDB.
- **Sync**: `sync/` (Cloudflare Worker + D1 backend).

See [README.md](README.md) (overview), [DECISIONS.md](DECISIONS.md) (log of choices made), and [ROADMAP.md](ROADMAP.md) (backlog).

## Building (Android)

**Requires Android Studio JDK:**
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest`

- Run tests locally (Robolectric + MockWebServer) after any change.
- **Beta Releases:** `workflow_dispatch` `.github/workflows/build-debug-apk.yml` (`side_install: true, prerelease: true`). Wrapper: `scripts/cut-beta.sh "notes"`. Cut betas after every batch.
- **Promoting to Prod:** Manual only, after owner confirmation. `gh workflow run build-debug-apk.yml --ref <confirmed-tag> -f release_tag=<next-tag> -f release_notes="..." -f prerelease=false -f side_install=false` or `scripts/promote-beta.sh`.

## Building (macOS)

- **Packages:** `cd macos/Packages/CouchTourKit && swift test`. If it fails with `__allocating_init`, install Xcode.
- **App Target:** Requires Xcode. `cd macos && xcodegen generate`, then `xcodebuild -project CouchTour.xcodeproj -scheme CouchTour -configuration Debug -destination 'platform=macOS' build`.
- **Install & Relaunch:** `macos/scripts/install.sh`. App is ad-hoc signed.

## Building (Sync Backend)

- **Setup:** `cd sync && npm install`, `npm run db:migrate:local`, `npm run dev`.
- **Deploy:** Automatic via `.github/workflows/sync-deploy.yml` on push to `main` for `sync/**`. Do not deploy manually.

## Naming Constraints

- **Do not rename:** `"phishin.db"`, `"phishin_auth"`, `PhishInApi`, `PhishInDb`. macOS DB is also `phishin.db`.
- Phish.in references and attribution are correct and required.

## Room Migrations

- The `progress` table holds user listening history. NEVER use destructive migrations.
- Write `MIGRATION_n_n+1`, register in `addMigrations`, bump version, commit JSON, cover in `MigrationTest.kt`.

## Project Conventions

- **DECISIONS.md:** Log decisions (`Dnn`). Append to reverse past decisions; do not rewrite history.
- **ROADMAP.md:** Track open questions/features here.
- **Comments:** Explain *why*, not *what*.
- **README.md:** Keep unit-test count updated.
- **UAT.md:** Track human verification. Items marked "needs work" are bug reports.

## Working under the Cline Kanban board

- **Board owns worktrees, you own branches.** Never run `git worktree add/remove`.
- Always `git checkout -b <branch>` immediately (starts detached).
- **Own git E2E:** Branch, commit, push, PR, wait for CI (`scripts/ci-wait.sh`), merge with `--delete-branch`.
- **Status lines:** End messages with `STATUS: MERGED #124`, `STATUS: PR-OPEN #124`, or `STATUS: BLOCKED <reason>`.
- **Decision IDs:** Allocate `Dnnn` by checking `main` right before committing (`git fetch origin && git show origin/main:DECISIONS.md | grep -c '^### D'`).
- **Android SDK:** Fix missing SDK with `echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties`.
- **macOS Build Hazards:** Remove symlinked `macos/CouchTour.xcodeproj` before `xcodegen`. Verify `CouchTourKit` resolves to the local worktree copy, not main checkout.

## Working through open issues

- **Autonomous Merge:** Merge PRs if CI passes and self-review is clean. Only block for owner on real ambiguity.
- **Clean Up:** Run `git worktree remove` (if not Kanban) and delete branches post-merge.
- **Beta Releases:** Cut a beta after every batch.
- **Parallel Work:** No cross-agent coordination prompts. Just name files to avoid.
- **Priority:** Follow `ROADMAP.md` "Suggested build order".

## Session continuity (primary checkout only)

- Start session: `pickup`. End/Pause: `handoff`. Do not cold-resume without `pickup`.

## Publishing constraints

- **Brand:** Unofficial. Exclude band name from title.
- **Scrobbling:** No built-in Last.fm (use official app via MediaSession).
- **License:** PolyForm Noncommercial 1.0.0.
