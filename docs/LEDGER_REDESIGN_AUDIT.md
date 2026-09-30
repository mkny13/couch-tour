# Ledger Redesign Audit (#133 / D214)

**Date:** 2026-09-29 · **Trigger:** #356 · **Branch:** `mahler/356-audit-ledger-redesign-133-d214-for-lefto`

Audit of the Ledger redesign implementation (commits `2a6b2cc`, `28fa02c`, `c0c0912`, plus the
visual-fidelity reconcile `68d179a`) for leftover design-mockup data and dropped/miswired features.
Deliverables: this document, new issues #383-#386, evidence comments on #349/#355, checklist on #356,
D282 in `DECISIONS.md`, uat-071 in `UAT.md`.

## Method

- **Redesign surface:** every file touched by `2a6b2cc`, `28fa02c`, `c0c0912`.
- **Behavioral baseline:** `2a6b2cc^` (pre-redesign). The macOS `RootView.swift`, `MiniPlayerView.swift`
  and `NowPlayingInspector.swift` deleted by the redesign were diffed feature-by-feature against
  their redesigned replacements.
- **Intermediate fix rounds honored:** ~50 commits landed between the redesign and HEAD
  (e.g. e6a2faa, 68d179a, 25110cc, c0c0912, a1dde99, 8879bff, 26c896a, 3736f2c, 6ba7d18) fixing
  early redesign defects (#139-#149). Every known issue was therefore re-verified **at HEAD**,
  not at the redesign commits, so stale findings are not re-reported.
- **Sweeps:** mockup data, lost features, miswired actions, accessibility, cosmetic duplication —
  on both Android (`app/src/main/java/dev/mike/couchtour/`) and macOS (`macos/CouchTour/`).

## New issues filed

| Issue | Platform | Finding |
|---|---|---|
| [#384](https://github.com/mkny13/couch-tour/issues/384) | macOS | ⌘F dead (nothing consumes `appModel.focusSearchField` since `RootView` was deleted); cleared query never pops the `.search` route (pre-redesign `syncSearchRoute()` did) |
| [#383](https://github.com/mkny13/couch-tour/issues/383) | macOS | Transport Previous/Next buttons lost their accessibility labels in `PlayerRailView` and `ExpandedNowPlayingView` (pre-redesign `MiniPlayerView` had them) |
| [#385](https://github.com/mkny13/couch-tour/issues/385) | Android | Fake `0.41f` scrubber progress when duration is unknown (`NowPlaying.kt:452-456`, mockup leftover from `68d179a`) |
| [#386](https://github.com/mkny13/couch-tour/issues/386) | Android | Now Playing dropped artwork, audio-format display, and the "Casting to …" header indicator; hardcoded decorative gradient bypasses ledger tokens (owner decision) |

## Existing issues re-verified at HEAD

| Issue | Status at HEAD | Evidence |
|---|---|---|
| #347 sidebar mockup favorites | **open, still live** | `SidebarView.swift:16` `fallbackFavoriteArtists` (Goose/WSP/…) renders when favorites are empty |
| #348 sidebar restored / Artists nav row | **closed/fixed** | `SidebarView` rows + ⌘2 verified |
| #349 search never shows artist results | **open, still live** | HEAD `SearchView.swift` renders only shows/slices/tracks; `hits.artists` feeds only the filter menu (line 148). Pre-redesign **did** render artist rows (`2a6b2cc^ SearchView.swift:148-152`), so this is a redesign regression. Evidence comment posted |
| #354 Next Tour Stop artist chips open tour picker | **open, unchanged** | `HomeView.kt:1968` routes chips to `tourPickerRequested` |
| #355 Jam chart placeholder | **open, Android side unchanged** | `NowPlaying.kt:396` still gates the JAM CHART badge on `backend == PHISHIN || null` and `NowPlaying.kt:440-447` passes the hardcoded note "Notable version from phish.in archive records." macOS side verified correct (tag-driven, `PlayerRailView`). Split issues #378/#379 remain the fix path. Evidence comment posted |

## Checked and clean

- **macOS HomeView fake shows:** deleted by `26c896a` (#144); shelves at HEAD are data-driven (`inProgressShelf`, `onThisDateShelf`). No mock literals remain.
- **macOS WaveformScrubber fallback envelope:** the 95-point array is now a documented offline fallback behind a real `WaveformLoader` fetch (`dynamicEnvelope ?? Self.fallbackEnvelope`, `6ba7d18`, D224/#158). Acceptable, not mockup data.
- **Android LedgerBottomBar:** Home/Search/Library/Settings all wired with correct route predicates and text+icon labels (a11y OK).
- **Android Library queue rows:** "Open" / resume wired to `openQueueKey` (`LibraryScreen.kt:352,373`).
- **Android Next Tour Stop card:** tour picker wired via `tourPickerRequested` (the chip mis-target is #354).
- **Android transport & like buttons:** all carry `contentDescription`; like/add/prev/next/play are wired to the player.
- **Android Home playback/sync sections, SettingsScreen rows:** wired; icon-only elements are either decorative with text siblings or labeled.
- **macOS rail/expanded transport:** like, add-to-playlist, cast, mute, volume slider all labeled and wired; TAPE source picker and Compare button wired to `enterCompareSourcesMode` (reachability added to UAT as uat-071).
- **Space playback hotkey, ⌘1-4 jumps, ⌘[ back:** present (`CouchTourApp.swift`, `SpacePlaybackHotkey.swift`, `BackButton.swift`).
- **TrafficLights:** still the fake chrome view tracked by #347 (listed above, not double-filed).

## Minor findings (not filed separately)

- **Android:** inert like-button fallback — when no track id exists, `NowPlaying.kt:611` renders `IconButton(onClick = {})`, a dead heart button. Low impact; fold into #386's cleanup or the next Now Playing pass.
- **Android:** audio format string (`state.audioFormat`) no longer rendered anywhere on screen (notification only) — folded into #386.
- **Android Now Playing JAM CHART / TAPE chips:** decorative arrow icons use `contentDescription = null` with adjacent text — acceptable, noted for completeness.

## Push gate

Both suites green on this branch before push: Android `testDebugUnitTest` (BUILD SUCCESSFUL) and
macOS `swift test --package-path macos/Packages/CouchTourKit` (455 tests, 0 failures).
