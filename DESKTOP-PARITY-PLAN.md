# Desktop personal-library/account parity — working plan

Working document for branch `claude/desktop-android-parity-d41f72`. Delete or fold into
[DECISIONS.md](DECISIONS.md) when the work lands. Covers issues #56-59, the
personal-library/account cluster that was explicitly out of scope for the desktop MVP (see
[ROADMAP.md](ROADMAP.md) → Desktop (macOS) → Personal library and account parity).

## Scope

| # | Feature | Android original |
|---|---|---|
| #56 | Favorite artists | `Favorites.kt`, star toggle, pinned section |
| #57 | phish.in account login | `Auth.kt`, `LoginScreen` |
| #58 | Likes — phish.in (server, account-gated) + Relisten (local) | `LikedTracks.kt`, `LikeButton`/`LikeTrackButton` |
| #59 | Local playlists spanning both backends | `LocalPlaylist.kt`, Room |

Explicitly **not** in scope (already filed separately, do not fold in):
- #63 — like button on Now Playing (Android doesn't have one either; `PlayerState` carries no
  track id).
- #69 — playlist rename/manual reorder (Android only has append/remove).
- Importing an existing phish.in playlist, or excerpts — no write API to build against, and
  local playlists never supported excerpts on Android either.

## Sequencing and why

1. **#56 Favorite artists first.** Zero dependencies, smallest surface, and it establishes
   the `UserDefaults`-backed "flat `Set<String>`, `@Published`, toggle()" pattern the local
   half of #58 reuses verbatim.
2. **#57 Login next.** Needed before #58's phish.in half can do anything (the API rejects
   likes unauthenticated). Also the first place this app talks to phish.in with anything but
   `GET`, so it's worth landing and testing on its own before likes/playlists build on it.
3. **#58 Likes**, split into two independent halves that can land together or separately:
   Relisten's local half depends only on #56's pattern; phish.in's server half depends on #57.
4. **#59 Local playlists last.** Independent of login (account-free on Android, D161, and
   staying that way here) but the biggest single chunk — new GRDB tables/migrations, queue-key
   plumbing, and UI in three places (Library-ish entry point, playlist list/detail, add-to-
   playlist button on every track row).

Each lands as its own PR per the repo's usual per-issue cadence, merged once green and
self-reviewed, per [CLAUDE.md](CLAUDE.md)'s "Working through open issues."

## #56 — Favorite artists

- `ArtistRef` (`Catalog.swift`) gains a `key: String` computed property —
  `"\(backend.rawValue):\(id)"` — mirroring `Catalog.kt:46` exactly, since eventually this key
  may travel through sync the way queue keys do.
- New `Favorites` type in `CouchTourKit`: `UserDefaults.standard`-backed (not a GRDB table —
  same "low-cardinality preference data, not worth Room's/GRDB's migration ceremony"
  reasoning as `Favorites.kt:14-18`), a stored `Set<String>` under key
  `"favorite_artist_keys"`, `@Published var keys: Set<String>`, `toggle(_:)`. No explicit
  beta namespacing needed — `UserDefaults.standard` is already per-bundle-id, the same fact
  `Player.swift`'s volume setting already relies on.
- New pure function `mergeArtists(phish:relisten:favorites:) -> [ArtistRef]` in
  `Catalog.swift`, tested without network the same way the file's other mapping functions are:
  Phish always first, then favorited (by showCount desc), then the rest (by showCount desc) —
  port of `Catalog.kt:181-190`.
- `ArtistsView.swift`: consume `AppModel`'s new `Favorites` instance, add a "Favorites"
  section above the full list (only when non-empty) plus a star toggle per row via
  `.swipeActions` or a trailing button — SwiftUI idiom, not a literal port of Compose's
  `trailingContent`.
- `AppModel` gets a `let favorites = Favorites()` alongside `progressStore`/`syncSession`.

Test plan: `CatalogTests.swift` gets `mergeArtists` cases (Phish-always-first, favorited
pinned in showCount order, non-favorited unaffected); a quick `Favorites` toggle/persist round
trip. No live-API dependency — pure-function and UserDefaults tests only.

## #57 — phish.in account login

- New `PhishInAuth` type in `CouchTourKit`, mirroring `Auth.kt`'s `TokenStore`/`Session`
  split:
  - `TokenStore`: `KeychainStoring`-backed (reuse the existing protocol, **new** service
    string `"dev.mike.couchtour.phishin"` — never `SyncTokenStore`'s, per the explicit
    separation already called out in `Sync.swift:265`), storing only `jwt` and `username` —
    **never the password**, matching Android's design and its explicit "used for this one
    request and never stored" comment.
  - `Session`: `@Published var username: String?`, `login(email:password:) async throws`,
    `logout()`, restores from `TokenStore` on init.
- `PhishInAPI.swift` grows the auth machinery it was deliberately left without (comment at
  line 3/273 flags this as the intended extension point):
  - `X-Auth-Token` header (not `Authorization: Bearer` — same footgun Android's comment warns
    about), attached when a stored token exists.
  - `POST /auth/login` with `{email, password}` → `{jwt, username, email}`.
  - A 401-on-authed-request hook that triggers `Session.logout()`, excluding `/auth/login`
    itself (a bad-password 401 must surface as a login error, not a silent logout).
- UI: a "phish.in account" section, most likely folded into the new `Settings` scene next to
  Sync (D171 already put Sync there; login is the same kind of account-plumbing surface,
  not a browse destination) — a signed-out state with email/password fields and a disclaimer
  string matching Android's, a signed-in state showing the username with a log-out button.
  Decide the exact placement (Settings vs. a sidebar entry) once #56 is landed and the
  Settings scene's shape is back in view — flagging this as open rather than deciding
  prematurely.

