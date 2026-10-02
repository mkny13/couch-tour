# macOS codebase health (#489, part of #487)

Scope: `macos/CouchTour` (app target) and `macos/Packages/CouchTourKit`. This is the first written report; the next `pass:health` run should diff against it. Line numbers are as of the commit that adds this file.

## Leaks

Searched `macos/` for `FileHandle`, `URLSession(`, `DatabaseQueue(`/`DatabasePool(`, `NotificationCenter` observers, KVO, `addPeriodicTimeObserver`, `Timer`, and long-lived `Task`s.

| Finding | Where | Result |
|---|---|---|
| `FileHandle` / per-call `URLSession` / extra `DatabaseQueue` | none in app or Kit (only `URLSession.shared`) | Nothing to fix |
| AVPlayer periodic time observer | `Player.swift:644` (added), `Player.swift:191` (removed) | Paired; ok |
| KVO observations (`currentItem`, `rate`, item `status`) | `Player.swift:629-632`, `:711` | Held as `NSKeyValueObservation` properties, released with the owner; ok |
| Heartbeat loop | `CastClient.swift:222` | `Task` stored in `heartbeatTimer`, uses `[weak self]`; ok |
| Fire-and-forget `Task { }` in views | `HomeView`, `SearchView`, `SyncView`, `DiagnosticsView`, … | Short-lived network/DB calls that end on their own; no cancellation needed |

No leaks found, so no leak fixes were made.

## Dead code

Removed (each had no caller anywhere in `macos/`, including tests):

- `macos/CouchTour/DesignSystem/InlineErrorView.swift` (whole file)
- `macos/CouchTour/DesignSystem/NavigationTile.swift` (`NavigationTile`, `DisclosureChevron`)
- `View.cardSurface(padding:)` in `DesignSystem/CardSurface.swift` (`CardMetrics` is still used)
- `PlayerController.playNextTourStop(_:)` and `dismissPostShowPrompt()` in `Player.swift` (their only caller, `NextTourStopPromptBanner`, is already gone)
- `CastPlaybackStateMachine.createStopPacket()` in CouchTourKit
- `WaveformLoader.loadWaveform(from:barCount:)` and its `cache` in CouchTourKit (the scrubber uses `loadEnvelope`)

No `AXIdentifiers` entries were orphaned by these removals; `macos/scripts/check-ax-ids.sh` passes.

Carried over from the old `macos_dead_code_audit.md` (now deleted):

- `RootView`, `MiniPlayerView`, `NowPlayingInspector`, `NextTourStopPromptBanner`, `QueueRow`: already gone.
- `CastRoutePickerButton` / `CastRoutePickerMenu`: the old audit was stale. They are used by `ThreePaneRootView.swift:43` and `ExpandedNowPlayingView.swift:440`, so they stay.
- No obsolete network adapters (`PhishInAPI`, `RelistenAPI`, `Sync` all live).
- Deprecated APIs (`NSApp.keyWindow` at `Player.swift`, `.foregroundColor` in `SearchView`, `HomeView`, `PlayerRailView`) are still present, but upgrades are out of scope for #489 and remain open.

`PlayerController.postShowPrompt` is still published and set (`Player.swift:283`, `:685`) but nothing in the views reads it now. Left alone as bigger than a local fix; see follow-up below.

## Complexity

Ranked by line count of `func` and `var body` declarations (brace-matched). Within the five named files nothing is huge: the longest were `HomeView.inProgressCard` (96) and `ShowDetailView` `TrackRow.body` (90). `Player.swift`, `Catalog.swift` and `RelistenAPI.swift` have no function over 90 lines.

Split (structural only, comments kept):

- `HomeView.swift` `inProgressCard`: header and play row extracted to `inProgressHeader(_:)` and `inProgressPlayRow(_:)` (96 → ~45 lines).
- `ShowDetailView.swift` track row `body`: extracted `titleAndBadges` and `trackMenu` (90 → ~45 lines).

Top 10 longest functions / view bodies across `macos/` after this change:

| # | Lines | Location | Declaration |
|---|---|---|---|
| 1 | 428 | `ExpandedNowPlayingView.swift:20` | `var body` |
| 2 | 397 | `PlayerRailView.swift:18` | `var body` |
| 3 | 240 | `SearchView.swift:64` | `var body` |
| 4 | 176 | `SidebarView.swift:17` | `var body` |
| 5 | 147 | `SyncView.swift:20` | `var body` |
| 6 | 138 | `Browse/TourPickerSheet.swift:23` | `var body` |
| 7 | 132 | `CastRoutePicker.swift:40` | `var body` |
| 8 | 126 | `LocalPlaylistView.swift:33` | `var body` |
| 9 | 125 | `CouchTourApp.swift:25` | `var body` |
| 10 | 101 | `CouchTourKit/Artwork.swift:68` | `func curatedPalette` (data table) / `LocalPlaylistsView.swift:281` `tableRow` |

Items 1-9 are outside the issue's named files and are candidates for the next pass (the first two are the best targets).
