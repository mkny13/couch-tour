# macOS CouchTourKit test health (pass:tests, issue #448 / #426)

Suite: `macos/Packages/CouchTourKit/Tests/CouchTourKitTests` (XCTest).

| | Before | After |
|---|---|---|
| Tests | 517 | 517 (README already says 517) |
| `swift test` wall time of tests | 10.2s | ~5.7s |
| `swift test --parallel` | 1 failure (see Isolation) | passes, 3 consecutive runs |

D243 baseline was 0.81s / 381 tests. The creep is not sleeps: it is the loudness DSP (#268, #319)
running in debug builds. `LoudnessTests` (16 tests) take ~2.4s and `LoudnessMeasurerTests` ~1.5s after the fix.

## Isolation: 3 findings, all fixed here
| Where | Problem | Fix |
|---|---|---|
| `PlaybackSettingsTests.swift:12`, `ThemeSettingsTests.swift:11` | Fixed `UserDefaults` suite name; under `--parallel` tests wipe each other's values (`testAudioQualityAndGaplessPersistAcrossInstances` failed 3 assertions) | suite name carries a per-test UUID |
| `LoudnessMeasurerTests` / `LoudnessMeasurer.init` | Measurer wrote segment temp files to the real `~/Library/Caches/CouchTourKit-leveling`, so the "temp files are cleaned up" tests saw other tests' (or an aborted run's) files | new optional `tempDirectory:` init parameter; tests use a per-test temp dir removed in `tearDown` |

## False greens: none found
No test without an assertion (scripted scan of every `func test…` body), no `XCTSkip`, one `expectation`
(`PlaybackSettingsTests` `wait(for:)`, awaited). `try?` appears only in cleanup/`defer` and one size read inside an assertion.
The one test whose structure was weak, `testClearAllEmptiesCacheAndCancelsInFlightMeasurement`, never
guaranteed the measurement was in flight (it slept 50ms and its "slow" decoder blocked the actor). Rewritten
to wait on a semaphore signalled by the first stubbed request, then `clearAll()`. Verified it fails when
`task.cancel()` is removed from `clearAll` (result non-nil, cache not empty).

## Time creep
Top 10 after the fix (seconds): DiagnosticsLog rotation 0.42; LoudnessTests sine/reset/silence/pooling ×6 0.31-0.34;
LoudnessMeasurerTests measure/coalesce/temp-file ×3 ~0.31.
Real sleeps removed: `Thread.sleep(0.5)` in the slow decoder and `Task.sleep(50ms)` in the clearAll test (~4.7s
across 5 tests before, since every measurer test also pays for a 3s fixture). The measurer fixture PCM is now 1s
(same LUFS/peak assertions pass). Remaining time is DSP over real sample buffers; no seam to remove it.

## Leaked resources
`URLProtocol` stubs are per-`URLSession` config (no global registration), sessions are invalidated in `tearDown`,
`MockServer` is created and torn down per test, temp dirs are removed in `tearDown`/`defer`. The leveling temp
directory was the one leak (above); a hung run left a stale file there until now.

## Filed issues
None; every finding was local to test code or a test seam.
