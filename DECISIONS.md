# Decision log

Conservative choice taken by default; each one is cheap to reverse. Push back on any of these.

> **Archive:** entries up to roughly D266 (Iterations 2–61) live in [docs/decisions/DECISIONS-ARCHIVE.md](docs/decisions/DECISIONS-ARCHIVE.md). Grep both files when you look up a D-number. New IDs come from `mahler next-id`, so the move can't cause reuse.


## Architecture

**D1 — Native Kotlin client, not a WebView wrapper.**
You said a wrapper would be fine. It isn't, for your two stated priorities. Native Android
media controls (lockscreen, notification shade, Bluetooth, headset button) require a
`MediaSessionService` running in the foreground; a WebView gets none of that, and its audio
is killed or muted when the app backgrounds. Since the API returns plain MP3 URLs, wrapping
buys nothing anyway. Cost of going native: the browse UI has to be written by hand.

**D2 — `minSdk 26`, `targetSdk 35`, `compileSdk 35`.**
You're on Android 13+, so 33 was on the table. 26 costs about ten lines of compatibility
code and removes any chance the APK refuses to install on a phone you own later.

**D3 — Media3 / ExoPlayer for playback, Room for state, Compose for UI.**
All first-party AndroidX. No third-party player.

**D4 — Plain OkHttp + kotlinx.serialization, no Retrofit.**
The app calls exactly three endpoints. Retrofit would be a dependency and a codegen step
to save maybe fifteen lines.

## Scope

**D5 — MVP is browse → play → resume. No login.**
Iteration 2 adds phish.in auth, which unlocks your saved playlists and likes. Splitting it
this way gets a working player into your hands a day sooner and keeps the auth work honest
rather than rushed.

**D6 — Progress is stored under a namespaced `queueKey`, e.g. `show:1997-11-17`.**
You asked for per-show, per-playlist, and full history. The Room table holds one row per
queue keyed by that string, so playlists slot in as `playlist:<slug>` in iteration 2 with
no schema migration. History is every row, ordered by `updatedAt`.

**D7 — Progress is written every 5 seconds while playing, plus on every play/pause and
track change.** A crash or a swipe-away loses at most five seconds. Writing on every
position update would hammer the database for no benefit.

**D8 — No offline downloads.**
Not requested. It pulls in storage permissions, a download manager, and cache eviction
policy. Say the word and it becomes iteration 3.

**D9 — I never handle your phish.in password.**
When auth lands, the app gets its own login screen and you type your password into it.
I won't type it during testing, so I'll verify authenticated features against a throwaway
account you create, or you'll verify them yourself.

## Data quirks found in the API

**D10 — Every show list is filtered to `audio_status=complete_or_partial`.**
Most shows in the archive have no audio at all — 1987 lists 26 `missing` against 10
`complete` and 8 `partial`. Showing unplayable shows would make the app look broken.

**D11 — `/years` returns *periods*, not years.**
Most are a single year (`"1997"`) but the early ones are ranges (`"1983-1987"`). Ranges
need `year_range=`; passing one to `year=` returns an empty list with no error. Handled in
`PhishInApi.showsForPeriod`.

**D12 — Tracks are filtered to those with a usable `mp3_url`.**
On `partial` shows some tracks are `missing`. The queue index therefore refers to the
*filtered* list, and both the UI and the queue builder filter identically.

### D269 — Google TV show/track browse: four-level `remember` chain, owner picked grid over row for shows (#226)

Part 2.2 of #9 (depends on #225/D266). Extends `TvBrowseScreen` to the full `artist → year/era → shows → tracks` hierarchy rather than introducing a nav graph at this point, since D266 already established that a hand-rolled state chain reads better than a NavHost for a D-pad-only surface with no deep links to serve.

- **Four `remember`ed states, not a graph:** `selectedArtist`/`selectedPeriod`/`selectedShow` chain the same way D266's two-level version did. Three `BackHandler`s are registered, each enabled only at the level it pops (`show != null`, then `period != null`, then `artist != null`) — safe because the states form a strict chain (a show is only ever set once a period is), so exactly one handler is ever enabled at a time. A fourth, deeper level (playback) doesn't need a state slot yet: track taps are a bare stub, same convention as the year grid's pre-Part-2.2 stub.
- **Shows: multi-row grid, not a horizontal row** — asked as an open question in the issue; owner chose grid. `TvShowBrowseScreen` reuses the existing `TvCard`/`LazyVerticalGrid` pattern from the years level (`GridCells.Fixed(4)`, wide cards) rather than introducing a new list shape. Shows sort newest-first via the existing `ShowSortMode.DATE_DESC`/`sortedByMode` — the phone's default — with no sort or tag-filter controls, which the issue scopes out for a browse-only 10-foot UI.
- **Tracks: a full-width `LazyColumn`, not a grid** — a list reads top-to-bottom, one D-pad lane, matching the phone's `RecordingScreen` track list shape more than the grids above it. Set grouping is a new pure function, `tvTrackSections`, rather than reusing MainActivity's private `groupedBySet`: that helper is `private` to `MainActivity.kt` and emits composables directly into a `LazyListScope`, so it isn't extractable without either making it public (widening a phone-specific seam) or duplicating its composable-emission shape on TV. `tvTrackSections` mirrors its adjacency-grouping semantics (a blank `PlayableTrack.setName` collapses into one unnamed section, dropping the header) as a pure, independently testable function instead.
- **Playback stub:** track row `onClick` is `/* Part 3: playback */`, the same bare-comment convention the year grid used pre-Part-2.2 — no placeholder screen, no fake `PlayerViewModel` call.
- **Tests:** `tvShowItems` (sort order, subtitle) and `tvTrackSections` (grouping, position numbering, unnamed-section collapse) — pure logic only, continuing D266/TvBrowseTest's convention that the composables themselves are a UAT item, not a Robolectric/Compose-UI-test target. 7 new tests; Android suite at 618, macOS unchanged at 438.

### D270 — Google TV playback: Now Playing screen, transport controls, queue navigation, volume routing, and progress/resume (#184)

Part 3 of #9 (completes TV playback layer, building on #182 TV foundation and #183 TV browse UI):

- **Now Playing screen (`TvNowPlaying.kt`):** A split landscape layout tailored for 10-foot TV viewing. The left pane renders high-resolution cover art (falling back to `ShowArtwork`/procedural art), large track title, artist and show/venue metadata, audio format badge (FLAC/MP3), a progress bar with current and total elapsed time, and a row of TV-focusable transport buttons (Previous, Rewind 15s, Play/Pause, Fast Forward 15s, Next). A ticker updates scrubber progress every 500ms while playing.
- **Queue navigation and track selection:** The right pane renders the active player queue as a scrollable `LazyColumn` of focusable TV Cards (`androidx.tv.material3.Card`). The actively playing track is highlighted with a distinct indicator (`▶`, bold title, primary color accent). D-pad up/down navigates between tracks; pressing Select/OK calls `vm.seekToTrack(index)` to immediately jump playback to that track.
- **Hardware remote transport & volume controls:**
  - `TvMainActivity.onCreate` sets `volumeControlStream = AudioManager.STREAM_MUSIC`, ensuring hardware volume keys on physical Android TV / Google TV remotes control music stream volume directly.
  - `TvMainActivity.onKeyDown` intercepts remote media key events (`KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_PLAY`, `KEYCODE_MEDIA_PAUSE`, `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`, `KEYCODE_MEDIA_FAST_FORWARD`, `KEYCODE_MEDIA_REWIND`, `KEYCODE_MEDIA_STOP`, `KEYCODE_HEADSETHOOK`), forwarding them to `PlayerViewModel`/`PlaybackService` so playback responds immediately to dedicated remote buttons.
- **Progress saving & resume on TV:**
  - Since TV playback uses the existing `PlaybackService` and `QueueInfo` with `queueKey`, progress rows are automatically written to Room's `progress` table every 5s and on pause/stop.
  - The TV artist browse screen displays a "Continue listening" shelf at the top if in-progress rows exist; selecting an in-progress show resumes playback at the saved track and position.
  - The TV track list screen checks for saved progress on the show being browsed; if present, a prominent "▶ Resume ({trackTitle})" button appears in the header. Selecting any track starts playback at that track and opens the Now Playing screen.
- **Tests:** 13 new unit tests in `TvNowPlayingTest.kt` (progress fraction, subtitle formatting, continue listening filtering) and `MediaItemsTest.kt` (extras `DURATION_MS` persistence); Android suite at 631, macOS unchanged at 438.


### D271 — Correctness bug fixes in CouchTourKit: loudness buffer slide, show date bounds, and progress-save error handling (#330)

Addressed three correctness defects and audit findings across `CouchTourKit`:

- **F1: `LoudnessMeter.slideBlock()` overlap-safe copy (`Loudness.swift`):** Replaced `update(from:count:)` (documented undefined behaviour for overlapping memory slices `[0, keep)` and `[stepSize, blockSize)`) with `memmove`. Added `testFullScaleSineExercisesMultipleBlockSlidesAndPinsLUFS` in `LoudnessTests.swift` exercising 16 consecutive slide operations and asserting the integrated loudness matches ITU-R BS.1770-4 (-3.0 ± 0.1 LUFS, 0.0 dBFS peak). Added `uat-066` for human listening verification.
- **F2: `formatShowDate` month/day bounds (`Format.swift`):** Bounded month to `1...12` and day to `1...31` in the fast numeric branch, falling through to `DateFormatter` and raw string fallback on failure. Added `testFormatShowDateRejectsOutOfRangeMonthAndDay` in `FormatTests.swift` verifying out-of-range inputs are rejected from formatting into YYYY-MM-DD.
- **F3: `ProgressRecorder` error handling and write retry (`ProgressRecorder.swift`):** Introduced `ProgressWriting` protocol implemented by `ProgressStore`. In `saveTick`, moved state updates (`lastSaveTime`, `lastSavedQueueKey`, `lastSavedTrackIndex`, `lastSavedPositionMs`) to execute only after a successful `store.put(row)`, caught and logged failures via `NSLog`, and returned `false`. Added `testSaveTickReturnsFalseWhenStoreThrowsAndRetriesNextTickAtSamePosition` in `ProgressRecorderTests.swift` proving that a failed write returns `false`, preserves `lastSaved*` state, and permits subsequent retry at the same position.
- **Queue key consistency (`QueueKey.swift`, `Catalog.swift`):** Centralized `recordingShowKey` in `QueueKey.swift` using `recordingPrefix`, marked `RecordingId` as `Sendable` to eliminate Swift 6 concurrency warnings, and documented why `parseQueueKey` intentionally rejects two-part recording show keys. Added regression tests in `QueueKeyTests.swift`.
- **Tests:** 5 new macOS package tests (443 total, 0 failures); Android suite unchanged at 631.


### D272 — Android pure-logic correctness bug scan (#329)

Addressed six seeded correctness defects and audit findings across Android pure-logic modules (`OnThisDate.kt`, `Format.kt`, `Queue.kt`, `FillerTracks.kt`):

