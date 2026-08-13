# Multi-artist support via Relisten — working plan

Working document for the in-progress branch `claude/multi-artist-relisten-archive-2kxmzh`.
Delete or fold into [DECISIONS.md](DECISIONS.md) when the work lands.

## Goal

Play shows by artists other than Phish — Grateful Dead, Widespread Panic, and the ~200 others
on [Relisten](https://relisten.net) — while keeping **resume** and **history** working. Likes,
playlists, and login are phish.in account features and stay Phish-only.

## Decisions taken up front

| # | Decision | Why |
|---|---|---|
| 1 | **Relisten**, not archive.org directly | archive.org's `/metadata/{id}` is free-text taper filenames — no setlists, no song identity, no set boundaries, no year index. Relisten *is* the normalization layer over archive.org. Going raw means rebuilding it, for worse data. |
| 2 | **phish.in stays the Phish backend** | Relisten carries Phish, but sourced upstream from phish.in and without waveforms, cover art, likes, playlists, or login. Keeping phish.in means zero regression and zero churn in the 110 existing tests. |
| 3 | **Auto-pick the best tape, with a switcher** | Relisten averages ~9 recordings per Dead show. Default to the best-rated; offer a sheet to switch. |
| 4 | **First cut is browse → play → resume → history** | Mirrors the original MVP (D5). Search, songs, and venues for the new artists are deferred. |

## The one genuinely new concept: recordings

phish.in is one-audio-per-show. Relisten is many — ~9 tapes per Dead show (2,080 shows,
18,085 sources). **Different tapes of the same date have different track counts and
boundaries**, so progress must be keyed per-recording. Resuming a stored `trackIndex` against
a different tape lands mid-jam on the wrong song. This is why the source UUID goes in the
queue key.

## What we get for free

`Progress.queueKey` is already a namespaced string (D6: `show:1997-11-17`,
`playlist:some-slug`) *specifically* so new queue kinds could slot in — D31 cashed that in
once for playlists with no schema change. A `relisten:` prefix cannot collide with existing
rows, so **no migration is needed**. `Progress` also stores `title`/`subtitle`/`artUrl`/
`trackTitle` denormalized, so History and "Continue listening" render rows from any backend
with no network call and no knowledge of the backend. Only *resuming* dispatches on the prefix.

## Reference — facts not to re-derive

### Relisten API

Base `https://api.relisten.net/api`. No key, no auth, no rate limit documented.

| Purpose | Path |
|---|---|
| Artist list | `GET /v3/artists` |
| Years for an artist | `GET /v3/artists/{artistUuidOrSlug}/years` |
| Shows in a year | `GET /v3/artists/{artistUuid}/years/{yearUuid}` → `YearWithShows` |
| Show + all tapes | `GET /v2/artists/{artistIdOrSlug}/shows/{date}` → `ShowWithSources` |

The last one keys a show by **artist slug + date string** — exactly the identity model this
app already uses (`PhishInApi.show(date)`, route `show/{date}`, key `show:$date`).

Track nesting: `Source` → `sets: SourceSet[]` (`index`, `name`, `is_encore`) →
`tracks: SourceTrack[]` (`uuid`, `title`, `track_position`, `duration`, `mp3_url`, `flac_url`).

### Verified against the live API

Checked against `grateful-dead/1977-05-08` (Cornell) and `/v3/artists` on 2026-08-13. These
replace earlier guesses — several of them contradicted what seemed obvious.

- **`duration` is seconds.** "Minglewood Blues" = `325` (5:25); the source total is `10059`
  (2h47m). Everything in this app is milliseconds (`Api.kt:74`, `Format.fmt`,
  `MediaItems.kt`), so the mapper multiplies by 1000. Pin it with a test — a duration
  1000× off is exactly the kind of thing that looks fine until someone opens the scrubber.
- **Track counts really do differ per tape.** Cornell's ten sources run 20, 21, 21, 25, 21,
  18, 21, 22, 19, 18. This is the empirical case for putting the source in the queue key: a
  stored `trackIndex` of 22 means different music depending on the tape, and on three of
  these it doesn't exist at all.
- **Sources arrive pre-sorted by `avg_rating_weighted`, descending.** The default tape is
  therefore just `sources[0]`. **Do not tie-break on `is_soundboard`** — Cornell's soundboard
  ranks 4th (8.212 against 8.260), so preferring it would override Relisten's own ranking
  and hand the user a worse-rated recording.
- **`features` on each artist is a capability model, and it beats guessing:**
  - `features.sets` is **false for Grateful Dead**, true for Phish. Dead sources carry a
    single wrapper set literally named "Set", so set headings must be suppressed when this
    is false — otherwise every Dead show renders one meaningless "Set" divider.
  - `features.multiple_sources` is **false for Phish** (1884 shows against 1888 sources),
    which independently confirms decision 2: Relisten's Phish has no tape to choose.
  - Also present: `can_have_flac`, `track_durations`, `eras`, `tours`, `ratings`,
    `setlist_data_incomplete`.
- 226 artists. `mp3_url` points at `archive.org/download/...`, so playback needs archive.org
  reachable from the device.

`ArtistRef` therefore needs `hasSets` and `hasMultipleSources` — an additive change to the
model already landed in P2.

### Traps — each fails silently if missed

1. **Seconds against milliseconds**, as above.
2. **`mp3_url` is nullable.** Same shape as D12 — filter to usable URLs, and filter
   *identically* in the UI and the queue builder or the stored `trackIndex` means two things.
3. **No waveforms, usually no cover art.** `waveformUrl`/`artUrl` null; scrubber falls back
   to plain, and `RowItem` already handles a null art slot (D34).
4. **`.setArtist("Phish")` is hardcoded** in `MediaItems.kt`. That feeds the MediaSession the
   official Last.fm app scrobbles from (D50, D70). Left alone, **every Dead show scrobbles as
   Phish.** This is a live correctness bug the change must fix.

### Environment

The cloud container this started in had `dl.google.com` denied by its network policy, so
there was no Android SDK and `./gradlew` could not run — the same wall D73 hit. That is why
`.github/workflows/test.yml` exists, and it is worth keeping regardless: it means nothing
reaches `main` unverified.

Network access has since been opened, so the SDK installs and the suite runs in-container
after all. On a fresh container: install `cmdline-tools` from `dl.google.com`, then
`sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"`, and point
`local.properties` at it with `sdk.dir=<sdk>` (gitignored, never commit it). Note
`JAVA_TOOL_OPTIONS` carries proxy and truststore flags that Gradle echoes on every line —
clear it for readable output. A device still cannot be attached, so the checks under
Verification below remain a local-machine job.

## Phases

Each phase is one commit, small enough to verify on its own. Tests are written before the
code they cover.

- [x] **P0 — CI runs the tests.** Add a `test` job (`testDebugUnitTest`) to
      `.github/workflows/`, triggered on `pull_request` and on pushes to this branch. Without
      this nothing below can be verified at all.
- [x] **P1 — `Queue.kt`: the recording queue key.** `QueueKind.RECORDING`,
      `recordingQueueKey(artistSlug, date, sourceId)` → `relisten:<slug>/<date>/<uuid>`.
      Inner delimiter is `/`, not `:`, to sidestep the first-colon-only ambiguity
      `parseQueueKey` and `BrowseNode.parse` both work around. Existing prefixes untouched.
      *Tests first, in `QueueTest`.*
- [x] **P2 — `Catalog.kt`: domain model + `MusicSource` seam + `PhishInSource` adapter.**
      Narrow: only what browse → play needs. `Api.kt` is not rewritten.
- [x] **P2b — `Progress.kt`: the `artist` column (schema v6).** Not in the original plan;
      added when O2 was overruled. `MIGRATION_5_6` backfills existing rows to Phish, and
      `artists()` / `historyFor()` are what the column is for.
- [x] **P3 — `Relisten.kt`: DTOs and parsing.** Fixtures captured live and trimmed into
      `app/src/test/resources/fixtures/` (`relisten_artists.json`: Phish, Grateful Dead,
      Widespread Panic; `relisten_years.json`: Dead years; `relisten_year.json`: six Dead
      shows around 5/8/77; `relisten_show.json`: Cornell's first four sources, full track
      lists). `ArtistRef` gained `hasSets`/`hasMultipleSources`. 13 tests in
      `RelistenParsingTest` cover the seconds→ms conversion, set flattening in index order,
      dropping null/blank-`mp3_url` tracks, `sources[0]` as the default tape (and that an
      explicit or unknown recording id is handled correctly), and that set names are
      suppressed for `hasSets = false`. No `MusicSource` implementation yet — that needs the
      request layer, which is P4.
- [x] **P4 — `Relisten.kt`: request shapes, plus the `MusicSource` seam.** `RelistenApi` —
      `artists()`, `years(uuid)`, `year(artistUuid, yearUuid)`, `show(slug, date)` — no key,
      no auth. `RelistenRequestTest` pins the one easy-to-get-wrong shape: the per-show
      endpoint stays on `/v2` (`api/v2/artists/{slug}/shows/{date}`) while everything else
      moved to `/v3`. Wiring `RelistenCatalogSource : MusicSource` turned out cheap enough to
      fold in here rather than wait for P6/P7: it's the P3 mapping functions plus `RelistenApi`
      behind the seam P2 defined, with the one cache O4 called for. Confirmed live — not
      assumed — that `/v3/artists/{slug}/years` and `.../years/{yearUuid}` both accept the
      slug directly, so `ArtistRef.id` (already the slug) needs no uuid-lookup round trip.
      (The DTO for one tape is `RelistenSource`; the `MusicSource` implementation is
      `RelistenCatalogSource` — same word, deliberately different names to keep them apart.)
- [x] **P5 — `MediaItems.kt`: per-artist metadata + `recordingTrackItems`.** `QueueInfo`
      gained `artist` (defaults to `"Phish"`, so every existing phish.in call site needed no
      change); `mediaItem` and the new `recordingMediaItem` both call through a private
      `coreMediaItem(...)`, which is where `.setArtist("Phish")` actually lived — fixing the
      hardcode there fixes it for every caller at once, phone and Auto tree alike (D73).
      `recordingTrackItems(detail: ShowDetail)` builds the queue for one Relisten tape; a
      show with no chosen recording still builds a playable, unresumable queue rather than
      refusing outright — the same tradeoff D42 makes for shuffle. 6 tests in
      `MediaItemsTest`.
- [ ] **P6 — `PlayerViewModel.kt`: resume dispatch + `playRecording`.**
- [ ] **P7 — `MainActivity.kt`: routes and screens.** `artists`,
      `artist/{backend}/{id}`, `artist/{backend}/{id}/{period}`,
      `recording/{backend}/{artist}/{date}?src=`. Home gains an "Artists" row; the Phish path
      does not move. Reuse `RowItem`, `SectionHeader`, `loadOnce`, and the set-grouping helper.
      `openQueueKey` gains the `relisten:` case.
- [ ] **P8 — `Browse.kt` / `PlaybackService.kt`: Android Auto.** Artists node above Years,
      `BrowseNode.Recording`. `Resume` already wraps the `queueKey` verbatim (D73), so
      Continue Listening picks up Relisten rows with no change.
- [ ] **P9 — Docs.** New DECISIONS.md iteration; README feature lines and test count.

## Open questions — conservative choice taken, flagged for discussion

**O1 — resolved.** Fixtures were going to be reconstructed from Relisten's OpenAPI schema
because the API host was unreachable, which would have been weaker than the existing
fixtures — their whole value per D35 is that they are real responses. Network access is now
open, so fixtures get captured and trimmed from the live API like every other one in
`app/src/test/resources/fixtures/`, and the duration unit and source ordering are confirmed
facts rather than assumptions. See "Verified against the live API" above.

**O2 — overruled, and rightly. The artist is its own column (schema v6).**
The conservative choice had been to fold the band name into the denormalised `subtitle` and
avoid touching the one table the app exists to protect. Mike wants history filtered and
grouped by artist, and that makes `subtitle` a kludge: grouping off a display string means
splitting on a separator that venue names are free to contain, so the first
`Barton Hall · Ithaca, NY` breaks it. Done properly instead — `MIGRATION_5_6`, schema v6,
`6.json` committed, and `ProgressDao.artists()` / `historyFor(artist)` to make the column
worth having.

Existing rows are backfilled to `'Phish'`. **That is not the guess D21 declined to make:**
until the second backend existed, phish.in was the only thing the app could play, so every
row already in the table is Phish, playlist rows included. D21's case differed in kind —
inferring `finished` needed a track duration the table has never stored.

The writer takes the artist from `MediaMetadata.artist`, which items already publish for
external scrobblers (D50), so there is no second copy to keep in step and the field becomes
correct for Relisten automatically once P5 stops hardcoding `"Phish"`.

**O3 — MP3 only; FLAC ignored.**
Relisten serves `flac_url` alongside `mp3_url`. Media3 plays FLAC, but `MimeTypes.AUDIO_MPEG`
is hardcoded for Cast (D59) and the stock receiver (D65) expects progressive MP3. Taking
MP3-only keeps the Cast path working unchanged. Revisit as its own piece of work.

**O4 — No catalog caching beyond the artist list.**
There is no caching anywhere today (`loadOnce` re-fetches on every screen entry). A ~200-entry
artist list re-fetched on every back-navigation is the one case worth a single `@Volatile`
cached list. A real catalog cache stays out of scope.

**O5 — Relisten's operators have not been approached.**
CLAUDE.md records that phish.in's maintainer permitted the API use on condition it is not
branded official. That says nothing about Relisten, who are a different operator. Worth an
approach on the same terms before a store release. Their `upstream_sources` carry a
`credit_line` field — surfacing it on the show screen is the natural attribution. Also: more
band names widens the Play Store trademark surface, though the existing rule (band name out
of the title, descriptive use in the body) covers it.

## Verification

CI (P0) covers the unit tests. The rest needs a device:

1. Artists → Grateful Dead → 1977 → 5/8/77 → play.
2. Kill the app mid-track; reopen. The show is in "Continue listening", labelled with the
   band, and resumes at the right track *and* second.
3. Switch tapes from the recordings sheet. The new tape gets its **own** resume point and the
   original tape's position survives — the whole reason the source UUID is in the key.
4. Phish path untouched: login, My Shows, a playlist with excerpts, waveform scrubber, likes.
5. `adb shell dumpsys media_session` on a Dead track — artist reads "Grateful Dead", not
   "Phish". This is what the Last.fm app scrapes (D50).
6. Android Auto: Artists node browses, and a Relisten row appears under Continue Listening.
