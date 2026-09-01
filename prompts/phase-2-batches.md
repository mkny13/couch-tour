# Phase 2 — Batch Plan

Produced by [phase-2-plan.md](phase-2-plan.md)'s planning pass on 2026-08-31, and **approved by
Mike the same day** — see "Decisions taken" at the bottom, which the batches above already
reflect. The batches below have since been turned into working prompts:
**[phase-2-batch-prompts.md](phase-2-batch-prompts.md)** is what you hand to a worktree. This file
stays the reasoning and the audit behind them.

Baseline at the time of writing: `main` @ `5e6ad7b`, Android **463 tests / 0 failures**, macOS
**370 tests / 0 failures**, no open PRs, no stale branches.

---

## Step 0 — audit results (read this first; it changes the whole shape of Phase 2)

The plan prompt was right to insist on this. **Six of the eleven issues in scope are already
built.** Every row below was verified by reading the code, not by trusting DECISIONS.md — which
turned out to matter, because DECISIONS.md is wrong about three of them (see the next section).

| Issue | Feature | Real status |
|---|---|---|
| **#27** | FLAC streaming | **Done, both platforms.** Verify + close. |
| **#68** | Next Stop tour picker | **Done, both platforms.** Verify + close. |
| **#10** | Desktop cast | **Done (macOS).** Verify + close. |
| **#85** | Post-show next-stop prompt | **Already closed on GitHub.** ROADMAP still lists it. |
| **#60** | macOS Sparkle auto-updates | **Already closed on GitHub.** ROADMAP still lists it. |
| **#67** | Browse & filter by tag | **Android done. macOS: model only, no UI.** |
| **#21** | Trending & momentum browse | **Android done. macOS: model only, no UI.** |
| **#62** | Relisten show artwork | **Android done. macOS: model only, no UI.** |
| **#65** | Offline downloads | **Not started.** Nothing on either platform. |
| **#18** | Volume leveling | **Not started.** Nothing on either platform. |
| **#91** | Sortable search results | **Not started.** Nothing on either platform. |
| **#61** | Multi-level catalog cache | **Not started.** Still the single `@Volatile` artist list. |

Evidence for the "done" rows:

- **#27** — Android parses `flac_url` (`Relisten.kt:123`), plays it with `MimeTypes.AUDIO_FLAC`,
  and falls back to MP3 for Cast; macOS prefers `flacUrl` in `Player.swift:315` and badges it via
  `StatusPill.codec` (`NowPlayingInspector.swift:76`, `MiniPlayerView.swift:60`,
  `ShowDetailView.swift:72,177`).
- **#68** — Room `MIGRATION_8_9` (`Progress.kt:251`) + `TourPickerDialog`
  (`MainActivity.kt:1346`); GRDB `v9_artistTourPreferences` + `TourPickerSheet.swift`, wired into
  `HomeView.swift:17`.
- **#10** — `CastClient.swift`, `CastDiscovery.swift`, `CastRoutePicker.swift`,
  `AirRoutePicker.swift` all present and wired (D196).

---

## The headline finding: three "shipped" features never reached the macOS UI