Test plan: request-shape tests via the existing `MockServer.swift` pattern
(`RequestTests.swift` already covers headers/bodies for GET; extend for the login POST and
the `X-Auth-Token` attach/401 behavior). `Keychain.swift`'s `InMemoryKeychain` covers the
store round-trip without touching the real Keychain, same as `SyncTests.swift` does for sync.
Live verification (real phish.in login) happens by hand against the real API before calling
this done, the same way sync's live pairing was verified (D116-D148) — this repo does not
trust Robolectric/XCTest alone for anything crossing a real network+credentials boundary.

## #58 — Likes

Two independent halves; land together or separately depending on how #57 goes.

**Relisten (local, no dependency on #57):**
- New `LikedTracks` type, identical shape to `Favorites`: `UserDefaults`-backed
  `Set<String>` of Relisten track UUIDs, key `"liked_relisten_track_ids"`, `toggle(_:)`.
- A heart-icon button on Relisten track rows (wherever `TrackGroups.swift` renders a
  `PlayableTrack` row) — no count, no auth check, matching `LikeTrackButton`.

**phish.in (server, depends on #57):**
- `PhishInAPI.swift` gains `Likable` (show/track/playlist), `like`/`unlike` (POST/DELETE
  `/likes`), and decodes `likesCount`/`likedByUser` on the `Show`/`Track`/`Playlist` structs
  that don't carry them yet (check `PhishInAPI.swift:72-73,133-134` — some may already).
- A like button on phish.in track rows, signed-out state showing the public count but inert
  to taps (matches `LikeButton`'s `Session.username` gate, `MainActivity.kt:1948-1990`),
  optimistic toggle with rollback on failure. Scoped to tracks, matching the issue title and
  the Android #11 precedent it's explicitly porting — not the show/playlist header, which
  would need `ShowSummary` to carry a phish.in numeric show id it doesn't have yet.
- Explicitly **not** on Now Playing (#63, out of scope here).

Test plan: `LikedTracks` gets the same toggle/persist test as `Favorites`. phish.in half gets
`MockServer`-based request-shape tests (POST/DELETE `/likes` payload, auth-gating), plus a
signed-out-tap-is-inert UI-level check if the test harness supports it, otherwise verified by
hand.

## #59 — Local playlists

The biggest single piece. Port of `LocalPlaylist.kt` + `Progress.kt`'s `MIGRATION_7_8`.

- **Schema** (new GRDB migrations on the existing `phishin.db`/`ProgressStore`, not a new
  database file — matches `ProgressStore.swift`'s `DatabaseMigrator` pattern of named,
  additive migrations): `local_playlists` (id UUID string, name, trackCount denormalized,
  createdAt, updatedAt) and `local_playlist_tracks` (rowId autoincrement PK, playlistId FK
  cascade-delete, position, backend, trackId, showDate, artistSlug?, recordingId?, plus
  denormalized title/durationMs/venueName/artURL) — column-for-column port of
  `LocalPlaylistEntity`/`LocalPlaylistTrackEntity` (`LocalPlaylist.kt:22-68`).
- **Queue key**: `QueueKind` in `QueueKey.swift` gains `.localPlaylist`, prefix
  `"local-playlist:"`, parsed the same way `.playlist`/`.recording` already are — must be
  byte-identical to `Queue.kt`'s `"local-playlist:"` per this file's own sync-contract
  comment.
- **Resolution at play time**: `resolveLocalPlaylistTracks` — group stored refs by distinct
  show/tape, one fetch per show rather than per track, skip unresolvable refs rather than
  failing the whole playlist — port of `LocalPlaylist.kt:132-192`.
- **UI**: an entry point (this app has no separate "Library" screen either — most natural
  home is a new item in `ArtistsView`'s list or a new sidebar section, decide once #56's
  "Favorites" section shape is settled so the two don't collide visually), a playlist
  list/detail pair (new/rename-free — no rename, per #69), and an add-to-playlist button
  placed next to the like button on every track row (`TrackGroups.swift`) once #58 lands
  there, so the two ship in the same visual slot rather than needing a second pass.

Test plan: `ProgressStoreTests.swift`-style migration test (schema applies cleanly, existing
`progress` rows untouched — this repo's Room/GRDB migration discipline is non-negotiable per
CLAUDE.md's "Room migrations" section, which applies in spirit to GRDB too), CRUD round trip
on the new tables, `QueueKeyTests.swift` cases for `.localPlaylist` parse/round-trip,
`resolveLocalPlaylistTracks` unit tests against `MockServer` fixtures (multi-show playlist,
one unresolvable ref skipped not fatal).

## Cross-cutting notes

- `UserDefaults`-backed stores (`Favorites`, `LikedTracks`) need no explicit beta namespacing
  — `.standard` is already per-bundle-id. Keychain-backed and GRDB-backed stores
  (`PhishInAuth.TokenStore`, the playlist tables) do need the `#if BETA` split
  `SyncTokenStore`/`ProgressStore` already apply, since Keychain services and the on-disk DB
  path are not automatically bundle-scoped.
- All new stores get constructed once in `AppModel.init()` and threaded through
  `@EnvironmentObject`, matching `progressStore`/`syncSession` today — no new ad-hoc
  singletons.
- None of this touches `sync/` — favorites/likes/playlists are local-only on both platforms
  today (Android has no server sync for them either), so no backend work is implied.
