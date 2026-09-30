# Ledger Redesign Audit (#133 / D214)

**Date:** 2026-09-30 · **Trigger:** #356 · **Branch:** `mahler/356-audit-ledger-redesign-133-d214-for-lefto`

Audit of the Ledger redesign implementation (commits `2a6b2cc`, `28fa02c`, `c0c0912`, plus the
visual-fidelity reconcile `68d179a`) for leftover design-mockup data and dropped/miswired features.
Deliverables: this document, new issues #383-#386, evidence comments on #349/#355, checklist on #356,
D305 in `DECISIONS.md`, uat-079..082 in `UAT.md`.

## Method

- **Redesign surface:** every file touched by `2a6b2cc`, `28fa02c`, `c0c0912` (Android: `MainActivity.kt`,
  `NowPlaying.kt`, `DesignComponents.kt`, `LibraryScreen.kt`, `SettingsScreen.kt`, `Theme.kt`; macOS:
  `SidebarView.swift`, `ThreePaneRootView.swift`, `PlayerRailView.swift`, `ExpandedNowPlayingView.swift`,
  `WaveformScrubber.swift`, `DesignSystem/LedgerDesign.swift`).
- **Behavioral baseline:** `2a6b2cc^` (pre-redesign). The macOS `RootView.swift`, `MiniPlayerView.swift`
  and `NowPlayingInspector.swift` deleted by the redesign were diffed feature-by-feature against
  their redesigned replacements.
- **Intermediate fix rounds honored:** commits landed between the redesign and HEAD
  (e.g. `e6a2faa`, `68d179a`, `25110cc`, `c0c0912`, `a1dde99`, `8879bff`, `26c896a`, `3736f2c`, `6ba7d18`,
  `83d0dad`, `814d485`, `40798bb`) fixing early redesign defects (#139-#149, #347, #354, #378, #379, #385).
  Every known issue was therefore re-verified **at HEAD**, not at the redesign commits, so stale findings
  are not re-reported.
- **Sweeps:** mockup data, lost features, miswired actions, accessibility, cosmetic duplication —
  on both Android (`app/src/main/java/dev/mike/couchtour/`) and macOS (`macos/CouchTour/`).

## New issues filed

| Issue | Platform | Finding |
|---|---|---|
| [#384](https://github.com/mkny13/couch-tour/issues/384) | macOS | ⌘F dead (nothing consumes `appModel.focusSearchField` in `macos/CouchTour/AppModel.swift:58` since `RootView` was deleted; set in `macos/CouchTour/CouchTourApp.swift:56`); cleared query never pops the `.search` route (pre-redesign `syncSearchRoute()` did) |
| [#383](https://github.com/mkny13/couch-tour/issues/383) | macOS | Transport Previous/Next buttons lost their accessibility labels in `PlayerRailView.swift:341,368` and `ExpandedNowPlayingView.swift:382,407` (pre-redesign `MiniPlayerView` had them) |
| [#385](https://github.com/mkny13/couch-tour/issues/385) | Android | Fake `0.41f` scrubber progress when duration is unknown (`NowPlaying.kt:452-456`, mockup leftover from `68d179a`; fixed at HEAD in PR #390, verified absent from code and guarded in `LedgerLayoutTest.kt:31`) |
| [#386](https://github.com/mkny13/couch-tour/issues/386) | Android | Now Playing dropped artwork (`CassetteArtwork` / album art), audio-format display (`state.audioFormat`), and the "Casting to …" header indicator; hardcoded decorative gradient bypasses ledger tokens (owner decision) |

## Existing issues re-verified at HEAD

| Issue | Status at HEAD | Evidence |
|---|---|---|
| #347 sidebar mockup favorites | **closed / fixed at HEAD** | Fixed in PR #389 (`83d0dad`). `fallbackFavoriteArtists` completely deleted from `SidebarView.swift`. Empty state displays "Star artists to pin them here" and show counts format via `formatShowCount` suppressing 0 counts. |
| #348 sidebar restored / Artists nav row | **closed / fixed** | `SidebarView` rows + ⌘2 verified. |
| #349 search never shows artist results | **open, still live** | HEAD `SearchView.swift` renders only shows/tracks/songs (`SearchTab` enum lines 19-24, 239-258); `hits.artists` feeds only the filter menu (line 150). Pre-redesign **did** render artist rows (`2a6b2cc^ SearchView.swift:148-152`), so this is a redesign regression. Evidence comment posted. |
| #354 Next Tour Stop artist chips open tour picker | **closed / fixed at HEAD** | Fixed in PR #387 (`814d485`). Chip click now focuses the artist on Home (`MainActivity.kt:495`); tour picker moved to header action (`MainActivity.kt:360-362`). |
| #355 Jam chart placeholder | **closed / fixed at HEAD** | Fixed via sub-issues #378 / PR #408 (Android: `Tag.notes` plumbing, badge gated on `jamChartNotes`, real notes rendered in `JamChartNoteCard`, phish.in source link) and #379 / PR #407 (macOS: `Track.slug` plumbing, real notes in `JamChartNoteCard`, "View on phish.in" link). Hardcoded note literal "Notable version from phish.in archive records." is completely gone. |

## Checked and clean

- **macOS HomeView fake shows:** deleted by `26c896a` (#144); shelves at HEAD are data-driven (`inProgressShelf`, `onThisDateShelf`). No mock literals remain.
- **macOS WaveformScrubber fallback envelope:** the 95-point array is now a documented offline fallback behind a real `WaveformLoader` fetch (`dynamicEnvelope ?? Self.fallbackEnvelope`, `6ba7d18`, D224/#158). Acceptable, not mockup data.
- **Android LedgerBottomBar:** Home/Search/Library/Settings all wired with correct route predicates and text+icon labels (a11y OK).
- **Android Library queue rows:** "Open" / resume wired to `openQueueKey` (`LibraryScreen.kt`).
- **Android Next Tour Stop card:** tour picker wired via header action; chip focus working per #354.
- **Android transport & like buttons:** all carry `contentDescription`; like/add/prev/next/play are wired to the player.
- **Android Home playback/sync sections, SettingsScreen rows:** wired; icon-only elements are either decorative with text siblings or labeled.
- **macOS rail/expanded transport:** like, add-to-playlist, cast, mute, volume slider all labeled and wired; TAPE source picker and Compare button wired to `enterCompareSourcesMode` (reachability added to UAT as uat-082).
- **Space playback hotkey, ⌘1-4 jumps, ⌘[ back:** present (`CouchTourApp.swift`, `SpacePlaybackHotkey.swift`, `BackButton.swift`).
- **TrafficLights:** decorative window chrome in `SidebarView.swift:20` and `ExpandedNowPlayingView.swift:54` defined in `DesignSystem/LedgerDesign.swift:229`. Tracked for owner visual evaluation in UAT (uat-079).

## Minor findings (not filed separately)

- **Android:** inert like-button fallback — when no track id exists, `NowPlaying.kt:611` renders `IconButton(onClick = {})`, a dead heart button. Low impact; fold into #386's cleanup or the next Now Playing pass.
- **Android:** audio format string (`state.audioFormat`) no longer rendered anywhere on screen (notification only) — folded into #386.
- **Android Now Playing JAM CHART / TAPE chips:** decorative arrow icons use `contentDescription = null` with adjacent text — acceptable, noted for completeness.

## Push gate

Both suites green on this branch before push: Android `testDebugUnitTest` (772 tests, BUILD SUCCESSFUL) and
macOS `swift test --package-path macos/Packages/CouchTourKit` (470 tests, 0 failures).
