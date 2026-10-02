# Android test health (pass:tests, issue #447 / #426)

Suite: `app/src/test` (58 files, Robolectric + MockWebServer + in-memory Room).

## Numbers
| | Before | After |
|---|---|---|
| Tests | 812 (0 failed, 0 skipped) | 812 |
| Summed test time | 44.8s | 14.1-15.3s (3 runs) |
| Gradle wall (`cleanTest testDebugUnitTest`, warm daemon) | 47s | 16.7-21.3s |

D243 baseline was ~13s for 535 tests. README already says 812, so it is unchanged.
Three consecutive full runs (forced with `cleanTestDebugUnitTest --no-build-cache`) passed 812/812 each time.

## Time creep: one real regression, fixed
`LibraryAccountTest."loadLibraryAccount on network failure..."` took **30.0s**, two thirds of the whole suite.
`loadLibraryAccount` fires four parallel requests, but the test enqueued a single 500, so the other three sat
unanswered until the OkHttp read timeout. Fixed with a `Dispatcher` that answers every request with 500.
Breakage check: setting `error = false` in the catch branch of `loadLibraryAccount` makes the test fail; restored afterwards.

10 slowest classes (before the fix; the first one is now ~0.05s):
| Class | Time (s) | Note |
|---|---|---|
| LibraryAccountTest | 30.06 | fixed, see above |
| A11yTagsTest | 4.31 | 4.1s is first Compose rule/Robolectric warm-up in one test; unavoidable, left |
| DiagnosticsInstrumentationTest | 1.96 | real file/log flushes, left |
| OnThisDateTest | 1.62 | two tests use `delay(50)` inside `runBlocking` (concurrency probes, lines 314 and 446); ~0.1s total, left |
| SyncSessionTest | 0.94 | |
| DiscoveryCatalogE2ETest | 0.62 | 58 tests |
| VolumeLevelingTest | 0.56 | `delay(500)` at line 552 is cancelled by the test, never waited on |
| MigrationTest | 0.52 | |
| ArtistScreenTest | 0.51 | |
| ProgressDaoTest | 0.37 | |

`Thread.sleep` in `app/src/test`: none.

## Isolation: fine
No `Dispatchers.setMain` anywhere, so no `resetMain` gap. Singletons the tests touch (`PhishInApi.baseUrl/authToken`,
`SyncSession`, `OnThisDate` cache, `DiagnosticsLog`) are reset in `@Before`/`@After` or at the start of the test
(`OnThisDate.resetCache()`, `DiagnosticsLog.resetForTest()`). DBs are in-memory or in a temp dir.
Four runs total in this session (one forced cold plus three), no order-dependent failure.

## False greens: none found, one cleanup fixed
- No `assertTrue(true)`, no `@Ignore`.
- Every `try/catch` and `runCatching` in tests keeps its assertion outside the swallow, or calls `fail()` in the try
  (`ApiRequestTest`, `SessionTest`, `SyncTest`, `RelistenRequestTest`, `YouTubeCatalogTest`, `CacheErrorAssertions`).
  `runCatching { ... }` followed by an assertion on side effects (logout hook, `lastError`) is the intended shape.
- Heuristic scan for `@Test` bodies with no assert: one hit, `DiagnosticsLogTest` "log returns without throwing ... unwritable".
  Its assertion is "nothing throws", which fails if `log`/`recordCrash` throw, so it is valid. Left as is, with a comment.
- Coroutine tests: `launch` bodies are joined (`job.join()`) in `DiagnosticsInstrumentationTest` and `FeedbackDiagnosticsTest`;
  `SyncTest` debounce test uses `runTest` virtual time.

## Leaked resources: one fixed
- Every file with `MockWebServer()` also calls `shutdown()` (14/14). Every in-memory/Room DB builder is paired with `close()`.
  The `OkHttpClient`s built in tests are not leaked beyond the test JVM and their servers are shut down.
- `DiagnosticsLogTest` "unwritable" test chmod'ed a temp dir to mode 000 and never restored it, so `TemporaryFolder` could
  not delete it (a leaked dir per run). Fixed: permissions restored in `finally`.
- `VolumeLevelingTest` cancels scope children in `tearDown`; temp dir deleted.

## Filed issues
None; every finding was local to test code.