- **F1: `phishInRanges` contiguous and ascending span batching (`OnThisDate.kt`):** Spans are sorted ascending by start year before batching, and only merged into the current batch when contiguous or overlapping (`span.first <= last.first.last + 1`) and within `cap`. Prevents out-of-order or gapped input from spanning years not present or double-fetching shows. Added regression test in `OnThisDateTest.kt`.
- **F2: `OnThisDate.load` concurrent deduplication (`OnThisDate.kt`):** Wrapped the cache miss path in a `kotlinx.coroutines.sync.Mutex` with double-checked locking so concurrent callers for the same key execute only a single fan-out. Added injectable `source` seam parameter and regression test in `OnThisDateTest.kt` verifying single fan-out.
- **F3: `monthDay` digit validation (`OnThisDate.kt`):** Checked that all 8 non-hyphen positions in `YYYY-MM-DD` strings are ASCII digits, preventing non-digit malformed dates like `"abcd-ef-gh"` from matching anniversary shows. Added regression assertion in `OnThisDateTest.kt`.
- **F4: `formatCompactDuration` KDoc alignment (`Format.kt`):** Retained `h:mm` for hour+ durations and `m:ss` for sub-hour durations to match `Format.swift` and keep set/show headers compact without duplicating `fmt()`. Corrected KDoc to state explicitly that seconds are dropped past an hour to fit headers and badges. Pinned behavior with regression tests in `FormatTest.kt`.
- **F5: `formatShowDate` parity and bounds validation (`Format.kt`):** Extended `formatShowDate` to accept `YYYY/MM/DD`, unpadded month/day (e.g. `1997-5-8`), and standard date formats (e.g. `May 8, 1977`), while bounding month to 1..12 and day to 1..31, matching `Format.swift` (D271). Added regression tests in `FormatTest.kt`.
- **F6: `formatRemainingTime` parity with `Format.swift` (`Format.kt`):** Resolved divergence where `Format.kt` returned a negative clock string (`"-7:32"`), whereas `Format.swift` returned a suffixed string (`"7:32 left"`). Aligned `Format.kt` to match `Format.swift` by returning `"${fmt(remainingMs)} left"` and guarding `durationMs <= 0L` to return `"0:00 left"`. Added parity test in `FormatTest.kt` and updated `LedgerLayoutTest.kt`.
- **Queue and filler track logic (`Queue.kt`, `FillerTracks.kt`):** Documented in `Queue.kt` that `parseQueueKey` intentionally rejects two-part `recordingShowKey` because playback cannot resume without a specific tape id; added regression tests in `QueueTest.kt`. In `filterPlaybackTracks`, handled all-filler track lists by falling back to the unfiltered track list so playback does not stall or drop the show; added regression test in `FillerTracksTest.kt`.
- **Tests:** 8 new Android unit tests in `OnThisDateTest.kt`, `FormatTest.kt`, `QueueTest.kt`, and `FillerTracksTest.kt`; Android suite at 641, macOS unchanged at 443.

### D273 — Sync backend correctness bug scan and first test harness: `@cloudflare/vitest-pool-workers` against real Miniflare D1 (#328)

`sync/` had no test harness at all — `npm run typecheck` and a live staging smoke test were the entire safety net. Added `@cloudflare/vitest-pool-workers` + `vitest` (`sync/vitest.config.ts`, `sync/test/sync.test.ts`) rather than a hand-rolled D1 mock, because the defects found here are specifically about what D1 does under batched and interleaved writes — a mock would assert the bug rather than catch it. Tests call the exported Worker directly (`worker.fetch(request, env, ctx)`, both from `cloudflare:test`) against a real (Miniflare-backed) D1 instance, with `schema.sql` applied fresh in a `beforeEach` since this pool version doesn't isolate D1 storage between tests in the same file the way earlier versions did. `npm test` (`vitest run`) is now gated into `sync-deploy.yml` alongside `typecheck`.

Fixed four seeded correctness defects in `sync/src/index.ts`:

- **F1: duplicate `queueKey` within one push resolved by array order, not `updatedAt`.** `applyIncomingChanges` filtered each incoming change against a snapshot of existing rows alone, so two entries in one push sharing a `queueKey` both passed the LWW filter, both got a seq slot, and D1 applied the resulting batch in array order — last one in wins regardless of which had the newer `updatedAt`, orphaning the other's seq. Now deduped by `queueKey` (keeping the highest `updatedAt`, ties going to the later array occurrence) before the existence lookup and LWW filter, so exactly one statement and one seq are allocated per key. Regression test proves array order no longer decides the outcome.
- **F2: the returned cursor was computed from a `seqs.next` read taken before `applyIncomingChanges`'s atomic bump.** If another device in the group pushed in the gap between that read and the response being assembled, the rows returned could carry seqs higher than the cursor reported, and the client would re-pull the same rows on every subsequent sync. The response `seq` is now derived from the seq of the last row in the (already seq-ordered) result set, bounded by `cursor.retentionFloorSeq` and `since` — derived from the same fresh read that produces the rows, not a value cached earlier in the request. Bounding by `retentionFloorSeq` ensures that when active rows exist below the retention floor (e.g. pre-purge rows), a full resync (`since = 0`) advances the client's cursor to at least the floor so follow-up syncs do not trip HTTP 410 and loop infinitely. Regression tests cover both concurrent push interleaving and full-resync cursor bounding above the retention floor.
- **F3: `trackIndex`, `positionMs`, and `deletedAt` accepted negative integers.** `parseProgressFields` checked type but not sign, and both clients index a track list with `trackIndex` straight off the wire. Added `nonNegativeInt`/`optionalNonNegativeInt` validators; each field now 400s on a negative value via the existing `ValidationError` path.
- **F4: `purgeOldTombstones` read `rowsPurged` from a separate `COUNT(*)` taken before the `DELETE` it's meant to describe**, so a tombstone crossing the retention cutoff between the two statements could be counted without being deleted, or vice versa. Fixed by reading the count off the `DELETE` statement's own `meta.changes` in the same batch, eliminating the second read (and the race it created) entirely rather than just narrowing the window.

- **Tests:** 14 new vitest tests in `sync/test/sync.test.ts` covering the four fixes above plus the existing happy paths (pair → claim → push/pull round trip, 410 on a stale cursor with the `since = 0` exemption, and retention floor cursor bounding). Android suite unchanged at 641, macOS unchanged at 443.

### D274 — Android volume leveling: AudioProcessor gain in PlaybackService, decode-ahead measurement in VolumeLeveler (#267)

Completes Part 3/5 of #18 (Android counterpart to macOS D259):
- **Custom `LevelingAudioProcessor` in Media3 sink:** installed via `DefaultRenderersFactory.buildAudioSink` in `PlaybackService`. Sits after decode, applying to both MP3 and FLAC. Applies static linear gain clamped between -12 dB and +12 dB with a 50 ms ramp to eliminate clicks at track boundaries and transitions. Float writes are atomic on the JVM, avoiding locks in the realtime audio path.
- **Background decode-ahead measurement (`LoudnessMeasurer` + `VolumeLeveler`):** on queue start, if volume leveling is enabled, `VolumeLeveler` checks Room's `source_loudness` table (`SourceLoudnessDao.getCurrent`). On cache miss, it starts playback at 0 dB (unity) and pulls 30-second slices from the middle of up to 3 tracks using HTTP Range requests against MP3 URLs, decoding via `MediaCodecSegmentDecoder`, and measuring loudness using `LoudnessMeter`. When measurement completes, the result is cached and the gain is dynamically updated in-place mid-playback.
- **Cast excluded:** Google Cast receivers decode the audio directly; the local AudioProcessor gain is bypassed while casting. Help text on the settings toggle notes this.
- **Settings toggle:** "Level volume across sources" (`PlaybackSettings.levelVolume`) off by default, persisted across app restarts. Toggling off resets gain to 0 dB immediately with a smooth ramp; toggling on applies cached gain or triggers decode-ahead measurement.
- **Tests:** 25 unit tests in `VolumeLevelingTest` (gain math, int16 clamping, float PCM, 50ms ramp, track spread, HTTP Range pooling and fallback) and `VolumeLevelerTest` (cache hit/miss, stale version, failed measurement leaving no row, dynamic update, disabled setting); Android suite at 666, macOS unchanged at 443.


### D276 — Volume leveling post-beta decisions: default-off, clear-cache action, constant confirmation (#269)

Completes Part 5/5 of #18 (#269):
- **Default state:** "Level volume across sources" toggle defaults to OFF on both Android (`PlaybackSettings.levelVolume = false`) and macOS (`PlaybackSettings.levelVolume = false`). The feature remains opt-in until broader field evaluation.
- **Constants and algorithm confirmed:** constants remain exactly as defined in #265 — target -18 LUFS, clamp ±12 dB, peak headroom cap at -1 dBFS (`-1.0 - samplePeakDb`), and `algorithmVersion = 1`. No changes to meter math or version bump needed.
- **Clear measured loudness action:** added "Clear measured loudness" action next to the toggle in Settings on both Android (`SettingsScreen.kt` via `SettingsActionRow`) and macOS (`PlaybackSettingsView.swift` via "Clear Measured Loudness" button). Triggers database deletion (`SourceLoudnessDao.clearAll()` on Android, `ProgressStore.clearAllSourceLoudness()` on macOS) and cancels any in-flight background measurement tasks (`VolumeLeveler.clearAll()`, `LoudnessMeasurer.clearAll()`), resetting active player gain to unity (0 dB / 1.0 linear).
- **Applied-gain display:** owner chose to make applied-gain display optional; deferred and not added to the UI.
- **Cast exemption:** confirmed settings help text explicitly states Cast sessions receive no volume leveling on both platforms.
- **Tests:** unit tests covering clear-cache action and in-flight cancellation on both platforms (`VolumeLevelerTest.clearAll empties cache and cancels in-flight measurement` on Android; `LoudnessMeasurerTests.testClearAllEmptiesCacheAndCancelsInFlightMeasurement` on macOS), plus fresh-install default-off and toggle persistence (`PlaybackSettingsTest.kt` on Android, `PlaybackSettingsTests.swift` on macOS); Android suite at 668, macOS suite at 448.


### D278 — Sidebar remains and Artists nav row added; D203 superseded (#348)

The Ledger redesign added `SidebarView.swift` (2a6b2cc) and `ThreePaneRootView` still mounts it at `ThreePaneRootView.swift:23`. D203 — “No sidebar: Home is the hub…” — and the `Navigation.swift` header comment claiming “The sidebar is gone (D203)” are doc drift: the sidebar was never removed, it was redesigned.

This issue restores discoverability for artist browsing on macOS. `Route.artists`, `ArtistsView` and its navigation destination already existed and worked; only the sidebar chrome was missing.

- Added a primary nav row “Artists” second after Home in `SidebarView.swift`. Icon `music.mic`, `isSelected: appModel.path.last == .artists`, action `appModel.jump(to: .artists)` to match the existing ⌘2 menu binding.
- Position matches the two-places / four-tools ordering decided in the sort pass; no other rows moved.
- Selection predicate mirrors siblings; row does not stay lit while drilling into an individual artist — flagged for owner rather than silently changed.
- D203’s claim that the sidebar was removed is superseded for navigation chrome. The one-stack, breadcrumb, and search-as-chrome parts of D203 remain in force.



