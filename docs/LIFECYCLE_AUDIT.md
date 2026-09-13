# Audio Player Lifecycle Audit (Issue #186)

This document contains findings from an audit of the Android `MediaSessionService` / `ExoPlayer` and macOS `AVPlayer` / `CouchTourKit` lifecycle management, focusing on proper cleanup, unreleased resources, and memory leaks during playlist transitions and backgrounding.

## Android: `MediaSessionService` / `ExoPlayer`
1. **Player Release:** `ExoPlayer` is managed correctly in `PlaybackService.kt`. `localPlayer` and `castPlayer` are created once and correctly released in `PlaybackService.onDestroy()` via `release()`.
2. **Backgrounding & Pause:** When playback is paused, Android Media3's `MediaSessionService` automatically drops foreground priority (`stopForeground(false)`), allowing the OS to reclaim resources properly without explicitly killing the service.
3. **Swipe-away Cleanup:** `PlaybackService` correctly implements `onTaskRemoved(rootIntent: Intent?)`. If the app is swiped away while paused, it safely calls `stopSelf()`, which stops the service and triggers `onDestroy()` for a clean release.
4. **Playlist Transitions:** When changing playlists, `c.setMediaItems(items)` is called via `MediaController`. `ExoPlayer` natively handles replacing the queue, correctly releasing decoders and un-retained `MediaItem`s internally. There are no leaked audio resources across transitions.
5. **No Issues Found:** The Android lifecycle implementation handles memory and resources securely.

## macOS: `AVPlayer` / `CouchTourKit`
1. **Backgrounding:** macOS does not use `AVAudioSession` like iOS does (which requires manual `setActive(false)`). Instead, `AVPlayer` relies on macOS App Nap and the OS scheduler. As an audio player, it expects to continue playing in the background natively. No explicit audio session deactivation is required or possible.
2. **Playlist Transitions:** `Player.swift` holds a `private var items: [AVPlayerItem]` array which represents the queue. During transitions, `queuePlayer.removeAllItems()` is invoked, and `items` is reassigned to the new tracks. This releases strong references to the previous `AVPlayerItem` instances properly, avoiding leaks.
3. **Retain Cycles:** Observers like `queuePlayer.observe` correctly use `[weak self]` in closures (e.g. `rateObservation`, `currentItemObservation`), avoiding strong reference cycles. 
4. **⚠️ Finding (Minor Leak Risk):** The `timeObserverToken` obtained from `queuePlayer.addPeriodicTimeObserver(...)` in `Player.swift` is never removed. `AVPlayer` retains the observer block. While `Player` is currently an `@StateObject` on the `App` (meaning it lives for the process lifetime and the memory is reclaimed on quit), this is a lifecycle hygiene issue. If `Player` were ever recreated, the old `AVQueuePlayer` and its observer block would leak and potentially crash.

### Recommended Fix (macOS)
Update the `deinit` block in `macos/CouchTour/Player.swift` to remove the time observer:

```swift
    deinit {
        if let timeObserverToken {
            queuePlayer.removeTimeObserver(timeObserverToken)
        }
        if let spaceKeyMonitor {
            NSEvent.removeMonitor(spaceKeyMonitor)
        }
    }
```
