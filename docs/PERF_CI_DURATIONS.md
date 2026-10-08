# CI run-duration outliers (#506, part of #502)

Snapshot taken 2026-10-02 from the last 300 runs (`gh run list -L 300`, 2026-09-22 onward). Duration is
`updatedAt - createdAt`, minutes, so it includes queue time.

## Before: per-workflow durations

| Workflow (event) | Runs | Median | p90 | Max |
|---|---|---|---|---|
| Unit tests (pull_request) | 146 | 3.1 | 3.7 | 1360.2 (*) |
| macOS unit tests (pull_request) | 62 | 2.0 | 2.4 | 2.9 |
| Build debug APK (push) | 39 | 2.1 | 2.5 | 4.3 |
| Checks (pull_request) | 39 | 0.2 | 0.2 | 0.3 |
| Deploy sync backend (push) | 5 | 0.6 | 0.6 | 0.7 |
| Build and Release macOS App (dispatch) | 2 | 2.4 | 2.2 | 2.5 |
| Beta promotion guard (schedule) | 2 | 0.2 | 0.2 | 0.2 |

(*) One run on the "Part 2.2: Google TV app" PR sat for ~22 h before finishing; a queue/stall artifact, not
test time. Every other Unit tests run was under 5 min.

### Runs that couldn't be affected

`test.yml` had no `paths` filter. Of the last 300 Unit tests runs (299 distinct head commits), 196 (65%)
were on commits that touched nothing under `app/**`, Gradle files, `test.yml` or the source-guard script
(docs-only, `macos/**`-only, `sync/**`-only, etc.). Measured per head commit, not per whole-PR diff, so it is
an estimate. At a ~3 min median that is roughly 10 runner-hours across the window.

Other workflows are already scoped: `macos-tests.yml` filters on `macos/**`, `build-debug-apk.yml` on app and
Gradle files, `checks.yml` is ~12 s of grep, `contract-check.yml` is weekly cron only and never runs on PRs.

## Changes made

- `test.yml`: initially had a `paths` filter (`app/**`, `gradle/**`, `gradlew*`, root Gradle files,
  `scripts/test_sync_base_url_intent_guard.sh`, and the workflow file), mirroring `build-debug-apk.yml`.
  However, `main` branch protection now requires the `test` status check (#559, #561, D336).
  Because a `paths:` filter would cause docs-only or macOS-only PRs to wait indefinitely on a required check
  that never runs, the `paths:` filter was removed so `test.yml` runs unconditionally on every PR.
- `macos-tests.yml`: cache `macos/Packages/CouchTourKit/.build` with `actions/cache`, keyed on
  `Package.swift` + `Package.resolved`. The `macos-build` job's Xcode build isn't cached.
- Gradle cache: `gradle/actions/setup-gradle@v4` already caches. By default it only writes the cache from the
  default branch, and PR runs read it; no change made. I did not inspect the cache-hit lines in run logs.
- `actionlint` 1.7.12 is clean on both edited files.

Not changed: `sync-deploy.yml`, `macos-release.yml`, `beta-guard.yml` (out of scope).

## Slowest tests (local runs, 2026-10-02)

### Android (`testDebugUnitTest`, 819 tests, 16.6 s of test time in total)

| s | Test |
|---|---|
| 3.98 | A11yTagsTest: search field tag matches the macOS identifier and resolves on a text field |
| 1.93 | DiagnosticsInstrumentationTest: local playlist addTrack and removeTrack emit library playlist events |
| 0.78 | OnThisDateTest: showsOnDate where one period always throws returns partial and complete=false |
| 0.59 | OnThisDateTest: showsOnDate limits peak concurrency for phishin |
| 0.41 | ArtistScreenTest: tapping a video row reports the video id |
| 0.41 | SyncSessionTest: requestDebouncedPush coalesces a burst of calls into a single push |
| 0.40 | OnThisDateTest: showsOnDate retries a failed range once on IOException |
| 0.38 | ArtistScreenTest: section hides entirely for an artist with no curated channel |
| 0.37 | ApiRequestTest: encodes a search term containing a slash |
| 0.33 | A11yTagsTest: home section tags resolve in a Compose tree |

The ~3 min CI job is dominated by Gradle/AGP configuration and compile, not test execution.

### CouchTourKit (`swift test`)

| s | Test |
|---|---|
| 0.416 | LoudnessMeasurerTests.testSilenceIsNotCached |
| 0.385 | DiagnosticsLogTests.testRotationBoundsFootprintAtTwoMiB |
| 0.339 | LoudnessTests.testSine997PeakDbfs |
| 0.337 | LoudnessTests.testResetClearsState |
| 0.335 | LoudnessTests.testSine997Stereo48kLufs |
| 0.317 | LoudnessMeasurerTests.testTempFilesAreCleanedUpAfterSuccess |
| 0.311 | LoudnessTests.testSine997Stereo44kLufs |
| 0.311 | LoudnessMeasurerTests.testMeasuresAndCachesFromThreeSegments |
| 0.310 | LoudnessTests.testMultiPushPoolsSegments |
| 0.305 | LoudnessMeasurerTests.testConcurrentMeasuresCoalesceOntoOneFetchPass |

### Sync (vitest, 18 tests, durations in **ms**)

| ms | Test |
|---|---|
| 17 | happy paths: pair -> claim -> sync round trip |
| 16 | F2: cursor arithmetic under a concurrent push |
| 15 | F3: negative trackIndex is a 400 |
| 12 | happy paths: 410 when since is below the retention floor |
| 12 | full resync with empty table under non-zero retentionFloorSeq |
| 12 | full resync with active rows below retentionFloorSeq |
| 12 | F1: duplicate queueKey on an exact updatedAt tie |
| 12 | F1: duplicate queueKey keeps newest updatedAt |
| 11 | security audit #475: chunked upload body cap |
| 11 | health check |

## Follow-ups

No test runs over 10 s (slowest is 3.98 s), so no follow-up issues were filed.
