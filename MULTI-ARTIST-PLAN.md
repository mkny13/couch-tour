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

### Traps — each fails silently if missed

1. **`duration` is seconds on Relisten, milliseconds on phish.in.** `Track.duration` is ms
   everywhere here (`Api.kt:74`, `Format.fmt`, `MediaItems.kt`). Convert in the mapper, pin
   with a test. **Unverified against a live response — see open question O1.**
2. **`mp3_url` is nullable.** Same shape as D12 — filter to usable URLs, and filter
   *identically* in the UI and the queue builder or the stored `trackIndex` means two things.
3. **No waveforms, usually no cover art.** `waveformUrl`/`artUrl` null; scrubber falls back
   to plain, and `RowItem` already handles a null art slot (D34).
4. **`.setArtist("Phish")` is hardcoded** in `MediaItems.kt`. That feeds the MediaSession the
   official Last.fm app scrobbles from (D50, D70). Left alone, **every Dead show scrobbles as
   Phish.** This is a live correctness bug the change must fix.

### Environment

`dl.google.com` is denied by this environment's network policy (403 at the egress proxy), so
there is **no Android SDK and `./gradlew` cannot run locally** — the same wall D73 hit.
`api.relisten.net` and `archive.org` are blocked too. Verification therefore runs in GitHub
Actions; Phase 0 exists to make that possible.

## Phases

Each phase is one commit, small enough to verify on its own. Tests are written before the
code they cover.

- [ ] **P0 — CI runs the tests.** Add a `test` job (`testDebugUnitTest`) to
      `.github/workflows/`, triggered on `pull_request` and on pushes to this branch. Without
      this nothing below can be verified at all.
- [ ] **P1 — `Queue.kt`: the recording queue key.** `QueueKind.RECORDING`,
      `recordingQueueKey(artistSlug, date, sourceId)` → `relisten:<slug>/<date>/<uuid>`.
      Inner delimiter is `/`, not `:`, to sidestep the first-colon-only ambiguity
      `parseQueueKey` and `BrowseNode.parse` both work around. Existing prefixes untouched.
      *Tests first, in `QueueTest`.*
- [ ] **P2 — `Catalog.kt`: domain model + `MusicSource` seam + `PhishInSource` adapter.**
      Narrow: only what browse → play needs. `Api.kt` is not rewritten.
- [ ] **P3 — `Relisten.kt`: DTOs and parsing.** Fixtures in
      `app/src/test/resources/fixtures/`. *Tests first, in `RelistenParsingTest`.*
- [ ] **P4 — `Relisten.kt`: request shapes.** MockWebServer via `internal var baseUrl`,
      restored in `@After`, same pattern as `ApiRequestTest`. *Tests first.*
- [ ] **P5 — `MediaItems.kt`: per-artist metadata + `recordingTrackItems`.** Both builders
      converge on the same private `mediaItem(...)` so the phone and the Auto tree still
      produce byte-identical queues (the property D73 relies on). *Tests first.*
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

**O1 — Relisten fixtures are built from the published OpenAPI schema, not captured live.**
`api.relisten.net` is egress-blocked from this environment, so the JSON fixtures are
constructed from Relisten's schema definitions (via the `relisten-mobile` repo) rather than
being trimmed real responses. That is weaker than the existing fixtures, whose whole value
per D35 is that they are real. **The duration unit (seconds vs ms) and the default source
ordering both need checking against a live response before this ships.**

**O2 — No schema migration; the artist goes in `subtitle`.**
The structured option is a new `artist` column (schema v6). The conservative option is to
fold the band name into the existing denormalized `subtitle` — no migration, no new identity
hash in `MigrationTest`, no risk to the one table the app exists to protect. Taken the
conservative option. Revisit if history ever needs filtering or grouping by artist.

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
