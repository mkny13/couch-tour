# macOS Dead Code & Deprecated API Audit

## 1. Unused SwiftUI Views
The following SwiftUI views are no longer instantiated anywhere in the macOS app (or are only instantiated by other views in this list). They are remnants of older UI iterations (like the pre-3-pane layout) and can be safely removed:
- `RootView` (`macos/CouchTour/RootView.swift`) - Superseded by `ThreePaneRootView`.
- `MiniPlayerView` (`macos/CouchTour/MiniPlayerView.swift`) - Superseded by `PlayerRailView`.
- `NowPlayingInspector` (`macos/CouchTour/NowPlayingInspector.swift`) - Superseded by `ExpandedNowPlayingView`.
- `NextTourStopPromptBanner` (`macos/CouchTour/NowPlayingInspector.swift` / `MiniPlayerView.swift`) - Only instantiated inside the dead `NowPlayingInspector` and `MiniPlayerView`.
- `CastRoutePickerButton` & `CastRoutePickerMenu` (`macos/CouchTour/CastRoutePicker.swift`) - Only instantiated inside the dead `NowPlayingInspector` and `MiniPlayerView`.
- `QueueRow` (`macos/CouchTour/NowPlayingInspector.swift`) - Only instantiated inside the dead `NowPlayingInspector`.
- `InlineErrorView` (`macos/CouchTour/DesignSystem/InlineErrorView.swift`) - Declared but never used.
- `NavigationTile` & `DisclosureChevron` (`macos/CouchTour/DesignSystem/NavigationTile.swift`) - Declared but never instantiated.

## 2. Obsolete Network Adapters
A review of the network and API layers (`PhishInAPI`, `RelistenAPI`, `Sync`) indicates that no obsolete network adapter files are lingering in the macOS client (`CouchTourKit`).
- `PhishInAPI` is still actively used for authentication, user profiles, and likes.
- `RelistenAPI` is the modern, actively-developed multi-artist adapter.
- `Sync` remains the source of truth for device synchronization.
*Recommendation:* No wholesale network adapter removals are required for macOS at this time.

## 3. Deprecated APIs & Modern Replacements
The following API usages are deprecated or strongly discouraged in modern macOS SDKs and should be upgraded:

### `NSApp.keyWindow`
- **Location:** `macos/CouchTour/Player.swift:437` (`NSApp.keyWindow?.firstResponder`)
- **Deprecation:** Deprecated in macOS 10.14.
- **Replacement:** Use `NSApp.windows.first(where: \.isKeyWindow)?.firstResponder` instead.

### `.foregroundColor()`
- **Locations:**
  - `macos/CouchTour/SearchView.swift:530`
  - `macos/CouchTour/HomeView.swift:651`
  - `macos/CouchTour/PlayerRailView.swift:391`
- **Deprecation:** Deprecated in macOS 12 / iOS 15.
- **Replacement:** Use the modern `.foregroundStyle()` modifier, which supports advanced styling (like hierarchical materials and gradients).

## 4. Removal & Refactor Plan
**Estimated Scope of Effort:** Small (~1-2 hours)

**Prioritized Steps:**
1. **Remove Dead Views (Low Risk, High Value):**
   - Delete `RootView.swift`, `MiniPlayerView.swift`, `NowPlayingInspector.swift`, `CastRoutePicker.swift`, `InlineErrorView.swift`, and `NavigationTile.swift`.
   - Recompile to verify no dangling references remain.
2. **Upgrade Deprecated APIs (Low Risk):**
   - Replace `keyWindow` with `windows.first(where: \.isKeyWindow)` in `Player.swift`.
   - Replace all instances of `.foregroundColor` with `.foregroundStyle`.
3. **Verify:**
   - Run `swift test --package-path macos/Packages/CouchTourKit` and do a manual build via `xcodebuild` to ensure the project remains healthy.