### D279 — macOS stays sandboxed everywhere; local installs keep entitlements; one-time merge from unsandboxed stores (#345)

The Sparkle (CI) build is App Sandbox'd (D104), but `install.sh` / `install-beta.sh` re-signed with `codesign --force --deep --sign -` and no entitlements, silently stripping the sandbox. The two builds then used different stores (container vs `~/Library/Preferences` + `~/Library/Application Support`), so favorites, liked tracks, settings, sync cursors and history swapped or vanished when moving between them. D104 stands; the fix is to make the two paths identical and fold the stranded data in.

- **Install scripts:** after the existing deep ad-hoc sign, a second shallow `codesign --force --sign - --entitlements Generated/...entitlements` pass puts the entitlements on the app executable only (`--deep` would push them onto Sparkle's nested helpers).
- **Migration:** `UnsandboxedMigration.migrateIfNeeded` (`Migration.swift`) runs first in `AppModel.init()`, before any store reads `UserDefaults` or opens the database. Sandboxed launches only, once per bundle (`migration_done_v1` in the container's defaults; left unset if a source couldn't be read, so it retries). Beta and prod migrate independently from their own plist and Application Support directory.
- **Merge rules, never discarding either side:** favorites and liked tracks are unioned. Playback/theme/volume settings are copied only where the container has no value. Sync `lastSeq` / `lastPushWatermark` take the lower value (worst case is a re-pull or re-push, never a skipped row); `lastSyncedAt` takes the higher. `progress`, `artist_tour_preferences` and `taper_preferences` rows merge by higher `updatedAt`, tombstones included, so a deletion on either side survives. The source database is copied to a scratch directory first (WAL needs a writable `-shm`) and opened through `ProgressStore` so an older schema is upgraded.
- **Entitlement:** reading the unsandboxed files from inside the sandbox needs `com.apple.security.temporary-exception.files.home-relative-path.read-only`, scoped to exactly that bundle's plist and Application Support directory. This is a Mac App Store blocker (temporary exceptions are generally rejected there); the exception should be dropped once the migration has had time to run on the owner's Macs.
- **Not covered:** the runtime behavior of the read exception under a real sandbox was not exercised in the unattended run (no UI automation); it is listed for UAT.
- **Tests:** `MigrationTests` (7): unsandboxed no-op, set union, scalar fill, sync cursor rules, row merge by `updatedAt` incl. tombstones and preference tables, idempotency, no-source completion. macOS suite 448 → 455.

### D283 — Next Tour Stop chips select focus, track-tour moves to header action (#354)

The artist chips on the Android Home "NEXT TOUR STOP" card now filter the card to that artist's oldest unplayed show, rather than opening the tour picker.

- Tapping a favorite-artist chip sets a `rememberSaveable` focus state in `HomeScreen`. The filter runs client-side against the already-fetched candidate list, so it never hits the network and `NextStop.load`'s cache key is untouched.
- A second tap on the focused chip clears focus, restoring the cross-artist default.
- The tour picker moved to a "Change tour…" action in the card header. A labeled header action was chosen over a chip long-press because a long-press has no `contentDescription` and no visible affordance, which would have introduced the exact class of invisible-control defect the Ledger audit (#356) is filing.
- This supersedes the part of D214 (#133) that wired the chip tap directly to the tour picker.

### D285: Lowercase is the canonical wire format for ExternalReleasePlatform
- **Context:** The curated/heuristic matches for external releases (Spotify, Tidal) are bundled as JSON assets. Android's `ExternalReleasePlatform` enum lacked `@SerialName`, causing failures to decode the lowercase `"spotify"` string in the JSON because `ignoreUnknownKeys = true` swallows the `SerializationException`.
- **Decision:** Align Android's enum to the JSON format by adding `@SerialName("spotify")` and `@SerialName("tidal")`.
- **Why:** Lowercase is the canonical wire format. `scripts/generate_heuristic_matches.py` emits lowercase, and the macOS client expects lowercase (its raw string enum values are lowercase). Modifying the JSON would break the generator and the macOS decoder.

### D288: Distinguish empty from loading states for macOS sidebar favorites

**Date:** 2026-09-30

When the Relisten/favorites fetch was loading or failed, the macOS sidebar fell back to rendering design-mockup artists (Goose, WSP, etc.) which could mislead users into thinking they favorited them. We removed the mock data and replaced it with a muted "Star artists to pin them here" hint. To prevent this hint from flashing on every cold launch while favorites resolve, `isFavoritesLoaded` explicitly tracks the fetch state, rendering an empty space under the header until `loadFavorites()` finishes.

### D290: AX tree walking is bounded and identifier-scoped by construction
- **Context:** Agent-driven UI testing on macOS requires querying the accessibility tree. Calling `entire contents` on "Couch Tour Beta" hangs AppleScript. Also, frontmost-window targeting is fragile in CI environments.
- **Decision:** Smoke checks (`scripts/smoke/ax-tree.sh`) walk the UI tree using a bound of maximum depth (default 6) and a hard timeout (5 seconds), scoping output strictly to known IDs from `AXIdentifiers.swift`. Target is resolved strictly by window owner (`dev.mike.couchtour.mac.beta`) rather than screen coordinates or `frontmost`.
- **Why:** Bounding prevents the walk from hanging (the exact issue that motivated #365). Identifier scoping keeps the output small enough for agent context windows. Window-owner targeting ensures the test queries the intended application even if another window steals focus.

### D292: Core on-device diagnostics log (storage, rotation, retention, redaction)

Part of #344 (#373). Foundation for on-device diagnostics and issue reporting.

- **Storage Location**: `context.filesDir/diagnostics/` — internal storage, not `cacheDir`, because Android may evict cache under pressure and a diagnostics log that vanishes when the app crashes or runs low on memory is worthless. Not world-readable; the viewer (#376) and Feedback flow (#377) are the only exports.
- **Files & Bounded Footprint**: `diagnostics.log` (current) and `diagnostics.log.1` (one previous generation). The cap is 1 MiB per file, bounding total on-disk diagnostics footprint at 2 MiB.
- **Rotation**: On append, if `diagnostics.log` is already at or over the 1 MiB cap, `diagnostics.log.1` is deleted, `diagnostics.log` is renamed to `diagnostics.log.1`, a fresh `diagnostics.log` is started, and a `log.rotated` entry is written into it before the new event.
- **Retention**: On `init`, retention runs across both generations (`diagnostics.log` and `diagnostics.log.1`). Lines older than 7 days are dropped regardless of file size, pruning empty files if all entries expired. If a file is still at or over the cap after time-based pruning, it is truncated down to the last 2000 lines. Rotation handles the active app case; startup retention cleans up stale generations and long-idle logs.
- **Line Format**: `<ISO-8601 UTC timestamp>\t<LEVEL>\t<event>\t<key>=<value> ...` (single line per entry, tab-separated). Parses cleanly with `split('\t')` and survives copy-paste into GitHub issue reports.
- **Redaction Denylist**: Key names containing `token`, `secret`, `password`, `passwd`, `auth`, `credential`, `cookie`, `pairing`, `code`, or `key` (as whole words, camelCase like `accessToken`/`syncKey`, or compound names like `access_key`/`sync_key`) have their values replaced with `***` before writing. Splitting on camelCase boundaries and non-alphanumeric delimiters occurs before lowercasing so all naming conventions are caught. Callers logging token-shaped values can also pass them through `redactValue`.
- **Asynchronous Writes with Synchronous Crash Exception**: `DiagnosticsLog.log(...)` is non-blocking and non-throwing on the main thread, dispatching via `Channel.trySend` to a single-writer coroutine on `Dispatchers.IO`. If writing fails, logging is latched off for the rest of the process. `recordCrash` is the deliberate single synchronous exception, appending directly inside `runCatching` because the process is dying and queued writes would be lost (#374).

### D293: Diagnostics viewer (plain-text share, 200-line window, confirmable clear)

Part of #344 (#376). Dedicated diagnostics viewer in Settings → About → Diagnostics.

- **Plain-text share (no FileProvider, bounded for Binder limits)**: Diagnostics export text (`DiagnosticsLog.exportText(context)`) goes out via `ACTION_SEND` with `EXTRA_TEXT` and `text/plain` (with `EXTRA_SUBJECT = "Couch Tour diagnostics"`), and the Copy action writes the same text to the system clipboard. Attaching a physical `.txt` file for GitHub issues would require configuring a `FileProvider`, a `<paths>` XML resource, and manifest additions for a benefit pasting into an issue body already delivers with zero friction. Export text is bounded to `MAX_EXPORT_BYTES` (256 KiB, tail-aligned to complete log lines), ensuring neither `ClipData` nor `startActivity(Intent.createChooser)` exceeds Android's 1 MiB Binder transaction limit (`TransactionTooLargeException`) when both log generations are full.
- **200-line viewer window**: The viewer presents `DiagnosticsLog.tailLines(200)` in chronological order (newest-last). A 1 MiB raw log is unreadable and sluggish on a mobile screen. The 200-line tail captures the immediate diagnostic context of the last session or two, while Copy and Share remain available to export full log history across both generations.
- **Off-main-thread I/O**: All disk operations (`tailLines`, `summaryLines`, `exportText`, `clear`, `onDiskBytes`, `countEntries`) execute on `Dispatchers.IO`. In `SettingsScreen`, the entry count and byte size are asynchronously loaded via coroutines so composition remains entirely non-blocking. `CrashCapture.install()` registers the uncaught handler synchronously without disk I/O, dispatching previous crash file detection to `Dispatchers.IO`.
- **Confirmable clear with crash notice erasure**: Clearing requires explicit user confirmation in a dialog. On confirmation, both `diagnostics.log` and `diagnostics.log.1` are removed, `marks` are cleared, and `last_crash.txt` is deleted via `CrashCapture.consumePreviousCrash()`, preventing stale crash traces from persisting. Dismissing the crash notice card runs `CrashCapture.consumePreviousCrash()` on a coroutine, clearing UI state immediately and deleting the file on `Dispatchers.IO`.

### D294: Diagnostics event vocabulary and privacy bounds

Part of #344 (#375). Instruments library, playback, sync, API, and navigation events into on-device diagnostics.

- **Event Vocabulary**: Stable, lowercase, dot-separated event names across all client subsystems:
  - `library.favorite`: changes to favorited tracks (`kind=track id=<id> on=<bool>`), saved shows (`kind=show key=<key> on=<bool>`), and favorited artists (`kind=artist key=<key> on=<bool>`).
  - `library.counts`: aggregated counts upon completed Library loads (`playlists=<n> shows=<n> tracks=<n> total=<n>`), also marked in summary. Fires on every load including the empty/zero case to diagnose missing-item defects (#343).
  - `library.playlist`: local playlist membership updates (`action=add|remove track=<id> playlist=<id>`).
  - `playback.start`: queue start and unpause events (`track=<id> show=<show> source=<backend> resume=<bool>`).
  - `playback.stop`: playback pause, stop, or track completion.
  - `playback.progress`: low-frequency debounced progress saves (`track=<id> pct=<0-100> status=saved`).
  - `sync.start` & `sync.end`: sync lifecycle (`pushed=<n> pulled=<n> ms=<n> pushed_upto=<watermark> pulled_seq=<seq>`) and summary mark.
  - `sync.error`: sync failure with short classification codes (`unauthorized`, `gone`, `network`, `server`, `other`). Never raw exception messages.
  - `api.call`: request telemetry from `TimingEventListener` (`path=<encodedPath> phase=start|connected|end|failed ms=<n> reused=<bool>` and on failure `error=<exception simple name>`).
  - `nav.route`: destination changes in Navigation (`route=<route pattern>`).
- **Privacy Bounds ("Counts, IDs, Routes, Timings Only")**: Diagnostics logs never record request/response bodies, HTTP headers, query strings, authorization tokens, secrets, or user-typed text. Full URLs are strictly disallowed; API events record `encodedPath` only. Navigation events record route templates only (stripping query parameters and argument values). Bare keys `code` (error classifications) and `key` (show dates/artist identifiers) are preserved while compound credentials (`apiKey`, `syncKey`, `pairingCode`, `accessToken`, etc.) remain redacted to `***`.

### D295: Crash capture, synchronous-on-crash write, and handler chaining

Part of #344 (#374). Uncaught exception capture, synchronous disk logging, and next-launch crash notification.

- **Synchronous Write on Crash**: Unlike normal diagnostics events which queue onto a background channel to avoid blocking caller threads (D292), crash capture is explicitly synchronous. When an uncaught exception escapes, the runtime immediately terminates the process upon return from the uncaught exception handler; any asynchronous or queued coroutine writes would be discarded before flushing. Both `DiagnosticsLog.recordCrash` and writing to `context.filesDir/diagnostics/last_crash.txt` execute synchronously on the crashing thread inside isolated `runCatching` blocks.
- **Handler Chaining**: `CrashCapture.install()` chains in front of the existing `Thread.getDefaultUncaughtExceptionHandler()` (on Android, `RuntimeInit$KillApplicationHandler`). The previous handler must always be invoked after recording; swallowing the exception would leave the process in a broken zombie state, preventing standard OS crash reporting, death callbacks, and ANR generation.
- **Isolated Error Boundaries**: Every step of crash capture—initializing `DiagnosticsLog`, recording to `diagnostics.log`, writing `last_crash.txt`, and delegating to the previous handler—is individually wrapped. Failure in one step (such as disk full or an unwritable sink) never prevents subsequent steps from executing or prevents delegation to the previous handler, ensuring no secondary exception escapes the handler.
- **Lazy Fallback on OOM**: Handler detail evaluation uses `getOrElse` rather than `getOrDefault` so fallback strings are evaluated lazily. If an `OutOfMemoryError` causes stack trace generation to fail, eager argument evaluation would re-throw outside `runCatching`; lazy fallback ensures `previousHandler` is always reached.
- **URL and Query String Sanitization**: Stack traces and exception messages are stripped of URLs and query parameters before writing to `diagnostics.log` and `last_crash.txt`, ensuring unhandled network exceptions do not expose auth tokens or pairing codes in exportable diagnostics.
- **Next-Launch Detection without Premature Erasure**: In `CouchTourApp.onCreate()`, `CrashCapture.detectPreviousCrash()` checks for `last_crash.txt` on `Dispatchers.IO` to satisfy D293 and prevent main-thread disk I/O. If present, it records a `crash.previous` diagnostics event with the crash timestamp and first line of the trace, records a summary mark (`Last crash`), and exposes the notice via `CrashCapture.lastCrashNotice` (`StateFlow<String?>`). The `last_crash.txt` file is preserved across relaunches until explicitly consumed via `CrashCapture.consumePreviousCrash()`, so that a user who does not open Settings immediately can still export or inspect it later.

### D296: Feedback diagnostics tail copy and summary prefill

Part of #344 (#377). Attaches diagnostic evidence to feedback issues with clipboard tail copy and prefilled body summary.

- **Clipboard Tail vs URL Body Summary Split**: A GitHub issue URL (`https://github.com/mkny13/couch-tour/issues/new?...`) cannot carry 200 lines of raw log without risking truncation or rejection by browsers and intent dispatchers due to URI length limits. Instead, `launchFeedback` writes the full 200-line tail from `DiagnosticsLog.tailLines(200)` to the system clipboard via `ClipboardManager` and instructs the user to paste it under a `## Log` heading. The prefilled issue URL body receives only the concise summary from `DiagnosticsLog.summaryLines()` (last crash, last sync result, library counts, total entry count, and on-disk size).
- **URL Length Guard**: The summary injected into the issue URL body is capped at ~1,000 characters (`MAX_SUMMARY_CHARS`). In pathological cases with excessive mark lines, older lines are truncated chronologically, ensuring recent marks and metadata are preserved while keeping the generated URL well under safe browser limits (< 2,500 characters).
- **Loud Clipboard Copy with Toast**: Since copying to the clipboard is the mechanism that carries the full log evidence without requiring a `FileProvider`, it provides immediate user feedback via a Toast ("Diagnostics copied to clipboard") on the main looper.
- **Off-Main-Thread Log Read and Clipboard Write**: All diagnostics disk operations (`summaryLines()`, `tailLines(200)`) and the `ClipboardManager.setPrimaryClip` write execute on `Dispatchers.IO`, keeping the main thread and UI entirely non-blocking when Feedback is pressed.
- **Per-Install Preference Defaulting to On**: An "Include diagnostics" toggle is added to Settings → About (backed by `FeedbackSettings` in `SharedPreferences`), defaulting to `true` on a fresh install to ensure diagnostics are included by default. When turned off, the app preserves total silence: no clipboard write, no summary section, and no toast, producing byte-identical output to the legacy issue template and URL shape (#295).
- **Single Source of Truth across Call Sites**: All three feedback trigger sites in the app (`SettingsScreen`, `LibraryScreen`, and `MainActivity`) route through `launchFeedback`, ensuring consistent clipboard copy and URL construction without duplicating clipboard logic.

### D297: Window chrome placement for macOS Cast and AirPlay control

Fixes #311. Moves the desktop Cast and AirPlay picker (`CastRoutePickerButton`) from the playback-conditional player rail into the center-pane `NavigationStack` toolbar.

- **Window Chrome Toolbar Placement**: Desktop Cast & AirPlay shipped under D196 with `CastRoutePickerButton` mounted in `PlayerRailView` and `ExpandedNowPlayingView`. In the player rail, it was nested inside the `if let show = player.show` branch, causing the button to vanish whenever no show was loaded (e.g. cold-launching on Home with an empty player). The control is moved into the center-pane `NavigationStack` toolbar (`ThreePaneRootView.swift`) as a `.primaryAction` `ToolbarItem`, ensuring it is present across all center-pane screens (Home, search, artist, show, playlists) regardless of playback state, matching the Android client's header placement (`MainActivity.kt`).
- **Declaration Order & Top-Right Alignment**: Declared before `searchField`, `FeedbackButton`, and the expanded player button. SwiftUI orders `.primaryAction` toolbar items right-to-left, placing `CastRoutePickerButton` at the top-right position of the window, consistent with Android's top-right header placement.
- **Move, Not Duplicate**: The copy in `PlayerRailView` is removed rather than duplicated, leaving `volumeControl` alone in the footer slot. This avoids duplicate `CastDiscovery` start/stop lifecycles and keeps exactly one picker visible during normal playback (the sheet in `ExpandedNowPlayingView` retains its own copy because it covers the window toolbar).
- **Always Visible**: Unlike Android (which hides the cast button until a cast receiver is discovered), macOS keeps `CastRoutePickerButton` visible even with zero discovered Cast receivers, because it also acts as the system audio-output / AirPlay picker (`AirRoutePickerView`).
- **Accessible Name and Hover Help**: Added `.accessibilityLabel("Cast and AirPlay destinations")` and `.help("Cast and AirPlay destinations")` to `CastRoutePickerButton`, providing a readable name for VoiceOver and a hover tooltip while preserving the dynamic connected device label and icon.

### D298: Per-artist-row entry point for macOS tour picker

Fixes #361. Resolves unreachable macOS tour picker by attaching entry points to NEXT TOUR STOPS card rows.

- **Per-Artist-Row Placement**: On Android (#354), the Next Tour Stop card displays a single focused show alongside an artist chip row, placing the "Change tour…" affordance in the card header. On macOS, the card displays up to three distinct artist rows with no chip selector. Placing the entry point in the card header would lack a clear artist target; instead, each `tourStopRow` provides a direct "Track tour…" action and a matching `.contextMenu` item targeting `show.artist`.
- **Dual Affordance**: Provides both an inline text button (`Track tour…`) styled with `accentTintText` and a right-click `.contextMenu` item (`Label("Track tour…", systemImage: "mappin.and.ellipse")`). This keeps the action discoverable without cluttering the row layout (artist, date, venue/tour subtitle, rating, play button).
- **Direct Target Resolution**: Target resolution is immediate (`show.artist`), avoiding complex focus-state fallbacks.
- **Reused Sheet and Refresh Pipeline**: `TourPickerSheet` (from #68/D190) and its post-save/clear cache reset and refresh path (`NextStop.resetCache()` → `reloadProgress()` → `reloadDiscovery()`, D200/#100) are reused completely unchanged.

### D300: On This Date uses Relisten's on-date endpoint, removing year budget and raising artist cap to 10

Part of #350. Supersedes the Relisten half of D162 (D162's phish.in range-batching and daily caching design remains active; do not rewrite D162).

- **The Problem with D162's Relisten Year Budget**: D162 assumed neither backend had a cross-year date query, requiring client-side period fetches. For Relisten, which has no multi-year range queries, D162 introduced `RELISTEN_YEAR_BUDGET` (12) year-fetches split evenly across at most `MAX_RELISTEN_ARTISTS` (3) favorited artists, taking the most recent years first. For deep-catalog artists like moe. (active since 1990) or the Grateful Dead (1965–1995), anniversaries in older decades were never queried and never appeared on the Home screen's "On this date" shelf.
- **Dedicated Relisten On-Date Endpoint**: Relisten provides `GET /v2/artists/{slug}/shows/on-date?month=M&day=D` (with integer `month` and `day` query parameters), returning all shows played on that month and day across every recorded year in a single HTTP request. Response objects match `RelistenShowSummary` and map with `toShowSummary(artist:)`.
- **MusicSource Seam Extension**: Added `showsOnDate(artist: ArtistRef, month: Int, day: Int)` to `MusicSource` on both Android and macOS with default empty implementations so existing mock sources continue compiling. `RelistenCatalogSource` overrides this to call `RelistenApi.showsOnDate` / `RelistenAPI.showsOnDate`.
- **Elimination of Relisten Year Budget**: `RELISTEN_YEAR_BUDGET` / `relistenYearBudget` and the year-splitting calculations are removed entirely. Relisten anniversary discovery now executes exactly one request per favorited artist instead of walking individual years.
- **Raised Artist Cap**: Since each favorited Relisten artist costs only a single request per day rather than up to 12, `MAX_RELISTEN_ARTISTS` / `maxRelistenArtists` is raised from 3 to 10. Worst-case daily network cost is now ~14 requests (10 Relisten + ~4 phish.in batched ranges), fully cached per date+favorites in `OnThisDate`.
- **Same-Year Exclusion Preserved**: The endpoint returns shows from the current year if any occurred; `showsOnAnniversary` continues to filter out shows from today's own year, ensuring "On this date" remains strictly retrospective. The 8-show cap (`MAX_ANNIVERSARY_SHOWS`), randomized selection, and newest-first display order are preserved unchanged.

### D302: Liked Relisten tracks store display metadata, revising D161's flat-Set<String> shape

Part of #343 (#372). Revises D161's flat-`Set<String>` shape for `LikedTracks` so that liked Relisten tracks can render listable rows and resolve playback in the Library (#380) without pre-fetching whole shows.

- **Revising D161's Flat Set**: D160/D161 chose a flat `Set<String>` of track UUIDs stored under `track_ids` in `SharedPreferences`, mirroring `Favorites`. While adequate when only displaying filled hearts on setlists where `PlayableTrack` was already in memory, a bare UUID is insufficient for rendering a Library track list (#380 / #343). Neither backend has a fetch-track-by-id API; displaying a list of liked tracks would require fetching every parent show up-front.
- **Store Shape (`LikedTrackRef`)**: `LikedTracks` now stores `Map<String, LikedTrackRef>` serialized as JSON via `kotlinx.serialization` under a new preference key `track_records`. `LikedTrackRef` captures `id`, `title`, `showDate`, `venueName`, `durationMs`, `artistName`, `artistSlug`, `recordingId`, `artUrl`, `likedAt`, and `backend`. All display fields are defaulted or nullable so legacy likes without metadata load seamlessly.
- **SharedPreferences Retention**: Kept in `SharedPreferences` rather than migrating to Room. Liked tracks remain a single flat collection without relational joins or per-playlist ordering, avoiding unnecessary database schema bumps and Room migrations.
- **Backwards-Compatible Migration on `init`**: `LikedTracks.init(context)` reads both `track_records` and legacy `track_ids`. Any legacy ID not present in records is synthesized into a `LikedTrackRef` with blank metadata and `likedAt = 0`. Legacy likes remain liked and can be unliked. Corrupt or unparseable JSON degrades safely to an empty store and logs to `DiagnosticsLog` without crashing startup.
- **Now Playing Integration & Tape Identity**: Updated `LikeTrackButton` to take `LikedTrackRef`. In `NowPlaying`, `LikedTrackRef` is constructed from `PlayerState`. Because `PlayerState` does not carry recording IDs directly, a pure helper `deriveRecordingId(queueKey)` extracts the bare tape source ID from `QueueKind.RECORDING` queue keys (`relisten:artist/date/source`). Other queue kinds (shows, shuffle, local playlists) fall back to `recordingId = null`, matching `LocalPlaylistTrackEntity` semantics.
- **Playlist Adapter**: `LikedTrackRef.toLocalPlaylistTrackRef()` adapts stored liked tracks directly into `LocalPlaylistTrackEntity` for queue playback resolution via the existing `resolveLocalPlaylistTracks` batching pipeline.

### D303: Library lists saved items instead of playback ledger, separating Library from History

Part of #343 (#380). Supersedes the "Library shows" reading that D214/D215/D220 built on Android, separating saved items from the playback history ledger. Revises D220's "Library bookmark parity" UAT item.

- **Separation of Library and History**: Previously, the Library's "Shows" tab read `progressDao.inProgress()`, displaying the unfinished playback ledger under a Library label. History was already an independent screen (`HistoryScreen`), resulting in confusion where played but unsaved shows appeared in the Library. `LibraryScreen` stops reading `progressDao.inProgress()`. The Shows tab is now sourced from Android's bookmark store (`SavedShows.keys`), displaying saved shows while listening history remains exclusively on Home ("Continue listening") and in History (`HistoryScreen`).
- **LibrarySources Pure Domain Seam**: Introduced `LibrarySources.kt` (`LibraryItem`, `LibraryTarget`) as a pure, UI-independent data model and transformation seam. Show bookmarks from `SavedShows` map to `savedShowItems`, local playlists map to `playlistItems`, and playlist tracks map to `trackItems`. Navigation targets (`Show(date)`, `Recording(RecordingId)`, `LocalPlaylist(id)`) are decoupled from Compose and `NavHostController`, enabling pure unit testing.
- **Deterministic Null-Last Sorting**: Sources without timestamp columns (`SavedShows` keys, `LocalPlaylistTrackEntity`) supply `addedAt = null`. Under `LibrarySortMode.RECENTLY_ADDED`, items with non-null `addedAt` sort first in descending order, followed deterministically by items with null `addedAt` in the order produced by their respective sources. `TITLE_ASC` and `TITLE_DESC` ignore timestamps entirely and sort alphabetically by `sortKey`.
- **Defensive Show Key Parsing & Dropping Unparseable Keys**: `SavedShows` keys are defensively parsed using `parseQueueKey` and `parseRecordingId`. Valid phish.in dates (`show:YYYY-MM-DD` or bare dates) and Relisten recording keys (`relisten:artist/date/source`) parse into clean `YYYY-MM-DD` titles. Non-show queue keys (`playlist:`, `local-playlist:`, `youtube:`) or corrupted entries are dropped from the list rather than rendering raw internal identifiers as titles.
- **Queue Prefix Sanitization in History**: In `HistoryScreen`, row titles now use `historyDisplayTitle` to guard against blank titles or raw internal prefixes (`show:`, `relisten:`, `playlist:`, `local-playlist:`, `youtube:`), falling back to date parsed from `queueKey` or `"Removed show"`, guaranteeing that internal queue keys never render as UI titles.
- **Contextual Actions & Honest Empty States**: The in-progress ledger long-press controls (Resume, Mark completed, Remove from In Progress, Delete from history) and trailing elapsed timestamps are removed from Library show rows, replaced by "Open show" and "Remove from Library" (`SavedShows.toggle`). Each tab displays an honest empty state with action guidance. When the Shows tab is empty and listening history exists (`progressDao.historyCount() > 0`), a direct tap-through link ("Shows you have played are in History") directs users to History.

### D304: Library folds phish.in account content and Relisten likes into saved items, fetching live without mirroring

Part of #343 (#381). Builds on D302 (`LikedTrackRef` display metadata) and D303 (Library saved items separation).

- **Unified Saved Items Surface**: The Library is the single place saved items live, merging local and phish.in-account sources across all three tabs: account playlists (created and liked, deduped by slug per D32) alongside local playlists under Playlists; phish.in liked shows alongside bookmarked shows under Shows; and phish.in liked tracks alongside Relisten liked tracks (#372) and local playlist tracks under Tracks. Tapping an account playlist opens the playlist page; tapping an account show opens the show page; tapping an account track plays the track inside its show (`vm.playTrack`); and tapping a Relisten liked track opens the tape recording (`openQueueKey`).
- **Fetch Live, Mirror Nothing**: phish.in account content is fetched live on demand rather than mirrored into Room. phish.in provides no like timestamps, no like IDs, and no incremental sync feed; a local database mirror would be a static snapshot with synthesized metadata requiring schema migrations and ongoing synchronization logic for zero user benefit. Local storage remains local, and server-side likes remain live.
- **Unauthenticated D26 Guard & Reactive Lifecycle**: Requests to `playlists?filter=mine` or `filter=liked` without authentication silently ignore the filter and return all 2,504 public playlists (D26). `loadLibraryAccount` strictly gates on `Session.username != null`, returning empty lists with zero network requests when signed out. Signed-out Library displays local items with an invitation line to sign in. Signing in or out reactively refetches or clears account content via `Session.username` flow collection without restarting the app. Network errors preserve local items and display a non-intrusive warning line without clearing or navigating away.
- **Deduplication Preserving Navigation Targets**: A show saved both locally and liked on phish.in is deduplicated to a single row by show date, preserving timestamps (`addedAt != null`) and local bookmark keys (`rawKey`). Tracks are preserved with their distinct navigation targets: account tracks navigate to playback inside their show (`AccountTrack`), while local playlist tracks navigate to their containing playlist (`LocalPlaylist`), avoiding destructive deduplication across playlists or account sources. Track deduplication is scoped to identical items via their unique item keys.
- **Deterministic Null-Last Sorting for Account Content**: phish.in likes provide no creation timestamps, producing `addedAt = null`. Under `RECENTLY_ADDED`, items with timestamps lead, while undated account items sort cleanly after dated items in API order rather than being interleaved by arbitrary timestamp guesses. Alphabetical sort modes (`TITLE_ASC`, `TITLE_DESC`) sort the entire merged set uniformly.

### D305: Rule: no mockup data or placeholder chrome in shipping code; Ledger redesign audit recorded (#356)

Audit of the Ledger redesign (#133, D214; commits 2a6b2cc / 28fa02c / c0c0912 + reconcile 68d179a) against pre-redesign baseline `2a6b2cc^` for leftover mockup data and dropped or miswired features. Full report in `docs/LEDGER_REDESIGN_AUDIT.md`; checklist on #356.

- **Rule Established: No Mockup Data in Shipping Code**: No literal sample data, fallback artist lists, placeholder counts, or decorative window chrome may be committed to shipping code. Design mockups, vector references, and prototype payloads belong exclusively in `design/` (e.g. `design/handoff/`). Fallback states in production must be honest (loading skeletons, empty states, or documented offline caches), never synthetic mockup content masquerading as real user data.
- **Findings Filed**: #384 (macOS ⌘F dead + cleared search never closes the route), #383 (macOS transport prev/next lost accessibility labels), #385 (Android fake 0.41f scrubber progress — fixed at HEAD via #390), #386 (Android Now Playing dropped artwork/format/cast-indicator displays — owner decision).
- **Known Issues Re-verified at HEAD**: #347 fixed (macOS sidebar mock favorites replaced with honest empty/loading state in #389), #348 fixed (Artists nav row restored), #349 live (search never renders artist results; confirmed redesign regression), #354 fixed (Next Tour Stop chips focus artist in #387), #355 fixed (jam chart real notes and source link wired on Android in #378 / PR #408 and macOS in #379 / PR #407).
- **Audit Scope & Clean Findings**: After intermediate fix rounds, the only remaining regressions at HEAD are #383, #384, #386, and #349. Decorative window traffic lights in `SidebarView` and `ExpandedNowPlayingView` logged for owner UAT. Compare Sources reachability logged in UAT. Docs-only change with no source code modifications.

### D307: Bound "On This Date" phish.in fetch fan-out and range sizes (#352)

"On This Date" fetches the phish.in catalog using `year_range=`. Very large ranges (up to 900 shows, ~2.7 MB) with unbounded concurrent fetches on a mobile connection were timing out against OkHttp's 30 s `readTimeout` (`Api.kt`). Rather than tuning that shared timeout and losing instrumentation (see D176), we shrunk the request payload and bounded concurrency so the requests reliably clear the timeout:
- **Smaller Batches**: Lowered `PHISHIN_RANGE_CAP` from 900 to 300. This scales the response from ~2.7 MB / 8–20 s down to ~0.9 MB / ~3 s, finishing well within the 30 s read timeout even with multiple requests in flight. Across Phish's ~2,000 shows, this results in ~7 requests instead of ~4, still safely below phish.in's `per_page=1000` limit.
- **Bounded Fan-out**: Bounded concurrent phish.in period fetches using `Semaphore(PHISHIN_CONCURRENCY)` with `PHISHIN_CONCURRENCY = 4`.
- **Narrowed Exception Absorption**: Replaced broad `runCatching` with explicit `catch (e: IOException)` across period and artist fetches, allowing `CancellationException` and unexpected programming errors to propagate naturally rather than silently returning empty lists.
- **Single Retry on Failed Ranges**: Added a single retry per range on `IOException` with a 250 ms delay to recover on transient network drops without complex backoff loops.
- **Completeness Reporting & Cache Integrity**: Introduced `Fetched<T>(value, complete)` to distinguish complete catalog results from partial ones. `OnThisDate.load` only populates the once-a-day cache when `complete` is true and results are non-empty. Partial results are still returned to the UI per D162, but re-fetched on subsequent visits.
- **Untouched API Client**: `Api.kt` and its shared 15 s / 30 s timeouts remain strictly untouched; this fix addresses the request size that made the timeout reachable.

### D309: Search artists and venues get dedicated macOS tabs and omit-when-unknown counts (#349)

Resolves search regressions and count mislabeling across macOS and Android (#349):
- **macOS Search Tabs for Artists and Venues**: Restores artist search results and splits venue slices into their own tab (`SearchTab.artists`, `SearchTab.venues`). Slices (`SliceHit`) were previously grouped under a single Songs tab with `slice.kind.heading` printed in the DATE column, causing venue rows to read "GD / Venues / The Marketplace / 0 shows" across track columns. Venue slices now have a dedicated Venues tab separate from Songs; the slice row layout leaves DATE empty, places the entity label in TITLE / TRACK, and renders known counts in the TIME · LIKES column. The All tab renders artists, tracks, shows, songs, and venues in sequence, with its total count accurately summing all visible rows.
- **Omit-When-Unknown Count Policy**: Relisten's live `/v3/search` returns artists without a `show_count` field (which decodes to 0 via `?? 0`) and returns venues without any count field. Fabricating numbers is prohibited, and rendering "0 shows" violates the precedent established in `ArtistsView.swift:92` and `PlaybackService.kt:560-562`. The count policy is *omit when unknown*:
  - **Artists**: Best-effort join against the in-memory 15-minute-TTL `/v3/artists` cache (`RelistenCatalogSource.cachedArtists`), which macOS primes at startup in `ThreePaneRootView.swift:91`. Non-blocking: no network request is added to the search path. If the cache is unprimed or the artist is missing, the count is omitted.
  - **Venues**: Relisten's search endpoint carries no count; venue counts are omitted until drilled into `/v3/venues/:uuid`. Song slices retain their real `shows_played_at` count.
  - **UI Guards**: Both platforms guard row rendering with `> 0` checks (`searchArtistRow`/`searchSliceRow` on macOS, `MainActivity.kt` on Android), ensuring "0 shows" is never displayed anywhere.
- **Flat SearchHits Model Preserved**: `SearchHits` remains flat (`artists`, `shows`, `slices`, `tracks`) without introducing a new grouping type, preserving cross-backend fan-out and artist filtering. Note: D206 documented a tag picker for `SearchView` (tag chip row with picking a tag emptying artists/slices), which remains unimplemented at HEAD; this work leaves that drift undisturbed for a dedicated follow-up.

### D310: Smoke journeys assert on accessibility identifiers, never on screen regions, and screenshots stay out of the repo (#368)

Establishes the user journey smoke specification (`scripts/smoke/JOURNEYS.md`) and shared runner contract (`scripts/smoke/lib.sh`) for macOS and Android smoke testing:
- **Identifier Queries Over Pixel Comparisons**: Pass conditions are deterministic queries over accessibility identifiers (`macos/CouchTour/AXIdentifiers.swift` on macOS, `app/src/main/java/dev/mike/couchtour/A11yTags.kt` on Android). Pixel comparisons, visual diffing, and region captures are rejected outright as brittle and nondeterministic.
- **Dynamic Input Synthesis Without Hardcoded Coordinates**: On macOS, the runner targets the accessibility tree by window owner and synthesizes input events only after confirming the beta application is the frontmost process. On Android, the runner derives tap coordinates per run dynamically from the layout bounds of nodes located by accessibility tag. Hardcoded pixel coordinates and fixed screen region taps are banned across both platforms.
- **Fixture Independence & Credential Safety**: The runner never types credentials or handles secrets. Journeys requiring user authentication or state (`signed-in`, `seeded-favorite`) evaluate the existing installed app session and record `SKIP` with human-readable evidence rather than failing when the fixture is unavailable.
- **Private Data Protection & Screenshot Artifact Isolation**: Failure screenshots are written locally under `smoke-reports/<tag>/` and gitignored. Only the lightweight markdown report (`smoke-reports/<tag>.md`) and TSV results are committed. In a public repository, user libraries, favorited artists, playlists, and listening history constitute personal data that must never be leaked into version control.

### D311: Baseline feature parity audit and smoke journey parity spec recorded (#292)

Baseline feature parity audit across macOS and Android clients, establishing the recurring parity audit standard for mahler#606. Full report committed in `docs/PARITY_AUDIT.md`; checklist comment on #292.

- **Executable Parity Specification**: Per owner direction, `scripts/smoke/JOURNEYS.md` doubles as the executable parity spec across platforms: journeys tagged `mac`, `android`, or `both` assert core capabilities across clients. Identified that `library-phishin-playlists` exposes macOS Library account gaps (#382), `favorite-syncs-*` exposes lack of favorites backend sync (#351), and future journeys should target search playlists, audio quality settings, and show-level likes.
- **Intentional Platform Differences Catalogued**: Documented deliberate architectural divergences that are not defects: macOS 3-pane layout & menu bar commands vs. Android Ledger bottom bar and gestures; in-app volume slider & mute vs. hardware volume rockers; AirPlay `AVRoutePickerView` integration on macOS; Sparkle auto-updates vs. APK release workflow; QR display (host) vs. camera scan (client); YouTube IFrame embed (D256) vs. Media3 audio stream extraction; and uniform exclusion of offline audio downloads (#65 / #141).
- **Findings Filed**: #427 (macOS search does not return or display phish.in playlists), #428 (macOS browse public phish.in playlists), #429 (macOS PlaybackSettings lacks Audio Quality and Gapless playback preferences), #430 (macOS on-device diagnostics log and viewer), #431 (Android Show Detail header lacks phish.in server-side Show Like button).
- **Existing Parity Issues Re-verified at HEAD**: #351 (favorite artists cross-device sync), #382 (macOS Library shows playback history rather than saved shows, and misses account content), #423 (Android search field testTag), #386 (Android Now Playing dropped artwork and indicators), #362 (macOS On This Date range timeout and cache bug).

### D312: CI verifies macOS app target compilation on pull requests (#402)

Broadens PR CI coverage to verify full macOS application target compilation alongside existing package unit tests:
- **Coverage Gap Closed**: Prior CI gates ran CouchTourKit unit tests only (`swift test`), allowing compilation failures in the macOS app layer (e.g. view layer typing, AVFoundation integration, Swift version mismatches) to pass CI unnoticed and escape to release cuts (#345–#356).
- **PR Trigger Broadened**: Updated `.github/workflows/macos-tests.yml` to trigger on changes matching `macos/**` rather than just `macos/Packages/CouchTourKit/**`.
- **Concurrent Build Job**: Added `macos-build` job running on `macos-14` concurrently with `macos-test`. The job checks out the repository, installs XcodeGen via `brew install xcodegen`, selects Xcode 16.2 to match XcodeGen's project file format, generates the Xcode project via `xcodegen generate`, and compiles the Debug scheme unsigned via `xcodebuild -project CouchTour.xcodeproj -scheme CouchTour -configuration Debug -destination 'platform=macOS' build CODE_SIGNING_ALLOWED=NO`.
- **Local Runtime Overhead Benchmark**: Clean project generation and build runs in ~29s on local hardware (incremental compilation ~14s; initial build with uncached SPM resolution ~55s). Recommended for CI; owner decision whether to incorporate into Mahler local verify.

### D313: Android Now Playing restores artwork, audio format badge, and cast indicator within Ledger (#386)
Owner chose "Restore artwork & indicators" over keeping the artwork-free Ledger layout. `NowPlaying.kt` now renders a 180dp rounded `ShowArtwork` tile (procedural cassette fallback) over the hero gradient (dark) / plain background (light), shows an FLAC (amber) or MP3 (outline/muted) badge in the TAPE row from `state.audioFormat`, and shows "Casting to <device>" in the top bar from `Casting.deviceName`. The badge appears only when the item has a format: `playerAudioFormat` returns empty for a YouTube video (#234), which carries no `FLAC_URL` and streams YouTube's own codecs, so labelling it "MP3" asserted a format it never had. This supersedes the artwork/indicator omissions in D214/D215; the rest of the Ledger layout stands. Android now matches macOS (`ConicGlowArtwork`) and Google TV (`ShowArtwork`).

### D314: Isolated staging sync backend for smoke testing and beta default

Fixes #359. Points Android debug/beta and macOS `CouchTourBeta` at the staging sync Worker and D1 database by default to allow automated and human smoke testing without polluting production sync history, with explicit overrides back to prod.

- **Staging Worker as Test Group**: The sync protocol does not support client-supplied group IDs; the Worker derives `groupId` exclusively from `Authorization: Bearer <token>` (`sync/src/auth.ts`). Without server admin/wipe endpoints, group isolation requires pointing clients at an isolated backend instance. The existing staging deployment (`https://couch-tour-sync-staging.mkastellec.workers.dev`, backed by its own D1 database `couch-tour-sync-staging` in `sync/wrangler.toml`) provides an isolated environment without requiring backend code changes (`sync/` remains untouched).
- **Beta Defaults to Staging**: Android debug builds (including `-PsideInstall=true` beta builds) configure `BuildConfig.SYNC_BASE_URL` to the staging URL, while release builds point to production. On macOS, `CouchTourBeta` resolves staging under `#if BETA`. Production clients (Android `release` and macOS `CouchTour`) continue defaulting to the production host.
- **Configured Host Overrides**: Clients support overriding the base URL in either direction without rebuilding:
  - macOS: `--sync-base-url=<url>` launch argument takes highest priority, followed by `COUCHTOUR_SYNC_BASE_URL` environment variable, falling back to default. Resolution logic lives in `CouchTourKit` (`SyncConfig.resolveBaseURL`).
  - Android: `syncBaseUrl` intent extra (`MainActivity.kt`) overrides the base URL on launch or `onNewIntent`.
  - Malformed or blank override URLs are ignored and fall back safely to configured defaults.
- **Clear Token on Host Change**: To prevent cross-environment token replay (e.g. presenting a production bearer token to staging, or vice versa), clients record the issuing host alongside the token (`SyncTokenStore.tokenHost`). When the active base URL host differs from the token's issuing host, the local token store is cleared and the client starts unpaired against the new host. Re-applying the same host preserves pairing state.
- **Guarded Reset Script**: `scripts/smoke-sync-reset.sh` empties staging D1 tables (`progress`, `seqs`, `pairings`, `devices`, `groups`) via `wrangler d1 execute couch-tour-sync-staging --remote`. It hard-guards against running on any database other than `couch-tour-sync-staging`, specifically checking and rejecting production database names and IDs, requires an explicit `--yes` flag to write remotely, and defaults to dry-run printing. Covered by `scripts/test_smoke_sync_reset.sh`.


### D315: macOS on-device diagnostics log in CouchTourKit (storage, rotation, retention, redaction)

Part of #430 (#433). macOS counterpart of Android D292; same file format and policies so exported logs read identically.

- **Storage**: `~/Library/Application Support/dev.mike.couchtour/diagnostics/` beside `phishin.db` (D171); `DiagnosticsLog(directory:)` takes an override for tests. `diagnostics.log` plus one previous generation `diagnostics.log.1`.
- **Rotation**: 1 MiB cap per file, 2 MiB total. On append, if the current file is at or over the cap, `.1` is deleted, current is renamed to `.1`, and a fresh file starts with a `log.rotated` entry.
- **Retention**: On `init`, both generations drop lines older than 7 days (unparseable lines are kept); a file still at or over 1 MiB is cut to its last 2000 lines.
- **Line format**: `<ISO-8601 UTC>\t<LEVEL>\t<event>\t<key>=<value> ...`; newlines and tabs in values become spaces so one entry is one line.
- **Redaction**: key names whose camelCase / delimiter-split words match `token`, `secret`, `password`, `passwd`, `auth`, `credential`, `cookie`, `pairing`, `code`, `key` (plural, or prefix/suffix for keywords of 4+ letters) get `***`. Bare `key` and `code` are exempt (e.g. HTTP `code=200`), but `syncKey`, `api_key`, `pairingCode` are redacted.
- **Concurrency / failure**: a serial `DispatchQueue` owns all state; `log` and `mark` are async and non-throwing. Any write failure latches logging off for the process rather than propagating. Inspection APIs (`tailLines`, `summaryLines`, `exportText`, `clear`, `onDiskBytes`) are synchronous and meant to be called off the main thread by the viewer.
- **Tail**: `tailLines(n)` seeks from the end and reads at most 256 KiB across both generations, dropping a leading partial line.
- Viewer, Feedback integration and call-site instrumentation are separate sub-issues.

### D316: macOS Settings diagnostics viewer (Copy / Clear)

Part of #430 (#435). Counterpart of Android #376 / D293.

- **Entry point**: a "Diagnostics…" button in the About section of Settings → Playback (`PlaybackSettingsView`) presents `DiagnosticsView` as a sheet.
- **Content**: summary header (`summaryLines()`: marks, entry count, on-disk size) above a monospace, selectable view of `tailLines(200)`. No auto-refresh or filtering; it loads once per open and again after Clear.
- **Copy** puts `exportText()` (both generations, full) on `NSPasteboard.general`. **Clear** asks for confirmation, then calls `clear()` (both files and marks) and reloads.
- **Threading**: the log's inspection APIs block on its serial queue and hit disk, so every call runs in `Task.detached`; the main actor only assigns results.
- The view owns its own `DiagnosticsLog()` on the default directory; the log's queue serializes it against any other instance only per-instance, which is acceptable until app-wide instrumentation (separate sub-issue) introduces a shared one.

## D317: Diagnostics instrumentation event vocabulary (macOS)

Status: accepted. Mirrors Android D294. Builds on D315/D316 (#433, #435); #434.

- Subsystems record through `Diagnostics` (CouchTourKit), a process-wide holder for one `DiagnosticsLog`. `log` is nil until the app calls `Diagnostics.installDefault()` at launch, so tests never write to the real log. The Settings viewer reads the same instance so marks are visible there.
- `api.call` (PhishInAPI, RelistenAPI, SyncAPI): `path=<percent-encoded path>`, `phase=start|end|failed`, `ms`, `status` on end, error type name on failed. Path only: query strings and headers are never logged. (A phish.in search term is part of the path by API design.)
- `sync.start`, `sync.end` (`pulled`, `pushed`, `ms`), `sync.error` (`code`, `ms`). Codes are short classifications (`unauthorized`, `network`, `server`, `gone`, `not_found`, `rate_limited`, `decode`, `http_N`, `unknown`), never the exception text. Sets the `Last sync` mark. Cancellation is not logged.
- `playback.start`, `playback.stop` (`posMs`), `playback.error` (`domain`, `errno` from `AVPlayerItemFailedToPlayToEndTime`), each with `show` and `track`. Sets the `Last playback` mark. Emitted from `Player`'s rate and failure observers; no per-second position logging.
- Field names avoid the redaction keywords (`code`-compound keys such as `errorCode` would print `***`), hence `errno`.

## D318 — macOS audio quality and gapless preferences (#429)

`PlaybackSettings` gains `audioQuality` (`lossless`/`compressed`, key `audio_quality`, default lossless) and `gapless` (key `gapless`, default on), matching Android's stored values (D228). `AudioQuality.resolveURL` picks FLAC vs MP3 and always falls back to the other format so a tape never becomes unplayable; the FLAC badge follows what actually plays. With gapless off, `Player` queues only the current item and inserts the next one when the queue drains (`currentItemDidChange`), so nothing is preloaded. Both settings apply from the next queue start, not mid-track.

## D319 — Recorded contract fixtures (#441)

Part of #405. The hand-shaped fixtures let upstream drift pass CI and break on devices (#353, #388), so `scripts/contracts/record.sh` captures real responses from the endpoints the clients actually call (11 requests: phish.in years/shows-by-year/show/search/playlists; Relisten artists/years/year/show/on-date/search). It sends a `CouchTour-contract-recorder` User-Agent, runs sequentially with a ~1 s pause, and resolves the Relisten Phish and 1997 uuids from the live responses instead of hard-coding them.

- **Naming**: `contract_*.json`, written byte-identically into both the Android and CouchTourKit fixture dirs, so `macos/scripts/check-fixtures.sh` (D35) keeps passing.
- **Trimming**: every JSON array is cut to its first 3 elements, recursively. Values and shape stay real; output is pretty-printed with sorted keys so re-records diff cleanly.
- **Re-record**: `scripts/contracts/record.sh` from the repo root (`--out-dir DIR` writes elsewhere, for a scheduled drift check, #443), then run the Android and CouchTourKit tests. A decode failure on a fresh recording is a real DTO bug, not a fixture problem.
- **Android**: `ContractFixturesTest` decodes each file with the production DTO and `Json` config, runs the phish.in and Relisten show through the real API clients over `MockWebServer`, and checks that the bundled curated/heuristic match assets load. `ShowsPage` and `PlaylistsPage` became `internal` for this. CouchTourKit decode tests are #442.
- The curated-match JSON is a bundled asset, not fetched, so it is tested where it ships rather than recorded.

## D320 — Weekly contract drift check (#443)

Part of #405. `.github/workflows/contract-check.yml` runs Mondays (and on dispatch, never on PRs): `scripts/contracts/record.py --skip-failed` re-records the live phish.in/Relisten responses to a temp dir, then `scripts/contracts/shape_check.py` compares their shape (keys and JSON types, never values) with the committed `contract_*.json` fixtures.

- **Why shape, not decode**: the DTOs use `ignoreUnknownKeys`, so a decode test can't see an added upstream key. The diff names each endpoint and path (`+` added, `-` removed, `~` retyped). Null and array length/emptiness are ignored: they are sample values, not shape.
- **Outages are not drift**: with `--skip-failed` a failed request (timeout, HTTP error) skips that endpoint, logged in the job; only drift files an issue.
- **Issue filing**: one open "Contract drift: upstream API shape changed" issue (`mahler`, `type:bug`); if open, the report is added as a comment. The job stays green on drift; the issue is the signal. Fixes land as a normal re-record plus DTO change.
- Unit tests: `python3 -m unittest scripts/contracts/test_shape_check.py`.

## D321 — CouchTourKit contract decode tests (#442)

Part of #405. The other half of D319: the `contract_*.json` recordings are decoded by
`ContractFixturesTests` here as well as by `ContractFixturesTest` on Android, so drift that
only breaks the Swift DTOs fails CI rather than a user's Mac. The endpoint list mirrors the
Kotlin one file-for-file, or the two clients would silently test different recordings again.

- **Through the real clients**: the phish.in and Relisten shows are served by `MockServer`
  and fetched via `PhishInAPI.show`/`RelistenAPI.show`, so the request path is exercised too,
  not just the decode.
- **`ShowsPage` is internal**: it was `private`, which `@testable` can't reach. Android did the
  same for `ShowsPage`/`PlaylistsPage` in #461.
- **Playlists has no Swift DTO and gets no new one**: the desktop MVP has no playlists screen
  (D5), so there is no model here to drift. `contract_phishin_playlists.json` is parsed
  generically to keep it loadable; D320's weekly shape check is what guards that endpoint.
- The curated/heuristic match tests read through `CuratedMatches.shared`/`HeuristicMatches.shared`
  rather than by file path, so they cover the resource actually shipping in the bundle.
- Tests: `cd macos/Packages/CouchTourKit && swift test` (515).

## D322 — Escaped-bug loop: Escape cause + Check that now catches it (#446)

Bugs that reached beta/prod were fixed without asking what check would have caught them, so
the same class recurred (#345–#356). The bug issue template now ends with *Escape cause* and
*Check that now catches it* sections, and `CLAUDE.md` gains an `## Escaped bugs` rule: a bug
fix isn't done until the second section names a check that exists in the repo (test, CI step,
lint, or smoke journey) or says why none is feasible.

- **Guidance, not a gate**: nothing enforces the sections mechanically yet. That generalization
  belongs to mkny13/mahler#611 (gates, not guidance).
- Part of #403.

### D323: Gate Android syncBaseUrl intent extra on BuildConfig.DEBUG

Fixes #480. `MainActivity` is an exported launcher Activity: any installed app can send it an
intent with the `syncBaseUrl` extra, and the previous code persisted that override as the sync
base URL in every build type — including release. This let a malicious app silently redirect
all future sync traffic (pairing, progress, token rotation) to a host it controls.

- **Release boundary**: the `syncBaseUrl` intent extra is now applied only when `BuildConfig.DEBUG`
  is true — i.e. debug builds and `-PsideInstall=true` beta builds, neither of which is an
  exported production target. Release builds ignore the extra entirely; the `BuildConfig.SYNC_BASE_URL`
  compiled into the release variant (production host) is used unchanged and no override is persisted.
- **Shared policy**: both `onCreate` and `onNewIntent` now route through
  `SyncApi.maybeApplyBaseUrlOverride(context, override, debug = BuildConfig.DEBUG)`, so the two
  lifecycle entry points cannot diverge. The existing URL validation (malformed/blank URLs fall
  back to the configured default) and the token-host change protection (D314) remain intact and
  apply only when an override is actually accepted.
- **macOS unaffected**: the `--sync-base-url` launch argument and `COUCHTOUR_SYNC_BASE_URL` env var
  continue to work on all macOS builds; the macOS override resolution lives in
  `CouchTourKit`'s `SyncConfig.resolveBaseURL` and is out of scope here.
- **Tests**: `SyncTest.kt` adds `SyncBaseUrlOverrideTest` covering a rejected release override
  (`debug = false`), an accepted debug override (`debug = true`), a blank/null override in debug,
  the `BuildConfig.DEBUG` default on test builds, and confirmation that D314's token-host
  clearing still fires through the gated path. `scripts/test_sync_base_url_intent_guard.sh`
  statically guards against an exported component forwarding `EXTRA_SYNC_BASE_URL` to
  `applyConfiguredBaseUrl` without the debug gate; wired into `.github/workflows/test.yml`.

Supersedes the Android portion of D314 ("`syncBaseUrl` intent extra overrides the base URL on
launch or `onNewIntent`") while preserving D314's staging defaults, token-host protection, and
macOS override behavior.

## D324: Pin undici and sharp via npm `overrides` in `sync/` (#483)

`@cloudflare/vitest-pool-workers@0.22.0` (latest) nests a miniflare/wrangler that pulls
undici 7.29.0 and sharp 0.35.2, giving 5 high dev-only advisories in `npm audit` (production
bundle unaffected; `npm audit --omit=dev` is 0). No upstream fix exists, and the suggested
downgrade to 0.8.30 is unacceptable. `sync/package.json` now overrides `undici` to `^7.29.1`
and `sharp` to `^0.35.4`; audit is clean and all 18 tests plus typecheck pass.
Remove the overrides once vitest-pool-workers ships a release whose nested miniflare no longer
needs them (re-check at the next security audit); if they ever break the test pool, revert and
record the advisories as accepted instead.

## D325 — macOS sender enforces public playlist excerpts while casting (#428)

D66 recorded that a receiver cannot apply Media3's `ClippingConfiguration`; the macOS sender now handles public playlist excerpts without a custom receiver. Cast `LOAD` and `SEEK` use file time (`clipStartMs` plus the entry-relative position). The sender converts receiver status back to entry-relative progress and polls status every 500 ms while an excerpt plays. At `clipEndMs` it pauses the receiver and advances the playlist. Polling stops when the entry finishes, changes, or Cast disconnects. The Cast media payload omits the excerpt's duration because it is not the MP3's full duration. This supersedes D66 for macOS; Android's Cast behavior remains as recorded there. Local AVPlayerItems seek to their excerpt start only after reaching `readyToPlay`, and playback waits for that seek, including normal track taps and resume. A sender-driven Cast boundary depends on the Mac remaining connected and may be late by a status round trip, so physical playback remains a UAT check.

## D326 — macOS search returns public playlists (#427)

Supersedes the D5 omission of a `playlists` bucket in `SearchHits`: now that #428 gave public playlists a screen, `SearchResults` decodes `playlists` into `PublicPlaylistSummary` and `SearchHits.playlists` carries them. They count as Phish-only (dropped when filtering to another artist, like tracks), appear in the All tab and a dedicated Playlists tab, and a tap opens `PublicPlaylistView`.

## D327 — A smoke run's committed evidence is its report; screenshots stay local (#371)

`scripts/smoke/run-smoke.sh` writes `smoke-reports/<tag>.md` and that file is what gets
committed and what #358's promotion gate reads (`Tag:`, one `Smoke: PASS|FAIL`, optional
`Waived:` lines). Failure screenshots (`smoke-reports/**/*.png`) and the per-platform result
files are gitignored: this repo is public, and the screenshots show the owner's signed-in
library, favorites, and listening history. `--commit-screenshots` exists as the owner's
opt-out; flipping the default is a one-line change once they've seen a report.
A platform that could not be run at all (runner exit 2, or a crash) can never produce a `PASS`;
the report names it. `SKIP` is neither pass nor fail: it is listed as "not verified" and does
not block a `PASS`. Whether `SKIP` should block promotion is a policy call left to the owner.
The formatter passes runner evidence through verbatim, so the runners are what must keep
evidence identifier-level. A run with zero results is a `FAIL`.

## D329 — Shipped issues are verified only by a beta-tag evidence comment (#404)

A merged issue stays `mahler:verifying` until a comment carrying `<!-- mahler:verified -->`, a beta
tag and evidence (smoke-report line, screenshot/dump, or owner UAT note) is posted;
`scripts/smoke/verify-comment.sh` writes that format. Each smoke report cross-references closed
`mahler:verifying` issues without the marker against the run's journeys (mapped via `#N` in
`JOURNEYS.md` or the journey id in the issue text): `covered`, `not covered`, or `still reproduces`.
Posting comments for `covered` issues is opt-in (`run-smoke.sh --auto-verify`) so a weekly run
does not write to issues unattended until the owner has seen the report table. Kept
couch-tour-specific and compatible with the Mahler-wide gate in mkny13/mahler#611 part 3.

## D330 — macOS Feedback includes diagnostics behind a Settings toggle, default on (#436)

Mirrors Android D296. With `PlaybackSettings.includeDiagnostics` on (default `true`), the Feedback
button copies `DiagnosticsLog.tailLines(200)` to the clipboard and adds `summaryLines()` to the issue
body under `## Diagnostics`; off leaves the clipboard and URL exactly as before. The URL carries only
the short summary (capped at 1000 chars, then shrunk until the encoded URL is under 2400), never log
lines — those travel by clipboard because GitHub pre-fill URLs can't hold them. The log is already
redacted at write time (D315).

## D331 — Two-client sync round trips are a separate orchestrator, not per-platform journeys (#406)

A sync bug like #351 only shows with two real clients, so `scripts/smoke/sync-roundtrip.sh` drives both runners through their new `--sync-step <action|assertion>` mode: an action on one client, then a polled assertion on the other (default 30s), in four directions (favorites and In Progress, each android→mac and mac→android). Results are reported under platform `sync`, one line per direction; the old per-platform SKIP stubs for `favorite-syncs-*` / `in-progress-syncs-android-to-mac` are gone and `check-journeys.sh` ignores `sync` journeys. A timeout is a `FAIL` on the asserting direction, a control or fixture that doesn't exist (exit 4) is a `SKIP`, and a direction that needs state from a failed one is a `SKIP`, so `favorite-syncs-*` reports #351 honestly instead of passing vacuously. Teardown runs `scripts/smoke-sync-reset.sh --yes` from an EXIT trap so the staging group is left as found; the reset also drops pairings, so the owner re-pairs the betas afterwards (pairing stays manual). `run-smoke.sh` runs it by default with `--platform both` (`--no-sync` opts out). Favorite-toggle and progress-seed/clear controls have no accessibility identifiers yet (no app code changed here), so those actions are env-configurable and SKIP until the identifiers land; the assertions use the existing row identifiers. Headless test: `scripts/smoke/test_sync_roundtrip.sh`.

## D332 — macOS Library lists saved local items; playback stays in History (#539)

Mirrors Android's D302/D303 on macOS. `LocalPlaylistsView` stopped reading `ProgressStore.inProgress()`, so a played-but-unsaved show shows only in `ListeningView`. Library sources are local playlists, their tracks, and locally liked Relisten tracks, mapped and sorted by the pure `LibrarySources` helper in `CouchTourKit` (undated rows sort last, ties keep source order). `LikedTracks` keeps its flat id set (legacy likes survive upgrade) and adds `LikedTrackRecord` display metadata persisted when a track is liked via `TrackLikeButton`; an id with no usable metadata is liked but omitted from Library rather than rendered as a raw uuid. Liked tracks play by resolving the record through `resolveLocalPlaylistTracks`. No show bookmark store is restored (D229 stands); the Shows tab is an honest empty state, with a link to the existing History screen when playback history exists. Account-liked shows/tracks are the dependent child's scope.

## D333 — macOS Library folds in live phish.in account content (#540)

Follows Android's D304 on macOS. When `PhishInSession` is signed in, `LibraryAccountData.load` fetches created and liked playlists (`filter=mine`/`liked`, deduplicated by slug), liked shows, and liked tracks, and `LibrarySources.items(... account:)` merges them into the Playlists, Shows, and Tracks categories. Live fetch, no GRDB/UserDefaults mirror, and token-gated: the `PhishInAPI` account calls throw before any request without a token, and the loader makes zero requests when signed out (D26: `filter=mine` otherwise returns every public playlist). phish.in gives no like-added time, so account rows have a nil `addedAt` and sort after dated local rows under Recently added. A failed account load returns an empty-but-`failed` result so local items stay and the Library shows a muted warning line rather than looking like an empty account. `LocalPlaylistsView` reloads on `session.username` changes, so signing in/out refreshes or clears only account rows. Rows route to the existing public-playlist and show destinations; a liked track plays directly from its mp3 URL as a one-track queue.

### D334: macOS "On This Date" phish.in range cap 900 → 300 (#362)

macOS `OnThisDate.swift` carried the same `phishInRangeCap = 900` batching as Android, producing ~2.7 MB `year_range=` responses that risk timeouts, which look like years with no shows. Lowered the cap to 300 (~0.9 MB), matching Android's `PHISHIN_RANGE_CAP` in D307 (#352). No concurrency bound was added because macOS fetches periods sequentially. The "no daily cache on a partial load" rule was already in place via #467 (`OnThisDate.load` caches only when `complete`), so it is unchanged. `PhishInAPI.swift` and timeouts are untouched. Guard: `testPhishInRangesDefaultCapKeepsBatchesUnder300AndCoversEveryYear`.

### D336: Require the `test` check on `main`; run it on every PR (#561)

`main` had no branch protection, so nothing mechanically required the PR test check (Mahler practices audit, mahler#780). `test.yml` had a `paths:` filter, which would make a required check hang forever on docs-only and macos-only PRs, so the filter is removed and the Android suite now runs on every PR (cost: ~1-2 min of CI on PRs that can't affect it). `scripts/apply-branch-protection.sh` requires status context `test` with strict=false, no required reviews, no admin enforcement, and ensures squash merge is on; strict=false and no reviews keep it compatible with the conductor merging on green (D18). It is applied by hand once, after the PR merges, so the setting can't gate its own PR.
