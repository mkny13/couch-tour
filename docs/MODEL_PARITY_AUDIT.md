# Cross-Platform Model Parity Audit (Issue #187)

This document compares Android's Kotlin domain models, Room entities, sync payloads, and
state representations against macOS's Swift structs, GRDB records, sync payloads, and state
representations, per the second leg of the #176 refactoring pass (first leg: audio player
lifecycle, `docs/LIFECYCLE_AUDIT.md`). This is a comparison/audit only — fixing anything found
here is a follow-up task, per #187's scope.

## Sync payloads: progress, history, and resume — no discrepancies found

Both clients and the Cloudflare Worker backend (`sync/src/types.ts`'s `ProgressFields`) agree
on a single 12-field row (`queueKey`, `title`, `subtitle`, `artUrl`, `trackIndex`, `positionMs`,
`trackTitle`, `updatedAt`, `finished`, `dismissed`, `artist`, `deletedAt`) that covers current
position, history, and resume state — there is no separate history or resume type on either
platform or the backend. Field names, types, and camelCase wire naming are identical across
Android's `SyncProgressWire` (`Sync.kt`), macOS's `SyncProgressWire` (`Sync.swift`), and the
backend's `ProgressFields`. Both clients independently solved the same "nulls must be sent
explicitly" problem the backend's D1 `.bind()` requires — Android via
`Json { encodeDefaults = true }`, macOS via a hand-written `encode(to:)` — arriving at
equivalent wire behavior by different mechanisms. `PairStartResponse`, `PairClaimResponse`,
`SyncRequest`, `SyncResponse`, and `DeviceInfo` also match field-for-field on both clients.
The Room `Progress` entity and GRDB `PlaybackProgress` record are byte-identical in column
list, as macOS's own code comment states. **No action needed.**

`ArtistTourPreferenceEntity` (Android, `Progress.kt`) and `ArtistTourPreference` (macOS,
`ProgressStore.swift`) also match field-for-field (`artistKey`, `tourName`, `year`,
`updatedAt`), as does the `local_playlists`/`local_playlist_tracks` schema against Android's
`LocalPlaylistEntity`/`LocalPlaylistTrackEntity` (`LocalPlaylist.kt` vs `LocalPlaylist.swift`).

## Domain models: discrepancies found

### ⚠️ `ShowSummary`: Android's cross-backend model still lacks `id`/`likedByUser` that Android's own raw model already has

macOS's `ShowSummary` (`Catalog.swift`) carries `id: Int64` and `likedByUser: Bool`, added in
D229 (#148) specifically so the show-detail header could add a phish.in server-side show
Like button. D229's own text says both fields "were already decoded on macOS's `Show` DTO and
exist on Android's `Show` model; the shared summary was simply dropping them" — but that fix
was scoped to macOS only. Android's own cross-backend `ShowSummary` (`Catalog.kt`) **still**
lacks both fields; only Android's raw phish.in `Show` (`Api.kt`) carries them.

**Consequence, confirmed by code search:** Android's `Likable` enum (`Api.kt`) already defines
`Likable.Show`, and the phish.in API layer can already POST a show like — but no Android
screen ever passes `Likable.Show` to a `LikeButton`. Only `Likable.Track` and
`Likable.Playlist` are used anywhere in `MainActivity.kt`/`NowPlaying.kt`. So while macOS
shipped a whole-show Like button on its Show Detail header (D229), **Android's Show Detail
screen has no show-level like action at all**, and its `ShowSummary` model can't carry the
state that a Like button would need without the same field addition macOS already made.

**Recommended fix:** add `id: Long = 0` and `likedByUser: Boolean = false` to Android's
`ShowSummary` (`Catalog.kt`), mirroring macOS's D229 change, then wire a show-level
`LikeButton(Likable.Show, ...)` into Android's show detail header for parity with macOS.

### ⚠️ `PlayableTrack`: macOS's cross-backend model carries likes/popularity that Android's does not

macOS's `PlayableTrack` (`Catalog.swift`) has `likesCount: Int`, `likedByUser: Bool`, and
`popularity: RelistenPopularity?`. Android's `PlayableTrack` (`Catalog.kt`) has neither — likes
are only modeled on Android's raw phish.in `Track` (`Api.kt`), not on the shared cross-backend
track type, and popularity isn't modeled on the track level at all on Android.

**Recommended fix:** decide whether Android's cross-backend `PlayableTrack` needs these fields
(e.g. if a future screen wants to show a Relisten track's popularity, or unify the like-button
call site to take a `PlayableTrack` instead of switching on raw `Track`/`PlayableTrack`), or
document that Android's like/popularity UI is intentionally only ever built against the raw
per-backend types. Currently undocumented either way.

### ⚠️ `Popularity` (Android) discards the raw per-window data that `RelistenPopularity` (macOS) retains

Android's `Popularity` (`Catalog.kt`) stores only five precomputed fields: `momentumScore`,
`trendRatio`, `hotScore48h`, `hotScore7d`, `hotScore30d`. macOS's `RelistenPopularity`
(`Catalog.swift`) stores the full `windows: [String: WindowPopularity]` map (each window
carrying `plays`, `hours`, `hotScore`) and derives the same hot-score fields plus
`plays48h/7d/30d` as computed properties. Since both are decoding the same upstream JSON, this
means Android is silently dropping `plays`/`hours` data the backend sends and macOS retains —
today this only matters if Android UI ever wants to show something like "142 plays this week,"
which it currently cannot do from this model without a decode change.

**Recommended fix:** if per-window play counts are wanted on Android (mirroring what macOS's
model already supports), change `Popularity` to decode the `windows` map the same way
`RelistenPopularity` does, rather than only the derived hot scores. Otherwise, no action needed
— but it's worth confirming this is a deliberate scope decision rather than an oversight.

### ⚠️ `Tag` (macOS) has a `notes` field with no Android counterpart

macOS's `Tag` (`Catalog.swift`) has `notes: String? = nil`. Android's `TagRef` (`Catalog.kt`)
has no equivalent field. Neither the Android nor macOS explorer found existing UI reading
`Tag.notes`, so this looks like an unused field on macOS rather than a live Android gap —
worth a quick grep for call sites before deciding whether to add it to Android or drop it from
macOS.

### Note (not a gap): `ShowDetail`'s queue-key override is macOS-only by design

macOS's `ShowDetail` (`Catalog.swift`) has a private `explicitQueueKey` slot, set only when
building a `ShowDetail` for a local playlist queue (a queue that spans arbitrary shows, so the
derived `show:<date>`/`relisten:...` key doesn't apply) — the code comment explains this
explicitly, tied to #59. Android's `ShowDetail` (`Catalog.kt`) has no such override because
Android never constructs a `ShowDetail` for local-playlist playback in the first place — local
playlists build a queue directly from `LocalPlaylistTrackEntity` rows via `ResolvedLocalTrack`
(`MediaItems.kt`) and `Queue.kt`'s `QueueKind.LOCAL_PLAYLIST`, bypassing `ShowDetail` entirely.
Both arrive at the same `local-playlist:<id>` queue key; the two platforms just route through
different types to get there. **No action needed**, but noting it here in case a future change
to one side's local-playlist plumbing assumes the other side shares the same code path.

### Note (not a gap, confirmed intentional): macOS's `SavedShows` removal

The macOS explorer found no `SavedShows`-equivalent store, while Android has one
(`SavedShows.kt`). This is not a drift — D229 (#148) deliberately deleted macOS's
`SavedShows` store, `AppModel` property, and UI (Save/Saved pill, bookmark icon) as part of
"Two Ways to Mark a Show, Not Three," explicitly noting "Android's own bookmark store is
deliberately untouched — #148 is macOS-scoped; Android's removal is a separate change." If
Android's bookmark/save feature is ever revisited, that's tracked as its own decision, not a
parity bug.

## State representations

### ⚠️ Settings: Android has `gapless` and `audioQuality` preferences that macOS's `PlaybackSettings` lacks entirely

Android's `PlaybackSettings.kt` models three preferences: `skipFiller`, `gapless` (default on,
applied as `ExoPlayer.PreloadConfiguration` read-ahead per D228), and `audioQuality`
(`LOSSLESS`/`COMPRESSED` enum, honored at queue-build time per D228). macOS's
`PlaybackSettings.swift` models only `skipFiller`. The audio-quality gap is already tracked —
ROADMAP.md's "Audio Quality, Gapless & Dead-Row Removal" entry (D228, #141) explicitly notes
"macOS control still open" — but the **gapless** gap is not currently called out anywhere in
ROADMAP.md or DECISIONS.md; it surfaced only from this model comparison.

**Recommended fix:** file (or extend the existing) follow-up ROADMAP item to add both an
`audioQuality` and a `gapless` preference to macOS's `PlaybackSettings`, mirroring Android's
model and D228's semantics (`AVQueuePlayer`'s own preload/buffering behavior would need
investigating for whether the same buffering-stall problem D228 solves for Media3 exists on
AVFoundation at all before assuming the fix transfers directly).

### Note (verified consistent, not a gap): neither platform models shuffle or repeat mode as persisted state

Grepping both trees turned up no `ShuffleMode`/`RepeatMode` type or persisted toggle on either
platform. Android's "shuffle" is a one-shot queue-building action
(`PlayerViewModel.shuffle(...)`), not a persisted UI-state field, and relies on Media3
`Player`'s own transport state rather than surfacing shuffle/repeat into `PlayerState`. macOS
has no equivalent at all. Since both platforms are equally absent here, this isn't a
cross-platform parity gap — it would only become one if a future feature request is built on
one side without the other.

### Note (architectural difference, not a bug): the two platforms' player/queue state hold different data at different layers

Android's `PlayerState` (`PlayerViewModel.kt`) is a ~25-field flattened snapshot of the
*current* track/show (title, artist, art, position, duration, etc.) exposed via `StateFlow`;
it does not hold the list of upcoming queue tracks — that list lives in Media3's
`MediaController`/`ExoPlayer`, outside the ViewModel's published state. macOS's `Player`
(`Player.swift`) is simultaneously the playback-engine wrapper *and* the published state
holder: it holds `tracks: [PlayableTrack]` (the actual queue array) and `currentIndex`
directly as `@Published` properties, so the full queue is part of macOS's UI-facing state in a
way it isn't on Android's. This reflects each platform's underlying player architecture
(Media3's `MediaController` as source of truth vs. a hand-rolled `AVQueuePlayer` wrapper) more
than model drift, but it means "queue state" is not a directly comparable shape between
platforms — a future feature that needs the live queue in the UI (e.g. a queue-reorder screen)
would be reading from a different place on each platform. No fix recommended; noting the shape
difference for whoever builds that next.

## Summary of recommended follow-ups (not actioned here — out of scope per #187)

1. Add `id`/`likedByUser` to Android's cross-backend `ShowSummary` and wire a show-level Like
   button on Android's Show Detail, matching macOS's D229.
2. Decide whether Android's cross-backend `PlayableTrack` should carry `likesCount`/
   `likedByUser`/`popularity` like macOS's does, or document that likes/popularity are
   intentionally raw-type-only on Android.
3. Decide whether Android's `Popularity` should decode per-window `plays`/`hours` like macOS's
   `RelistenPopularity` does, or confirm the flattened model is a deliberate scope decision.
4. Check for call sites of macOS's `Tag.notes`; either add it to Android's `TagRef` or drop it
   from macOS if unused.
5. Add `audioQuality` and `gapless` preferences to macOS's `PlaybackSettings`, extending the
   already-tracked D228/#141 "macOS control still open" follow-up to explicitly cover gapless
   too, and confirming whether AVFoundation needs an equivalent fix at all.
