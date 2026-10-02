# Codebase health: Android client (#488, part of #487)

Scope: `app/src/main/java/dev/mike/couchtour/` (the `:app` module). Security was
audited separately (#475/#476); macOS and sync are sibling sub-issues. This is
the Android counterpart to `docs/HEALTH_SYNC_SCRIPTS.md`. Next pass: diff
against this file.

## Leaks

Method: grepped `app/src/main` for `Cursor`, `openInputStream`/`openOutputStream`,
`FileInputStream`/`FileOutputStream`, OkHttp `Response`/`ResponseBody`,
`MediaMetadataRetriever`, un-`use`d `Closeable` values, and coroutine scopes /
listeners not tied to or cleared by a lifecycle.

| Where | Finding | Action |
|---|---|---|
| `MainActivity.kt:167` | `CoroutineScope(Dispatchers.IO).launch { SyncSession.sync(...) }` created inline in `onCreate` with no handle and never cancelled. Captures `this@MainActivity`, so a slow/destroyed launch could leak the Activity until the coroutine finished. | **Fixed** → `lifecycleScope.launch(Dispatchers.IO) {...}`, which is cancelled when the Activity is destroyed (the right lifecycle hook for a launch-time catch-up sync). |
| `Api.kt:207`, `Relisten.kt:413`, `YouTubeStreams.kt:99`, `VolumeLeveler.kt:275`, `Sync.kt:219` | OkHttp `newCall(...).execute()` | None needed — every call is wrapped in `.use { resp -> ... }`, so the response body is drained/closed. |
| `DiagnosticsLog.kt:190,204`; `Relisten.kt:415` | `FileOutputStream` / buffered readers | None needed — all opened with `.use {}`. |
| `Qr.kt:117-133` | CameraX `ImageAnalysis` analyzer | None needed — `ImageProxy` is closed on every path: the early-return path (line 120) and `addOnCompleteListener { proxy.close() }` (133), which fires on both success and failure. |
| `PlayerViewModel.kt:130` | `MediaController.addListener(object : Player.Listener)` | None needed — `onCleared()` calls `controller?.release()`, which detaches the controller's listeners; `refresh()` also no-ops once `controller` is null. |
| `PlaybackService.kt:118,283,406,764` | Service `scope` + `playerListener` on local + cast players | None needed — `onDestroy()` calls `scope.cancel()`, releases both players (`localPlayer?.release()`, `castPlayer?.release()` clears their listeners), nulls `castPlayer`. |
| `Sync.kt:634` | `internal var debounceScope = CoroutineScope(Dispatchers.IO)` | Left + why — process-global singleton by design (a debounce in flight outlives the triggering component); the single `pushJob` is cancelled/replaced on each `requestDebouncedPush`. Documented by the existing comment; not a leak. |
| `CrashCapture.kt:52`, `CouchTourApp.kt:35` | `CoroutineScope(Dispatchers.IO)` default-param for one-shot reads | Left + why — fires a single short read, returns the `Job`, and is then unreachable (no retained scope). Completes and is GC'd; not a long-lived leak. |
| `app/src/main` (all files) | `Cursor` / `MediaMetadataRetriever` / open streams | None needed — `Cursor` appears only in a comment (`Sync.kt:571`); no `MediaMetadataRetriever`, `openFileInput`, or un-`use`d stream usage exists. |

No leaks remain in the scanned surfaces; the one real one (`MainActivity:167`)
is fixed.

## Dead code

Method: fresh scan of every `private`/`internal` declaration in `app/src/main`
for ones referenced only at their own declaration (declaration-only), plus a
check of every item from the earlier `android_dead_code_audit.md`.

The audit's Section 1 (dead code) was already resolved by prior passes (#218,
#223): all seven flagged composables (`SurpriseMeButton`, `AnniversaryCard`,
`ResumeCard`, `ArtworkBox`, `AudioQualityBadge`, `ProgressBarOverlay`,
`LedgerToggle`), the `ShowSortMode` companion aliases (`DATE`/`RATING`/
`TRENDING_7D`/`TRENDING_30D`), the `RelistenPopularityWindow` aliases
(`w48h`/`w7d`/`w30d`), the `ArtistTourPreferenceDao` unused queries, and the
test-only functions (`currentUser`, `positionAt`, `toTag`, `historyFor`) are no
longer declared. Verified by grep: none of these names appear as a declaration
anywhere in `app/src`.

This pass removed the two genuinely-dead references that were still present:

