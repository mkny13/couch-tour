# Phase 2 — Batch Prompts

Working prompts for the batches approved in [phase-2-batches.md](phase-2-batches.md) on
2026-08-31. That file is the reasoning and the audit; this one is what you hand to a worktree.

| Batch | Issues | Platform | Status |
|---|---|---|---|
| **0** | #27, #68, #10 closeout + doc corrections | none (docs) | Ready |
| **1** | #67, #21, #62, #115 | macOS | Ready |
| **2A** | #91, #116, #90 | Android | Ready |
| **2B** | #91, #116, #90 | macOS | **Gated** on 1 + 2A |
| **4** | #61 | Android + macOS | Ready |
| **6** | #65 | Android + macOS | **Needs its own planning pass** — see the note at the bottom |

**#18 (volume leveling) is deferred out of Phase 2** and has no prompt here, by decision on
2026-08-31. See phase-2-batches.md's "Decisions taken".

---

## Standing rules for every batch below

Each of these is a fresh worktree off `main`. Every batch ends the same way:

- Run the suites that apply. Android: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest`
  — and note that `local.properties` is gitignored, so a fresh worktree needs
  `echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties` before Gradle will run at all.
  macOS: `cd macos/Packages/CouchTourKit && swift test`, plus
  `cd macos && xcodegen generate && xcodebuild -project CouchTour.xcodeproj -scheme CouchTour -configuration Debug -destination 'platform=macOS' build`
  for anything touching the app target.
- Baseline as of `1b442aa`: **Android 463 tests, macOS 370 tests**, both clean. README states the
  Android count and is currently stale at 461 — Batch 0 fixes that; every other batch updates it
  if its own work moves the number.
- Log the work in `DECISIONS.md` under a new iteration with new `Dnn` identifiers. Where a
  decision reverses an earlier one, add a superseding entry — never edit history.
- Open a PR, let CI finish, self-review the actual diff, merge, then delete the branch **and**
  remove the worktree.

**A separate session is currently editing
`macos/Packages/CouchTourKit/Sources/CouchTourKit/ProgressStore.swift` and
`macos/Packages/CouchTourKit/Tests/CouchTourKitTests/ProgressStoreTests.swift`** (removing a dead
`TrackedTourStore`). Stay out of both files. If your `swift test` count doesn't match the 370
baseline, that's why — check whether those two files moved before assuming you broke something.

---

## Batch 0 — Audit closeout and doc corrections

> Work in a fresh worktree off `main`. **This batch changes no application code at all** — nothing
> under `app/`, `macos/`, or `sync/`. It corrects the record, and it should land before the other
> batches so they're planned against an accurate map.
>
> An audit on 2026-08-31 ([phase-2-batches.md](phase-2-batches.md)) found that six of the eleven
> issues in Phase 2's scope were already built. Three of them are fully done and simply never got
> closed; two are already closed but still listed in the roadmap; and three shipped on Android
> only while `DECISIONS.md` claims they shipped on both platforms.
>
> **1. Verify and close #27, #68, #10.** Confirm each against the code before closing — don't take
> this prompt's word for it any more than the last pass took DECISIONS.md's:
>
> - **#27 (FLAC)** — `Relisten.kt:123` parses `flac_url`; Android plays it with
>   `MimeTypes.AUDIO_FLAC` and rewrites to MP3 for Cast in `Cast.kt`; macOS prefers it at
>   `Player.swift:315` and badges it via `StatusPill.codec`. Shipped in D187/D189.
> - **#68 (Next Stop tour picker)** — Room `MIGRATION_8_9` (`Progress.kt:251`) plus
>   `TourPickerDialog` (`MainActivity.kt:1346`); GRDB `v9_artistTourPreferences` plus
>   `TourPickerSheet.swift`. Shipped in D190.
> - **#10 (desktop cast)** — `CastClient.swift`, `CastDiscovery.swift`, `CastRoutePicker.swift`,
>   `AirRoutePicker.swift`. Shipped in D196.
>
> Close each with a comment naming the decision and commit that implemented it, so the trail is
> readable later.
>
> **2. Correct `ROADMAP.md`.** Drop **#85** and **#60** from the Phase 2 table — both are already
> closed on GitHub. Move #27, #68, and #10 into "Completed Milestones". Move **#18** out of Phase 2
> into a later phase **with a line saying the deferral was deliberate and why** (the loudness
> source is unsolved; revisit after #65) — without that line it reads as an oversight and someone
> will "fix" it back. Update the mermaid diagram and the Phase 2 table to match, and point the
> phase at [phase-2-batch-prompts.md](phase-2-batch-prompts.md) the way Batch 4 pointed at its own
> prompts file.
>
> **3. Add a `DECISIONS.md` entry — this is the substantive part.** D191 (#67 tags), D192 (#21
> momentum), and D193 (#62 artwork) each state that macOS UI shipped. It did not. In all three the
> pure model and its tests landed in `CouchTourKit` and nothing in `macos/CouchTour/` ever called
> them: `ShowsView.swift` is a plain unsorted `List`, `ArtworkView.swift` draws a `music.note`
> placeholder, and `grep -rn "Tag" macos/CouchTour` returns nothing.
>
> Write a superseding entry recording what actually shipped, and — the part worth having on the
> record — **why CI never caught it**: `swift test` covers the `CouchTourKit` package only. The app
> target isn't in the package and isn't built by `macos-tests.yml`, so a tested pure function that
> no screen calls is fully green. `TagTests.testShowSummarySortedByOption` passes today against a
> sort no macOS user can reach. Say plainly that a green `swift test` is not evidence a macOS
> feature is reachable, and that macOS UI work needs a launch-and-click pass.
>
> **4. `README.md`:** the Android test count is **463**, not 461.
>
> **Out of scope:** the macOS wiring itself (that's Batch 1), and any change under `app/`,
> `macos/`, or `sync/`.
>
> **Verify:** no code changed, so there's nothing to test — but confirm `git diff --stat` touches
> only `.md` files before opening the PR.

---

## Batch 1 — Wire the shipped models into the macOS UI (#67, #21, #62) and add #115

> Work in a fresh worktree off `main`. **This is the highest-value batch in Phase 2.** Three
> features already exist as tested pure logic in `CouchTourKit` and are simply not called by any
> macOS view. You are writing view code against an API that is already written and already green.
>
> Read [phase-2-batches.md](phase-2-batches.md) first for the audit that established this.
>
> **Another worktree may be running Batch 2A (Android) in parallel** — it works only in
> `app/`, so there is no overlap. **Batch 2B is gated on this batch** because it also edits
> `SearchView.swift`; don't start 2B's work here. A third session is editing
> `CouchTourKit`'s `ProgressStore.swift` / `ProgressStoreTests.swift` — stay out of both.
>
> Android is the spec for all three features: it implemented them for real and those decisions are
> recorded. Don't re-derive them.
>
> **1. #21 — sort control on `ShowsView.swift`.** `ShowSortOption` (`Catalog.swift:161`) and
> `sortShows(_:by:)` (`:185`) already exist with all seven cases and `displayName`s.
> `ShowsView.swift` today is a plain `List` with no `@State` for sort or filter at all.
>
> Android's chip row (`MainActivity.kt:823-831`) offers Date / Top rated / Trending 48h / Hot 7d /
> Popular 30d / Momentum. **A horizontal chip row is an Android idiom; on macOS prefer a toolbar
> `Menu` or a `Picker`** — pick one, and say in the PR why. Whatever you choose, sort must be view
> state applied to already-loaded results: `ShowsView.load()` resets `loadState` wholesale, so
> routing sort through it would refetch the network on every change.
>
> **2. #67 — tag filter on `ShowsView.swift` and `SearchView.swift`.** `filterShowsByTag`
> (`Catalog.swift:251`) and `filterTracksByTag` (`:261`) exist, with `filterByTag` conveniences on
> the array types (`:272`, `:282`).
>
> Android's shape, worth copying because it handles the awkward case: build the available tag list
> from the tags actually present in the current results, sorted by `priority` descending then name,
> prefixed with an "All" entry, and shown **only when there's more than one** — see
> `MainActivity.kt:837-846` for shows and `:1484-1502` for search. A selected tag that's no longer
> present in the results must fall back to "All" rather than silently showing nothing.
>
> **3. #62 — procedural artwork in `ArtworkView.swift`.** **Decided: a seeded gradient plus the
> artist monogram — not a port of Android's full cassette drawing.**
>
> `CouchTourKit`'s `ShowArtworkGenerator` already has everything: `palette(forArtist:date:)`
> (`Artwork.swift:52`), `monogram(for:)` (`:211`), and `dateBadge(from:)` (`:263`). All tested,
> none called.
>
> The real work is that `ArtworkView` takes only `url` and so has no seed. Its five call sites each
> have the ingredients in a different shape, so decide on one signature and thread it through:
>
> - `HomeView.swift:175` and `:610` — `show.artURL`, and `ShowSummary` carries artist and date
> - `HomeView.swift:557` — `progress.artUrl`, and `PlaybackProgress` carries `artist` and `title`
> - `NowPlayingInspector.swift:47` and `MiniPlayerView.swift:22` — `player.artURL`; the seed has to
>   come off the current track
>
> Keep `@ScaledMetric` sizing and `accessibilityHidden(true)` exactly as they are. The placeholder
> must still render when `url` is nil **and** when an `AsyncImage` load fails — both paths
> currently fall through to `placeholder`.
>
> **4. #115 — context menu on Continue Listening.** Separate issue, own commit, but it belongs in
> this worktree rather than a fourth macOS branch. `ResumeCardView` (`HomeView.swift:538`) has no
> `.contextMenu`, so there's no way to remove or complete a row on the desktop. Android has exactly
> this as a long-press `DropdownMenu` (`MainActivity.kt:2377-2407`): open / mark completed /
> remove. The pattern already exists 230 lines up the same file — the favorited-artist card at
> `HomeView.swift:304` uses `.contextMenu`.
>
> Apply it to `ResumeCardView` **and** `ListeningView`'s rows so the Home shelf and the full screen
> agree; D200/#98 already established that those two move together.
>
> **Use the `DesignSystem/` components from D204** (`CardMetrics`, `SectionHeader`, `Shelf`,
> `StatusPill`, `InlineErrorView`) rather than new inline styling — the whole point of that pass was
> that there was nowhere to put these decisions, so every screen picked again.
>
> **Out of scope:** Android (done for all four); the `ShowsView` → `ShowDetailView` navigation
> contract; anything in `SearchView.swift` beyond the tag filter (sorting search results is #91,
> which is Batch 2B).
>
> **Verify:** `swift test` for anything you push down into the Kit — but note that most of this
> batch is view code the package tests cannot reach, which is precisely how these three features
> got marked shipped while being invisible. **A passing build proves very little here.** Install
> with `macos/scripts/install.sh`, launch, and actually click: sort a year's shows, filter by a
> tag, look at a Relisten show with no artwork, and right-click a Continue Listening card.
>
> **Before you start, read the interactive-verification note at the bottom of this file** — the
> keychain prompt will otherwise block exactly the manual pass this batch depends on.

---

## Batch 2A — List sort and filter, Android (#91, #116, #90)

> Work in a fresh worktree off `main`. Three Feedback-filed issues that are one coherent piece of
> work: *let me reorder and narrow a long list.* Android first; Batch 2B does the macOS parity pass
> afterwards and will reuse whatever you build.
>
> **Another worktree may be running Batch 1 in parallel** — it works only in `macos/`, so there is
> no overlap with this batch. **Batch 4 (#61) may also be running**, and it works in
> `app/src/main/java/dev/mike/couchtour/Relisten.kt` and in `Catalog.kt`'s
> `loadArtistsByBackend` / `RelistenCatalogSource`. Keep your own additions to `Catalog.kt` in the
> pure-helper region near the existing `filterShowsByTag` / `filterByTag` functions and leave the
> `MusicSource` implementations alone; git resolves the rest.
>
> **Put the logic in pure, testable helpers in `Catalog.kt`**, not inline in composables. Batch 2B
> reuses them, and it's the difference between real unit tests and a manual click-through being the
> only check. Android's existing `sortedByMode` / `filterByTag` are the model to match.
>
> **1. #116 — sort and filter the artists list.** `ArtistsScreen` (`MainActivity.kt:736`) has
> neither. It renders `mergeArtists` order, which is most-recorded-first. Mike asked for a
> popular/alphabetical toggle and a filter field.
>
> Favorites are rendered as their own pinned section — **they stay pinned regardless of sort**, and
> the filter should apply to both sections so a search for an artist you've starred still finds it.
> Decide what an empty filter result looks like rather than rendering a blank screen.
>
> **2. #91 — sort search results.** `SearchResultsList` (`MainActivity.kt:1466`) already has an
> artist chip row and a tag chip row; this is a third control, so watch the vertical budget — three
> stacked chip rows above the results is a lot of chrome. Sort by date (both directions) and by
> phish.in like count where available.
>
> **Decide and state what happens to Relisten hits when sorting by likes** — Relisten has no
> phish.in like count, so mixing backends under that sort needs an answer (stable-sort them last,
> or hide the option when the results are Relisten-only). Also decide whether sort applies to the
> Shows section only or to Tracks as well; the sections are rendered independently.
>
> **3. #90 — search within playlist pages.** Neither `PlaylistScreen` (`:1959`, a phish.in playlist)
> nor `LocalPlaylistScreen` (`:2076`, a local mixtape) has a search field. Add one to each that
> filters the track list.
>
> A trap: the `TextField`s already present in the macOS playlist views are **name entry for rename**
> (`LocalPlaylistView.swift:216`, `LocalPlaylistsView.swift:79`), not search. Don't read them as
> existing support when you get to 2B.
>
> `LocalPlaylistScreen` supports drag-to-reorder (D184). **Filtering and reordering must not both
> be active at once** — reordering a filtered list would write positions computed against a subset
> and silently scramble the playlist. Disable reorder while a filter is active, or clear the filter
> when reorder starts. Say which you chose and why.
>
> **Out of scope:** macOS (Batch 2B); the search API and its debounce; playlist creation/editing
> beyond adding the filter.
>
> **Verify:** unit tests for every pure helper you add — sorting, filtering, and the
> empty/no-match cases. `./gradlew testDebugUnitTest` (baseline 463). Update the README count.

---

## Batch 2B — List sort and filter, macOS parity (#91, #116, #90)

> **Gated: do not start until Batch 1 and Batch 2A have both merged.** This is not ceremony — 2B
> and Batch 1 both edit `SearchView.swift`, and 2B should reuse the pure helpers 2A lands rather
> than writing a second implementation that drifts.
>
> Work in a fresh worktree off `main` (after those merges). Port Batch 2A's three fixes to macOS,
> following the decisions it recorded rather than re-litigating them — read its PR and its
> `DECISIONS.md` entry first.
>
> Surfaces:
>
> - **#116** — `ArtistsView.swift`. Keep its existing Favorites/Artists two-section split; the same
>   "favorites stay pinned, filter applies to both" rule from 2A holds.
> - **#91** — `SearchView.swift`. Its `resultsListBody` renders Artists / Shows / Songs / Venues /
>   Tracks as independent `Section`s, and there is already an artist `Picker` plus (after Batch 1) a
>   tag filter. A toolbar control likely fits better than stacking a third row.
> - **#90** — `LocalPlaylistView.swift` and, for the phish.in playlist screen, its counterpart.
>   `.searchable` is the native fit here. The reorder-vs-filter interaction from 2A applies equally
>   — macOS uses `.onMove` (D184) and has the same scrambling hazard.
>
> Reuse `CouchTourKit` helpers where 2A's Kotlin equivalents were pure; where a Swift counterpart
> doesn't exist yet, add it to `CouchTourKit` with tests rather than putting logic in a view.
>
> **Out of scope:** Android; anything Batch 1 already covered in `SearchView.swift`.
>
> **Verify:** `swift test` plus the app-target build, then launch and click — same reasoning as
> Batch 1, and the same keychain caveat below.

---

## Batch 4 — Multi-level catalog cache (#61)

> Work in a fresh worktree off `main`. **This batch is genuinely parallel with Batches 1 and 2A** —
> it lives in the API/catalog layer, which none of them touch.
>
> Today there is exactly one cache on each platform: `@Volatile cachedArtists` on Android
> (`Relisten.kt:439`) and an actor-private `cachedArtists` on macOS (`RelistenAPI.swift:768`).
> Periods, shows, and show detail re-fetch on every screen entry. That was a deliberate scope cut
> (O4 in [MULTI-ARTIST-PLAN.md](../MULTI-ARTIST-PLAN.md), and the comment at `Relisten.kt:436` says
> so) — this batch revisits it.
>
> **The audit found that #61 does *not* gate #67/#21/#62/#91**, contrary to what the planning
> prompt suspected. Those read results through the `MusicSource` seam (`Catalog.kt:262`), which
> hides caching entirely. So this batch is free to run alongside them — but see the file note below.
>
> **Batch 2A may be running in parallel in `app/`.** It adds pure sort/filter helpers to
> `Catalog.kt` near the existing tag helpers. Keep your `Catalog.kt` changes to
> `loadArtistsByBackend` and the `MusicSource` implementations, and stay out of the pure-helper
> region; git resolves the rest.
>
> **Recommend in-memory with a TTL as the first step** — reversible, needs no schema migration, and
> O4's original reasoning still holds. Decisions this batch owes, to be recorded in `DECISIONS.md`:
>
> - **In-memory vs. on-disk.** On-disk means a Room migration *and* a GRDB migration. CLAUDE.md is
>   strict here and the `progress` table rules apply to any new table: a real `MIGRATION_9_10`
>   registered in `addMigrations`, the version bumped, the generated schema JSON committed, and
>   `MigrationTest.kt` coverage. A destructive migration is never the answer. If you can meet the
>   goal in memory, do that instead and say so.
> - **TTL and invalidation**, including the interaction with `NextStop.cacheKey`, which already
>   invalidates on tour-preference changes (D190) and must keep doing so.
> - **A memory ceiling** for a ~200-artist catalog with years and shows underneath it.
> - **Cache correctness across both backends** — phish.in and Relisten have different period
>   semantics (`/years` returns ranges, per the README), so a shared cache key needs care.
>
> Keep the test hooks that already exist: `cachedArtists` is deliberately non-private on Android and
> `resetCache()` is public on macOS (`RelistenAPI.swift:781`) precisely so tests can reset between
> runs. Whatever replaces them needs the same affordance.
>
> **Out of scope:** any UI change; the `MusicSource` interface's shape — this must stay invisible
> behind that seam, which is the property that makes it parallelizable.
>
> **Verify:** unit tests on both platforms for hit, miss, expiry, and invalidation. Both suites.

---

## Batch 6 — Offline downloads (#65): needs its own planning pass

**Do not write a single prompt for this.** It is the largest item in Phase 2 by a wide margin and
the only one that is a genuinely new subsystem rather than a change to an existing one: a download
manager with queue and retry, a storage location with eviction and a management UI, a schema
migration on **both** platforms (Room is at v9, GRDB at v9), playback resolution that prefers a
local file, and download affordances at artist, show, and track level.

Two traps already identified, worth carrying into that planning pass:

1. **Cast interaction.** A local file cannot be streamed to a Cast receiver. This is the same shape
   of problem D187 solved for FLAC by rewriting the URL at the `CastItemConverter` boundary —
   whatever #65 does has to keep casting working by falling back to the remote URL.
2. **Playback pipeline ownership.** #65 changes how a playable URL becomes a player item
   (`MediaItems.kt`, `Player.swift`). With #18 deferred out of the phase nothing else contends for
   those files — but if #18 is ever revived alongside it, they must not run in parallel worktrees.

Run a planning pass in the shape of [phase-2-plan.md](phase-2-plan.md) once Batches 0–4 have
landed, and let it produce its own batch breakdown.

---

## Before any macOS batch: clear the keychain prompt by hand

Batches 1 and 2B are almost entirely view code, and their verification is clicking. D204 already
hit this and it will block the same pass again:

> Every ad-hoc reinstall invalidates the keychain ACL, so the app puts up a login-keychain password
> prompt on launch and again on every activation; it's app-modal, and clearing it needs Mike's
> password ("Always Allow"), which is not something to automate.

It is a local-signing artifact, unrelated to any of this work, and it walled off GUI automation for
an entire session. **Answer it once by hand before starting**, or those batches ship on a
build-passes signal that — as the audit that produced this plan showed — proves very little about
whether a macOS feature is reachable at all.