D191 (#67 tags), D192 (#21 momentum), and D193 (#62 artwork) each claim macOS UI shipped.
**It did not.** In all three cases the pure model and its unit tests landed in `CouchTourKit`,
and nothing in `macos/CouchTour/` ever called them:

| Feature | In `CouchTourKit` (tested) | In the macOS app target |
|---|---|---|
| Tags (#67) | `Tag` (`Catalog.swift:67`), `filterShowsByTag` (`:251`), `filterTracksByTag` (`:261`) | **Zero references.** `grep -rn "Tag" macos/CouchTour` returns nothing. |
| Sort (#21) | `ShowSortOption` (`Catalog.swift:161`), `sortShows` (`:185`) | **Zero references.** `ShowsView.swift` is a plain unsorted `List`. |
| Artwork (#62) | `ArtworkPalette`, `proceduralPalette` (`Artwork.swift:38,171`) | **Zero references.** `ArtworkView.swift` still draws `.quaternary` + a `music.note` glyph. |

Android, by contrast, genuinely wired all three: tag chip rows at `MainActivity.kt:839-846` and
in `SearchResultsList` (`:1486-1502`), sort chips at `:821-829`, and `ShowArtwork` at six call
sites (`NowPlaying.kt:389`; `MainActivity.kt:984,2260,2345,2368,2769`).

**Why this slipped past CI.** `swift test` covers `CouchTourKit` only — the app target isn't in
the package and isn't built by `macos-tests.yml`. A pure function plus its test is therefore
fully green while no screen calls it. `TagTests.testShowSummarySortedByOption` passes today
against a sort no macOS user can reach. This is a structural gap in the verification story, not
a one-off slip, and it is worth naming in DECISIONS.md so the next macOS batch doesn't repeat it.

**This is the single highest-value work in Phase 2**: three user-visible features on the desktop,
built on already-written and already-tested logic. It is mostly view code.

---

## Batches

```
Batch 0 (docs/closeout, no code) ── can run immediately, gates nothing
                        │
        ┌───────────────┼───────────────────┐
        ▼               ▼                   ▼
   Batch 1          Batch 2A            Batch 4
   macOS wiring     Android             #61 cache
   #67 #21 #62      #91 #116 #90        (API layer)
   + #115           (MainActivity)
   (macOS views)
        │               │
        └───────┬───────┘
                ▼
            Batch 2B
            macOS parity for #91/#116/#90
                │
                ▼
            Batch 6
            #65 offline downloads
            (largest; own phase)

deferred out of the phase: #18 volume leveling
```

### Batch 0 — Audit closeout *(no application code)*

Free, and it should run first because it corrects the map everything else is read from.

- Verify and close **#27**, **#68**, **#10** with a comment pointing at the implementing
  commit/decision.
- ROADMAP.md: move #27, #68, #10, #62, #67, #21 out of the Phase 2 table as their batches land;
  drop **#85** and **#60**, which are already closed but still listed.
- DECISIONS.md: a new entry recording that D191/D192/D193 overstated macOS coverage, what
  actually shipped, and the `swift test`-doesn't-cover-the-app-target reason. Per CLAUDE.md this
  is a new superseding entry, not an edit to the originals.
- README.md: Android test count is **463**, not 461.

**Out of scope:** any change under `app/`, `macos/`, `sync/`.

### Batch 1 — Wire the three shipped models into the macOS UI (#67, #21, #62) *(macOS only)*

The highest-payoff batch. All logic exists and is tested; this is view work.

- **#21** — add a sort control to `ShowsView.swift` driving the existing `sortShows(_:by:)`.
  Android's chip row (`MainActivity.kt:821-829`) exposes Date / Top rated / Trending 48h /
  Hot 7d / Popular 30d; `ShowSortOption` already has all seven cases with `displayName`s. A
  toolbar `Menu` or `Picker` fits macOS better than Android's chips — pick one and say why.
- **#67** — tag filter UI in `ShowsView.swift` and `SearchView.swift`, calling the existing
  `filterShowsByTag` / `filterTracksByTag`. Android's shape: an "All" entry plus the distinct
  tags present in the current results, cleared when the tag leaves the result set.
- **#62** — `ArtworkView.swift` renders the procedural palette instead of the `music.note`
  placeholder when `url` is nil or the load fails. **Decided: a seeded gradient plus the artist
  monogram — not Android's full cassette.** `CouchTourKit`'s `ShowArtworkGenerator` already
  supplies everything needed: `palette(forArtist:date:)` (`Artwork.swift:52`),
  `monogram(for:)` (`:211`), and `dateBadge(from:)` (`:263`). So this is a gradient fill plus a
  `Text` overlay, not a `Canvas` port — the whole generator surface is written and tested, just
  uncalled. `ArtworkView` needs a seed, which means threading
  artist/date through its call sites; keep `@ScaledMetric` and `accessibilityHidden(true)` as
  they are. Revisit if it reads thin at 150pt on the Continue Listening cards.

**Watch for:** `ShowsView` currently has no `@State` for filters at all, and its `load()` resets
`loadState` wholesale — sort/filter must be view-state applied to loaded results, not a refetch.
Use `DesignSystem/` components (D204) rather than new inline styling.

**Verify:** `swift test` for anything pushed down into the Kit; `xcodegen generate` +
`xcodebuild` for the app target; then **launch and click** — this batch is almost entirely view
code, so the build passing proves very little. See the interactive-verification note below.

**Out of scope:** Android (all three are done there); the `ShowsView` → `ShowDetailView`
navigation contract.

### Batch 2A — List sort & filter, Android (#91, #116, #90)

Three Feedback-filed items that are one coherent piece of work: *let me reorder and narrow a
long list.* None is in the ROADMAP table; #91 is, #116 and #90 are not.

- **#91** — sort Universal Search results by date or phish.in like count. `SearchResultsList`
  (`MainActivity.kt:1466`) already has an artist chip row and a tag chip row; this is a third
  control. Decide whether it sorts shows only, or tracks too.
- **#116** — `ArtistsScreen` (`MainActivity.kt:736`) has neither sort nor filter; it renders
  `mergeArtists` order (most-recorded first). Mike asked for a popular/alphabetical toggle plus a
  filter field. Favorites stay pinned in their own section regardless of sort.
- **#90** — no search field on either playlist screen (`PlaylistScreen` `:1959`,
  `LocalPlaylistScreen` `:2076`). The `TextField`s in the macOS playlist views are name-entry for
  rename, not search — don't mistake them for existing support.

**Prefer pure, testable helpers** (`sortArtists`, `filterArtists`, a search-hit sorter) in
`Catalog.kt` so Batch 2B can reuse them and both get real unit tests, rather than sorting inline
in a composable where only a manual pass can check it.

### Batch 2B — macOS parity for #91, #116, #90 *(gated on 2A and Batch 1)*

Gated for a concrete reason, not ceremony: **2B and Batch 1 both edit `SearchView.swift`**, and
2B should reuse whatever pure helpers 2A lands. Running 2A (Android) in parallel with Batch 1
(macOS) is safe — zero file overlap — and is the recommended parallelization.

Surfaces: `SearchView.swift`, `ArtistsView.swift`, `LocalPlaylistView.swift`,
`LocalPlaylistsView.swift`. `ArtistsView` keeps its Favorites/Artists two-section split.

### Batch 3 — macOS Continue Listening context menu (#115) *(tiny, independent)*

`ResumeCardView` (`HomeView.swift:538`) has no `.contextMenu`, so there is no way to remove or
complete a row on the desktop. Android has exactly this as a long-press `DropdownMenu`
(`MainActivity.kt:2377-2407`: open / mark completed / remove). The precedent is already in the
same file — the favorited-artist card at `HomeView.swift:304` uses `.contextMenu`.

Apply to both `ResumeCardView` and `ListeningView`'s rows so the shelf and the full screen agree
(the D200/#98 split already established that pairing).

**Decided: this runs inside Batch 1's worktree, not its own.** The `.contextMenu` pattern already
exists 200 lines up the same file, and a third parallel worktree for one small macOS fix isn't
worth the coordination. It stays a separate section here because it's an independent change with
its own issue and should be a distinct commit.

### Batch 4 — Multi-level catalog cache (#61) *(both platforms, API layer)*

Today: one `@Volatile cachedArtists` list on Android (`Relisten.kt:439`) and one actor-private
`cachedArtists` on macOS (`RelistenAPI.swift:768`). Periods, shows, and show detail all re-fetch
on every screen entry — the scope cut O4 took deliberately.

**Genuinely parallel with the UI batches** — it lives in `Relisten.kt`/`Catalog.kt` and
`RelistenAPI.swift`, which none of Batches 1–3 touch. The plan prompt suspected #61 might gate
#67/#21/#62/#91; **it does not** — those read `MusicSource`'s results, and caching is invisible
behind that seam.

Decisions this batch owes: in-memory vs. on-disk (a Room/GRDB table means migrations, and
CLAUDE.md is strict here); TTL and invalidation, including how it interacts with
`NextStop.cacheKey`'s existing preference-aware invalidation (D190); and memory ceiling for a
~200-artist catalog. **Recommend in-memory with a TTL first** — it is reversible, needs no
migration, and O4's original reasoning still holds.

### ~~Batch 5~~ — Volume leveling (#18) — **deferred out of Phase 2**

**Decided 2026-08-31: #18 is deferred out of Phase 2 entirely** and revisited after downloads
(#65). The reasoning below is kept because it's the research this pass did and it should not have
to be redone when #18 comes back.

A useful consequence: the playback-pipeline collision this plan flagged between #18 and #65 is
now moot, so **#65 no longer has to sequence around it** and owns `MediaItems.kt` / `Player.swift`
alone.

It sounds small and isn't. There is no gain/loudness infrastructure on either platform
today: Android sets `AudioAttributes` and a flat `player.volume`
(`PlaybackService.kt:124,142,149`) with no `AudioProcessor` chain; macOS keeps a persisted app-level `volume` (`Player.swift:37`) and no
`AVAudioMix`.

The hard part is *where the loudness number comes from*. Neither phish.in nor Relisten/archive.org
serves reliable ReplayGain tags, so the options are real analysis (decode-ahead loudness
measurement, expensive on mobile), a platform normalizer (Android `LoudnessEnhancer` /
`DynamicsProcessing`; AVFoundation has no direct equivalent), or a per-source manual trim the
user sets. These differ enormously in cost and quality — which is why this was never a
single-prompt batch. When it returns, it should open with a C1-style design pass producing a
recommendation Mike picks from, then an implementation prompt: the two-step that worked for #102.

Note also the issue's own framing: source/show-level matching, explicitly *not* per-track
normalization.

### Batch 6 — Offline downloads (#65) *(largest; treat as its own phase)*

The biggest item in Phase 2 by a wide margin, and the only one that is genuinely a new subsystem:
a download manager with queue and retry, a storage location with an eviction/management UI, a
schema migration on **both** platforms (Room is at v9, GRDB at v9), playback resolution that
prefers a local file, and per-artist/show/track UI affordances.

Two traps worth naming now:

1. **Cast interaction.** A local file cannot be streamed to a Cast receiver — the same class of
   problem D187 solved for FLAC by rewriting the URL at the `CastItemConverter` boundary. Whatever
   #65 does must keep casting working by falling back to the remote URL.
2. **`MediaItems.kt` / `Player.swift` ownership.** #65 changes how a playable URL becomes a
   player item. With #18 deferred out of the phase, nothing else contends for these files — but
   if #18 is ever revived alongside it, they must not run in parallel worktrees.

Recommend sequencing this **last** and giving it its own multi-batch plan rather than a single
prompt.

---

## Platform sequencing

Repo history is Android-first, then a macOS parity pass referencing the Android decision
(#17 → #37, folded into #25). That still holds where both platforms start level — so **#91, #116,
#90 go Android first (2A), macOS second (2B)**, with 2B reusing 2A's pure helpers.

**Batch 1 inverts it, and should.** Android is already finished for #67/#21/#62; the Android
decisions are made and recorded. Batch 1 is a pure catch-up pass, and Android's implementation is
the spec.

Batches 4 and 6 are both-platform from the start because their seams (`MusicSource`, the
download store) have to agree across clients or they diverge in ways that are painful to
reconcile later.

---

## Recommended running order

1. **Batch 0** — closeout and doc corrections. Free, and fixes the map. Now also carries the
   #18 deferral into ROADMAP.md.
2. **Batch 1 (macOS, including #115) ∥ Batch 2A (Android) ∥ Batch 4 (#61)** — three parallel
   worktrees, no file overlap.
3. **Batch 2B** — macOS parity, after 1 and 2A land.
4. **Cut a beta** — per CLAUDE.md, at the end of the batch. Batches 1–2B are almost all macOS
   view work, which only real use will validate.
5. **Batch 6 (#65)** — its own phase, and with #18 deferred it owns the playback pipeline
   uncontended.

---

## Decisions taken (Mike, 2026-08-31)

All five open questions are answered; the batches above already reflect them.

1. **The half-shipped framing stands.** Batch 1 is planned as written — #67/#21/#62 are
   Android-complete and macOS-pending, and wiring the existing tested models into the macOS UI
   is the phase's highest-value work.
2. **macOS artwork (#62): seeded gradient plus artist monogram**, not full cassette parity with
   Android. `ShowArtworkGenerator` already supplies the palette, monogram, and date badge.
3. **#115, #116, and #90 fold into Phase 2.** #116/#90/#91 are one "sort/filter a long list"
   batch; #115 rides along in Batch 1's macOS worktree.
4. **#18 (volume leveling) is deferred out of Phase 2**, to be revisited after #65. This was the
   one answer that went against the recommendation in this plan's first draft — the
   recommendation was a design pass inside the phase. Deferring is defensible on the same
   evidence: the loudness-source question is genuinely unsolved, and #18 was the only item whose
   scope couldn't be pinned down without further research. It also frees the playback pipeline
   for #65.
5. **#115 runs inside Batch 1's worktree**, as its own commit rather than its own worktree.

**What Batch 0 now owes ROADMAP.md**, on top of the closeout already listed: move #18 out of the
Phase 2 table into a later phase, with a line recording that the deferral was deliberate and why,
so it doesn't read as an oversight.

---

## Observations noted in passing (no action taken)

- `TrackedTourStore` (`ProgressStore.swift:302`) registers a `v9_artistTourPreferences` migration
  identical to `ProgressStore`'s, and is constructed **only** by one test
  (`ProgressStoreTests.swift:388`) — the app uses `ProgressStore.saveTourPreference` directly.
  Harmless today and the test passes; flagged as dead-ish code per CLAUDE.md rather than removed.
- GRDB migrations jump v6 → v7 → v9 with no v8, matching Android's Room versions rather than
  numbering densely. Intentional per D83; noting it so it isn't "fixed."
- `local.properties` is gitignored and machine-specific, so a fresh worktree cannot run
  `./gradlew testDebugUnitTest` until it is recreated:
  `echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties`. Worth a line in CLAUDE.md's
  Android build section.
- **Interactive macOS verification is still walled off.** D204 already flagged this: every ad-hoc
  reinstall invalidates the keychain ACL, and the resulting app-modal prompt needs Mike's password
  ("Always Allow"). Batches 1, 2B, and 3 are almost entirely view code whose verification is
  clicking. Answer that prompt by hand once before starting them, or those batches ship on a
  build-passes signal that proves very little.