| Where | Finding | Action |
|---|---|---|
| `Artwork.kt:915` | KDoc on `MediumArtworkOverlay` still enumerated the then-deleted `AnniversaryCard` and `ResumeCard`. | Removed the dead names from the KDoc. |
| `PlayerViewModel.kt:303-304` (`playNextTourStop`) | `val show = PhishInApi.show(...)` followed by `if (show != null) playShow(show)`; `PhishInApi.show` returns non-null `Show`, so the null check is a dead always-true branch (the compiler flagged it as a warning). | Removed the dead branch → `playShow(PhishInApi.show(summary.date))`. Compiler-verified: compiles only if `show` is non-null. |

Post-cleanup, every removed declaration is gone from `app/src` (grep confirms
none of `SurpriseMeButton`/`AnniversaryCard`/`ResumeCard`/`ArtworkBox`/
`AudioQualityBadge`/`ProgressBarOverlay`/`LedgerToggle` is declared anywhere;
the `ArtistTourPreferenceDao.getPreferenceFlow(artistKey)`,
`getAllPreferencesSync`, and `clearAll` queries are absent; `currentUser`,
`positionAt`, `toTag`, `historyFor` are absent).

The audit's Section 3 (deprecated-API / KTX modernization: `Uri.parse` →
`.toUri()`, `prefs.edit()…apply()` → `prefs.edit { }`, `Bitmap` KTX operator
overloads, Media3 `@OptIn(UnstableApi)`, CameraX `@OptIn(ExperimentalGetImage)`,
`modifier`-first Compose ordering, `data_extraction_rules.xml`,
`mipmap-anydpi/` de-qualifier, `MEDIA_PLAY_FROM_SEARCH` intent filter) is
still valid but explicitly **out of scope** for this pass (deprecated-API
upgrades are listed under Out of scope). Carried forward as a separate issue —
see #494.

## Complexity

Method: profiled the four target files (`MainActivity.kt`, `PlayerViewModel.kt`,
`PlaybackService.kt`, `Sync.kt`) by brace depth to rank functions by body
length; nesting is bounded by Compose state reads and `when` chains. Splits use
extract-function + early returns only; no logic or behavior change, and all
"why" comments were preserved (then re-attached to the helpers they describe).

### Splits performed

| Function (was) | File | Extracted helpers | Result |
|---|---|---|---|
| `refresh()` (133 lines) | `PlayerViewModel.kt:140` | `resolveIdentity` (queue-key + fallback identity), `queueTracks` (queue item mapping), `schedulePostShowPrompt` (end-of-show tour-stop prompt; early return on `!STATE_ENDED`) | 133 → 62 lines |
| `onCreate()` (119 lines) | `PlaybackService.kt:184` | `buildLocalPlayer()` (ExoPlayer + leveling-rendering factory), `startProgressSaver()` (5s progress-save loop) | 119 → 81 lines |

Both functions dropped out of the longest-function ranking; `Sync.kt`'s `sync()`
(78 lines) was reviewed and left intact (already small and single-purpose).

### Top 10 longest functions (after this pass, approximate lines)

| # | Function | File | Lines |
|---|---|---|---|
| 1 | `HomeScreen` | `MainActivity.kt` | 505 |
| 2 | `SyncScreen` | `MainActivity.kt` | 234 |
| 3 | `SearchResultsList` | `MainActivity.kt` | 223 |
| 4 | `ShowHeader` | `MainActivity.kt` | 222 |
| 5 | `RecordingHeader` | `MainActivity.kt` | 212 |
| 6 | `LocalPlaylistScreen` | `MainActivity.kt` | 132 |
| 7 | `InProgressLedgerRow` | `MainActivity.kt` | 128 |
| 8 | `YouTubeVideoScreen` | `MainActivity.kt` | 124 |
| 9 | `ArtistShowsScreen` | `MainActivity.kt` | 120 |
| 10 | `TourPickerDialog` | `MainActivity.kt` | 114 |

Test-only functions and the `MainActivity` journey wrappers are excluded. The
five largest entries are `@Composable` screens; splitting them (extracting
sub-composables) is out of scope for a no-behavior-change pass and is tracked
separately as a `MainActivity` file split — see #495.

## Follow-up issues

- #494 — Deprecated-API / KTX modernization (the audit's Section 3; out of scope here).
- #495 — Split `MainActivity.kt` (4835 lines) into multiple files / sub-composables.

## Verification

`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest`
passes (BUILD SUCCESSFUL; the previously-flagged always-true `show != null`
warning is gone after the dead-branch removal). `android_dead_code_audit.md`
was removed.
