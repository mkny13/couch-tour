# Desktop personal-library/account parity — completed plan

Working document for branch `claude/desktop-android-parity-d41f72` (now merged and shipped). Covers issues #56–#59, the personal-library/account cluster that was out of scope for the desktop MVP.

Archived decision log entries are in [DECISIONS.md](DECISIONS.md) (D179–D180 / Iteration 44) and tracked in [ROADMAP.md](ROADMAP.md).

## Scope & Status

| # | Feature | Android original | Status | Implementation / PR |
|---|---|---|---|---|
| **#56** | Favorite artists | `Favorites.kt`, star toggle, pinned section | **Shipped** | `Favorites.swift`, `mergeArtists()`, star toggle on `ArtistsView`, PR #75 |
| **#57** | phish.in account login | `Auth.kt`, `LoginScreen` | **Shipped** | `PhishInAuth.swift` (Keychain `TokenStore` + `Session`), `AccountView` in Settings, PR #77 |
| **#58** | Likes — phish.in + Relisten | `LikedTracks.kt`, `LikeButton`/`LikeTrackButton` | **Shipped** | `LikedTracks.swift` (local) + `PhishInAPI` (server `Likable`), `TrackLikeButton`, PR #78 |
| **#59** | Local playlists spanning both backends | `LocalPlaylist.kt`, Room | **Shipped** | `LocalPlaylistStore.swift` (GRDB), `.localPlaylist` queue key, `PlaylistsView`, PR #80, #81 |

### Explicitly Not in Scope (Tracked Separately)
- **#63** — Like button on Now Playing (`PlayerState` carries no track ID).
- **#69** — Playlist rename / manual reorder (currently append and remove only).
- Importing existing phish.in server playlists, or excerpt clipping.

---

## Sequencing & Completed Implementation

### 1. #56 — Favorite Artists (Shipped, PR #75)
- `ArtistRef` (`Catalog.swift`) gained `key: String` (`"\(backend.rawValue):\(id)"`) matching `Catalog.kt:46`.
- `Favorites` in `CouchTourKit`: `UserDefaults.standard`-backed `Set<String>` under key `"favorite_artist_keys"`, `@Published var keys`, `toggle(_:)`.
- `mergeArtists(phish:relisten:favorites:) -> [ArtistRef]` in `Catalog.swift`: Phish first, then favorited artists (by `showCount` desc), then remaining artists (by `showCount` desc).
- `ArtistsView.swift`: Displays a pinned "Favorites" section when non-empty, with star buttons to toggle favorite state.
- Covered by pure function and persistence unit tests in `CatalogTests.swift`.

### 2. #57 — phish.in Account Login (Shipped, PR #77)
- `PhishInAuth` in `CouchTourKit`:
  - `TokenStore`: `KeychainStoring`-backed (`service: "dev.mike.couchtour.phishin"`), storing `jwt` and `username` (password is never stored).
  - `Session`: `@Published var username: String?`, `login(email:password:)`, `logout()`.
- `PhishInAPI.swift`:
  - `X-Auth-Token` header attached when authenticated.
  - `POST /auth/login` endpoint decoding credentials and tokens.
  - `onUnauthorized` hook triggering `Session.logout()` on 401s (excluding `/auth/login` itself).
- UI: Dedicated "Account" tab in the `Settings` scene (⌘,) with signed-in status, disclaimer copy, and login/logout forms.
- Covered by `RequestTests.swift` (`MockServer`), `InMemoryKeychain` store tests, and real API authentication verification.

### 3. #58 — Likes (Shipped, PR #78)
- **Relisten (Local)**: `LikedTracks` in `CouchTourKit` (`UserDefaults`-backed `Set<String>` of track UUIDs, key `"liked_relisten_track_ids"`, `toggle(_:)`).
- **phish.in (Server)**: `PhishInAPI.swift` added `Likable`, `like`/`unlike` (POST/DELETE `/likes`), decoding `likesCount` and `likedByUser` on `Track`.
- UI: Unified `TrackLikeButton` on `TrackRow` and `QueueRow` with optimistic toggles and signed-out view state.
- Scoped to track rows (Now Playing screen like action deferred to #63).

### 4. #59 — Cross-Backend Local Playlists (Shipped, PR #80, #81)
- **Schema**: GRDB migrations adding `local_playlists` and `local_playlist_tracks` (cascade delete on foreign keys) sharing `phishin.db` via `LocalPlaylistStore(sharing:)`.
- **Queue Key**: Added `.localPlaylist` (`"local-playlist:<uuid>"`) to `QueueKind` in `QueueKey.swift`, matching Android's sync key grammar.
- **Concurrent Track Resolution**: `resolveLocalPlaylistTracks` groups stored track entries by show/tape and fetches distinct shows concurrently (D175 parity, PR #81), skipping missing tracks rather than failing playback.
- UI: "Playlists" sidebar section, playlist detail view with play-from-here / track deletion / playlist deletion, and "Add to Playlist" popover on all track rows.

---

## Verification & Cross-References

- All package unit tests passing in `CouchTourKitTests` (`swift test`).
- macOS Debug & Beta build targets verified cleanly (`xcodebuild`).
- Documented in [ROADMAP.md](ROADMAP.md) and logged in [DECISIONS.md](DECISIONS.md).
