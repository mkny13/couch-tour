# Client polling and timer audit (#505, part of #502)

Every periodic loop and timer in both clients, as of this pass. Verdicts: **fine**, **fixed** (was wasteful, changed here), **ok-bounded**.

| # | Where | Interval | Calls | Stops when | Verdict |
|---|-------|----------|-------|-----------|---------|
| 1 | Android `PlaybackService.startProgressSaver` | 5 s | disk (Room progress write) | never (before) / not playing (now) | **Fixed.** Woke every 5 s forever, even paused or idle. Now `runWhilePlaying` (`Polling.kt`) suspends with no timer until `isPlaying`; the play/pause edge is still saved by the listener, so at most 5 s is lost while playing. |
| 2 | Android `AccountScreens.kt` Sync device list | 5 s → 60 s backoff (1.5x) | network (`/devices`) | screen leaves composition | **Fixed.** Kept polling with the app backgrounded. Now inside `repeatOnLifecycle(STARTED)`: stops on stop, refreshes immediately on resume. Backoff pulled into `DeviceListBackoff`. |
| 3 | Android `Sync.kt` `SyncWorker` (WorkManager) | 15 min (platform minimum) | network | OS-scheduled, battery/constraint aware | Fine. |
| 4 | Android `Sync.kt` paging `while (true)` | none (back-to-back) | network | `!hasMore` | Fine; bounded by server pages. |
| 5 | Android `Sync.kt` debounce `delay(delayMs)` | debounce | none until fire | fires once | Fine (protocol; out of scope). |
| 6 | Android `TvNowPlaying.kt` scrubber | 500 ms | in-memory `vm.refresh()` | `!isPlaying`, leaves composition | Fine; no I/O, only while playing on a foreground TV screen. |
| 7 | Android `CompareSources.kt` / `SearchScreen.kt` / `OnThisDate.kt` / `MainActivity.kt` short `delay` | 250-500 ms | none (debounce/stagger) | one-shot | Fine. |
| 8 | macOS `ThreePaneRootView.swift` periodic sync | 15 min | network | window view disappears | **Fixed.** Ticked while the app was hidden/inactive. Now skipped via `PeriodicSyncPolicy.shouldSync(appIsActive:)`; `didBecomeActive` already syncs on return. |
| 9 | macOS `CastClient.swift` heartbeat | 5 s | cast socket ping | disconnect | Fine; required to keep the Cast connection alive, only exists while connected. |
| 10 | macOS `CastClient.swift` `clipStatusTimer` | 500 ms | cast socket status request | only for clip tracks, only sends while playing; cancelled on load/disconnect | Fine. |
| 11 | macOS `SyncView.swift` device list | 5 s → 60 s backoff | network | Settings view disappears (task cancelled) | Fine: Settings scene only exists while open. Not gated on app-active; left as-is to keep the change minimal. |
| 12 | macOS `SearchView.swift` | 300 ms | none | one-shot debounce | Fine. |
| 13 | CouchTourKit `Sync.swift` `sleepForDebounce` | debounce | none until fire | one-shot | Fine (protocol; out of scope). |

No `Timer`, `setInterval`, or `snapshotFlow` loops exist outside the above.

## Tests
- `PollingTest` (Android, in `SyncTest.kt`): backoff growth/cap/reset; progress ticker is idle while paused and ticks while playing.
- `PeriodicSyncPolicyTests` (CouchTourKit): periodic sync only while the app is active.

## Needs a human on the next beta
Sync screen device list still updates on pair/unpair; cast Now Playing still tracks position; progress still saves while playing.
