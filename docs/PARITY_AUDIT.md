# Baseline Feature Parity Audit: macOS & Android (#292)

**Date:** 2026-09-30 · **Trigger:** #292 · **Branch:** `mahler/292-periodic-scans-for-feature-parity-betwee`

This document establishes the baseline feature parity audit between the Couch Tour macOS client and Android client, covering user-facing capabilities, data models, and navigation surfaces. Future recurring scans (conducted under mahler#606) will repeat against this specification.

## Method & Scope

- **Navigation & Entry Points:**
  - Android: `MainActivity.kt` (`NavHost` composable routes, Ledger bottom navigation bar, Home shelves, and screen composables in `app/src/main/java/dev/mike/couchtour/`).
  - macOS: `SidebarView.swift`, `ThreePaneRootView.swift` (`NavigationStack` `Route` destinations in `Navigation.swift`, toolbar items, and view destinations in `macos/CouchTour/`).
- **Historical Parity Check:**
  - Audited against `docs/plans/DESKTOP-PARITY-PLAN.md` (#56-#59) to confirm what that plan shipped (favorite artists, phish.in login, likes, local playlists) and whether any regressions occurred.
  - Coordinated with the Ledger redesign audit (#356, D305) and recent parity sweeps (#380-#382 for Library and account models).
- **Executable Smoke Spec Alignment:**
  - Per owner direction (#292), `scripts/smoke/JOURNEYS.md` (#368) serves as the executable parity specification. Every audited feature is evaluated against whether an active smoke journey exercises it across platforms (`mac`, `android`, or `both`).

---

## Feature Parity Matrix

Each user-facing feature is marked as:
- **Present:** Fully functional and reachable on that platform.
- **Partial (<note>):** Incomplete, regressed, or missing key attributes (linked to issue).
- **Missing:** Absent from the platform's UI and data layer.

| Category | Feature | macOS | Android | Smoke Journey | Tracking / Status |
|---|---|---|---|---|---|
| **Navigation** | Home Screen & Shelves | Present | Present | `launch-cold-start`, `home-sections-after-relaunch` | Core entry on both; discovery shelves data-driven. |
| **Navigation** | In Progress / Continue Listening | Present | Present | `home-sections-after-relaunch`, `in-progress-syncs-android-to-mac` | Present on both; syncs via backend. |
| **Navigation** | Next Tour Stops Card | Present | Present | `next-stop-chip-focus` | macOS uses inline Track tour button (#361); Android uses artist chips (#354, #387). |
| **Navigation** | On This Date Shelf | Partial (#362) | Present | `home-sections-after-relaunch` | Android fixed in #352 (batch cap 300); macOS parity fix open in #362. |
| **Navigation** | Surprise Me Action | Present | Present | None | macOS top bar pill; Android heuristic/curated discoverability. |
| **Navigation** | Browse Public Playlists Entry | Missing | Present | None | Android has "Browse playlists" on Home; macOS lacks public playlists entry (Filed: #428). |
| **Navigation** | Account Quick Links on Home | Missing | Present | None | Android has "Your phish.in account" section; macOS lacks Home account links (Related: #382). |
| **Navigation** | Artists Catalog Destination | Present | Present | `browse-artists-to-artist`, `nav-reaches-every-destination` | Both list Phish + Relisten artists; favorites pinned. |
| **Navigation** | Search Destination | Present | Present | `nav-reaches-every-destination`, `search-artist-hit` | macOS persistent toolbar field; Android dedicated screen. Android field tag open in #423. |
| **Navigation** | Library Destination | Partial (#382) | Present | `nav-reaches-every-destination`, `library-phishin-playlists` | Android lists saved shows, likes, playlists (#380, #381); macOS shows playback history (Open: #382). |
| **Navigation** | History / Listening Ledger | Present | Present | `nav-reaches-every-destination` | Both display reverse-chronological playback history from `phishin.db`. |
| **Navigation** | Settings Screen / Scene | Present | Present | `nav-reaches-every-destination` | macOS uses ⌘, TabView (Playback, Account, Sync); Android has grouped settings screen. |
| **Navigation** | Diagnostics Screen / Viewer | Missing | Present | None | Android has Settings → Diagnostics viewer (#344); macOS has no diagnostics UI (Filed: #430). |
| **Navigation** | Feedback Submission | Present | Present | None | Both launch GitHub bug report; Android optionally attaches diagnostics tail. |
| **Navigation** | Login / Authentication | Present | Present | None | macOS in Settings Account tab; Android dedicated `LoginScreen` route. |
| **Navigation** | Device Pairing (QR Code) | Present (Host) | Present (Scanner) | None | Intentional platform division: macOS displays QR code, Android scans via camera. |
| **Search** | Artist Search Hits | Present | Present | `search-artist-hit`, `search-result-sections` | Restored on macOS in #349; present on Android. |
| **Search** | Show Search Hits | Present | Present | `search-result-sections` | Both return and navigate to shows across backends. |
| **Search** | Track Search Hits | Present | Present | `search-result-sections` | Both return tracks with durations, tags, and like buttons. |
| **Search** | Song & Venue Slices | Present | Present | None | macOS has dedicated Songs & Venues tabs (#349); Android renders categorized SectionHeaders. |
| **Search** | Playlists Search Hits | Missing | Present | None | Android returns public phish.in playlists; macOS drops playlists in `Catalog.swift` (Filed: #427). |
| **Search** | Search Sort Modes | Present | Present | None | Both support Relevance, Date, Duration, and Rating sort modes. |
| **Search** | Search Filter Chips | Present (Toggles) | Present (Dynamic) | None | macOS has SBD and Jam Chart toggles; Android has dynamic tag filter chips. |
| **Playback** | Transport (Play/Pause, Skip) | Present | Present | None | Wired on both; macOS also provides keyboard shortcuts (Space, ⌘←, ⌘→). |
| **Playback** | Waveform Scrubber | Present | Present | None | Dynamic envelope from phish.in / archive.org with offline procedural fallback. |
| **Playback** | Volume Control | Present (Slider) | Present (System) | None | macOS has in-rail slider and mute; Android uses system hardware volume (Intentional). |
| **Playback** | Volume Leveling (EBU R128) | Present | Present | None | Both measure loudness in background and apply gain; Settings toggle + clear cache. |
| **Playback** | Skip Filler Tracks | Present | Present | None | Both filter non-music tracks during playback queue advancement. |
| **Playback** | Gapless Playback Toggle | Missing | Present | None | Android has `PlaybackSettings.gapless`; macOS lacks preference (Filed: #429). |
| **Playback** | Audio Quality (FLAC vs MP3) | Missing | Present | None | Android has Lossless/Compressed picker; macOS lacks preference and picker (Filed: #429). |
| **Playback** | Jam Chart Badge & Notes | Present | Present | `jam-chart-note-details` | Both display badge, real note text, and phish.in per-track link (#378, #379). |
| **Playback** | Now Playing Artwork | Present | Partial (#386) | None | macOS has `ConicGlowArtwork`; Android dropped artwork in redesign (Open: #386). |
| **Playback** | Audio Format Badge (FLAC/MP3) | Present | Partial (#386) | None | macOS displays format in rail; Android dropped in redesign (Open: #386). |
| **Playback** | Compare Sources (Auditioning) | Present | Present | None | Both support seamless cross-source comparison during active playback. |
| **Playback** | Up Next Queue Management | Present | Present | None | Both list upcoming tracks and allow direct tap-to-play. |
| **Playback** | Now Playing Track Like Button | Present | Present | None | Both support liking currently playing track (phish.in + Relisten). |
| **Playback** | Show Detail Show Like Button | Present | Missing | None | macOS has `ShowLikeButton` (D229); Android lacks server show like, uses local bookmark (Filed: #431). |
| **Playback** | YouTube Playback | Present (Video) | Present (Audio) | None | macOS uses official IFrame embed (D256); Android resolves audio stream (Intentional). |
| **Library** | Favorite Artists | Present | Present | `favorite-persists-across-relaunch`, `no-unfavorited-in-favorites` | Star toggle on both; local persistence verified. |
| **Library** | Favorite Artists Device Sync | Missing | Missing | `favorite-syncs-mac-to-android`, `favorite-syncs-android-to-mac` | Neither platform syncs favorites through backend; both local-only (Open: #351). |
| **Library** | Liked Tracks (phish.in + Relisten) | Partial (#382) | Present | None | Android displays unified liked tracks in Library (#381); macOS Library pending (#382). |
| **Library** | Saved / Bookmarked Shows | Partial (#382) | Present | None | Android has `SavedShows`; macOS removed Save in D229; macOS Shows tab pending (#382). |
| **Library** | Local Playlists (CRUD) | Present | Present | None | Both support creating playlists, adding/removing tracks, and playing from here. |
| **Library** | Public phish.in Playlists | Missing | Present | None | Android allows browsing public playlists; macOS lacks browsing entirely (Filed: #428). |
| **Library** | phish.in Account Playlists | Partial (#382) | Present | `library-phishin-playlists` | Android merges into Library (#381); macOS lacks account playlists in Library (Open: #382). |
| **Library** | Playback Progress Device Sync | Present | Present | `in-progress-syncs-android-to-mac` | Both clients sync `progress` table via Cloudflare Worker backend. |
| **Account** | phish.in Login / Logout | Present | Present | None | Both authenticate credentials, store token securely (Keychain / SharedPreferences). |
| **Account** | Dedicated Account Content Views | Missing | Present | None | Android has My Shows, My Tracks, My Playlists; macOS has no account content views (Related: #382). |
| **Offline** | Audio File Downloads | Missing | Missing | None | Intentionally unsupported on both platforms (product decision #65 / #141). |
| **Offline** | Metadata & Envelope Cache | Present | Present | None | Both support in-memory caching and offline waveform fallbacks. |
| **Offline** | On This Date Daily Cache | Partial (#362) | Present | None | Android handles bounded requests; macOS range timeout and cache bug open in #362. |
| **Casting** | Google Cast Sender & Handoff | Present | Present | None | Both discover receivers, hand off queue, and bypass local volume leveling. |
| **Casting** | AirPlay System Route Picker | Present | Missing | None | macOS embeds `AVRoutePickerView` in toolbar popover (Intentional platform feature). |
| **Casting** | Cast Connected Header Banner | Present | Partial (#386) | None | macOS displays banner in rail & popover; Android Now Playing regressed (Open: #386). |
| **Casting** | Cast Picker in Window Toolbar | Present | Present | None | Both place cast route picker in top bar / toolbar (#311). |
| **Settings** | Theme Mode (Auto/Light/Dark) | Present | Present | None | Supported on both platforms; reactive theme switching. |
| **Settings** | Sparkle Software Updates | Present | Missing | None | macOS checks updates via Sparkle; Android uses APK release workflow (Intentional). |
| **Settings** | Diagnostics Logging & Viewer | Missing | Present | None | Android has `DiagnosticsLog` and `DiagnosticsScreen` (#344); macOS has none (Filed: #430). |
| **Settings** | Feedback with Diagnostics Export | Partial | Present | None | Android copies diagnostic tail to clipboard; macOS opens bug form without log. |

---

## Intentional Platform Differences

These items represent deliberate design and architectural divergence suited to each operating system. They are **not** considered defects or parity gaps:

1. **Windowing & Navigation Chrome:**
   - macOS utilizes a 3-pane layout (`SidebarView`, central content pane with `NavigationStack`, and right `PlayerRailView`), Menu Bar command menus (Playback, ⌘1–4, ⌘F, ⌘⌥I), and toolbar breadcrumbs.
   - Android utilizes the Ledger bottom bar (`nav.home`, `nav.search`, `nav.library`, `nav.history`, `nav.settings`), TopAppBar, and standard Android system gesture navigation.
2. **Volume Architecture:**
   - macOS exposes an in-app volume slider and mute toggle in `PlayerRailView` and `ExpandedNowPlayingView` governing AVFoundation output.
   - Android relies on hardware volume rockers and MediaSession audio stream routing.
3. **AirPlay Support:**
   - macOS integrates `AVRoutePickerView` for native AirPlay audio routing alongside Google Cast.
   - Android supports Google Cast exclusively.
4. **YouTube Playback Implementation (D256):**
   - macOS plays YouTube videos through the official YouTube IFrame Player API embedded in a WKWebView (`YouTubeVideoView.swift`), conforming to desktop embed terms.
   - Android extracts audio streams via `YouTubeStreams.kt` for background audio playback in Media3.
5. **Software Updates:**
   - macOS includes Sparkle auto-updating (`UpdaterViewModel`) in `PlaybackSettingsView` and Menu Bar.
   - Android uses beta release APKs (`scripts/cut-beta.sh`, `scripts/promote-beta.sh`).
6. **QR Code Pairing Roles:**
   - macOS acts as the host and displays the pairing QR code (`Qr.swift`, `SyncView.swift`).
   - Android acts as the client and provides a CameraX barcode scanner (`ScanScreen.kt`).
7. **Offline Downloads (Policy #65 / #141):**
   - Neither client supports downloading audio files to disk for offline listening. Streaming-only catalog access is intentional across both platforms.

---

## Issues Filed for New Gaps

Five real gaps identified during this audit were filed with label `mahler`, a `type:`, and a `size:`:

| Issue | Platform | Type | Size | Summary |
|---|---|---|---|---|
| [#427](https://github.com/mkny13/couch-tour/issues/427) | macOS | `type:feature` | `size:m` | **Search does not return or display phish.in playlists**: `SearchHits` in `Catalog.swift` explicitly omits playlists; search results on macOS do not show matching playlists. |
| [#428](https://github.com/mkny13/couch-tour/issues/428) | macOS | `type:feature` | `size:m` | **Browse public phish.in playlists**: Android provides "Browse playlists" on Home to view public phish.in playlists; macOS has no public playlists browser or detail view. |
| [#429](https://github.com/mkny13/couch-tour/issues/429) | macOS | `type:feature` | `size:m` | **PlaybackSettings lacks Audio Quality (FLAC vs MP3) and Gapless playback preferences**: macOS models only `skipFiller` and `levelVolume`, lacking Android's audio quality stream selector and gapless preloading. |
| [#430](https://github.com/mkny13/couch-tour/issues/430) | macOS | `type:feature` | `size:m` | **On-device diagnostics log and viewer**: Android has `DiagnosticsLog`, `DiagnosticsScreen`, and feedback clipboard export (#344); macOS has no in-app diagnostics. |
| [#431](https://github.com/mkny13/couch-tour/issues/431) | Android | `type:feature` | `size:m` | **Show Detail header lacks phish.in server-side Show Like button**: macOS has `ShowLikeButton` (D229); Android Show Detail only has local `SavedShows` bookmarks and no server show like. |

---

## Existing Issues Linked

The following existing open issues track cross-platform parity gaps and were re-verified at HEAD:

| Issue | Platform | Status | Summary |
|---|---|---|---|
| [#351](https://github.com/mkny13/couch-tour/issues/351) | Cross-platform | Open (`type:bug`, `size:l`) | **Favorite artists don't sync between devices (Android ↔ Mac)**: Favorites are stored in local `UserDefaults` on Mac and `SharedPreferences` on Android; no sync through `sync/` backend. Covered by smoke journeys `favorite-syncs-mac-to-android` and `favorite-syncs-android-to-mac`. |
| [#382](https://github.com/mkny13/couch-tour/issues/382) | macOS | Open (`p2`) | **Library shows playback history rather than saved shows, and misses phish.in account likes/playlists**: macOS Library Shows tab reads `progressStore.inProgress()` instead of saved shows, and lacks account likes/playlists. Covered by smoke journey `library-phishin-playlists`. |
| [#423](https://github.com/mkny13/couch-tour/issues/423) | Android | Open (`type:feature`, `size:m`) | **Add Android search field testTag and eliminate class fallback in smoke runner**: Parity gap with macOS `AXIdentifiers.searchField` preventing deterministic search smoke execution. |
| [#386](https://github.com/mkny13/couch-tour/issues/386) | Android | Open (`type:bug`, `size:m`) | **Ledger Now Playing dropped artwork, format badge, and cast indicator**: Android Now Playing regressed in redesign; macOS player rail displays all three. |
| [#362](https://github.com/mkny13/couch-tour/issues/362) | macOS | Open (`type:bug`, `size:m`) | **On This Date uses same oversized ranges and no-drop-on-timeout cache as #352**: Android bounded ranges to 300 in #352; macOS needs the same batching cap and cache safeguard. |

---

## Smoke Journey Parity Coverage

Per the addition to #292, `scripts/smoke/JOURNEYS.md` doubles as an executable parity spec. Coverage status across the current 15 smoke journeys:

1. `launch-cold-start` — mac, android (Covers cold start & root navigation on both)
2. `home-sections-after-relaunch` — mac, android (Covers In Progress, Next Tour Stops, On This Date on both)
3. `browse-artists-to-artist` — mac, android (Covers Artists list navigation to artist on both)
4. `search-artist-hit` — mac, android (Covers search query & artist hits on both)
5. `favorite-persists-across-relaunch` — mac, android (Covers local favorite persistence on both)
6. `no-unfavorited-in-favorites` — mac, android (Covers favorite list honesty on both)
7. `next-stop-chip-focus` — mac, android (Covers Next Tour Stop card focus on both)
8. `jam-chart-note-details` — mac, android (Covers Jam Chart note details and phish.in link on both)
9. `library-phishin-playlists` — mac, android (**Highlights Gap #382:** asserts Library displays phish.in account playlists; will fail or skip on macOS until #382 lands)
10. `favorite-syncs-mac-to-android` — mac→android (**Highlights Gap #351:** asserts favorite syncs from Mac to Android)
11. `favorite-syncs-android-to-mac` — android→mac (**Highlights Gap #351:** asserts favorite syncs from Android to Mac)
12. `in-progress-syncs-android-to-mac` — android→mac (Covers playback progress sync)
13. `nav-reaches-every-destination` — mac, android (Covers primary navigation destinations)
14. `search-result-sections` — mac, android (Covers artist, show, track search result sections)
15. `live-data-not-mockup` — mac, android (Regression guard for real phish.in catalog data)

### Recommended Journey Additions for Future Passes:
When recurring parity runs begin under mahler#606, new journeys should be added to `scripts/smoke/JOURNEYS.md` to directly fail when these newly filed parity gaps remain open:
- `search-playlists-hit` (`mac, android`): Search query returns public playlist hits on both platforms (fails on macOS until #427 lands).
- `settings-audio-quality` (`mac, android`): Settings contains audio quality selection toggle (fails on macOS until #429 lands).
- `like-show-detail` (`mac, android`): Show Detail header contains server-side show like button (fails on Android until #431 lands).
