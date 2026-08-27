# macOS UX Polish — Batch Prompts

Working prompts for **Phase 2 Batch 4** in [ROADMAP.md](../ROADMAP.md), filed from Mike's
testing of macOS beta **v0.57-beta** on 2026-08-26.

**Sequencing.** Batches A and B are independent — they touch different files and can run in
parallel worktrees. Batch C rewrites the files both of them edit, so it starts only after A
and B have merged.

| Batch | Issues | Primary files | Runs |
|---|---|---|---|
| A | [#97](https://github.com/mkny13/couch-tour/issues/97), [#98](https://github.com/mkny13/couch-tour/issues/98), [#100](https://github.com/mkny13/couch-tour/issues/100), [#101](https://github.com/mkny13/couch-tour/issues/101) | `macos/CouchTour/HomeView.swift`, `ContinueListeningView.swift`, `Browse/TourPickerSheet.swift`, `app/src/main/java/dev/mike/couchtour/MainActivity.kt` | in parallel with B |
| B | [#99](https://github.com/mkny13/couch-tour/issues/99) | `macos/CouchTour/MiniPlayerView.swift`, `NowPlayingInspector.swift`, `AppModel.swift`, `RootView.swift` | in parallel with A |
| C | [#102](https://github.com/mkny13/couch-tour/issues/102) | `macos/CouchTour/RootView.swift`, `HomeView.swift`, and most other views | after A and B merge |

Every batch: run `cd macos/Packages/CouchTourKit && swift test` plus a
`xcodegen generate && xcodebuild … build` of the app target, log the work in `DECISIONS.md`
under a new iteration, update the README's test count if it moved, and open a PR.

---

## Batch A — Home screen interaction fixes

> Work in a fresh worktree off `main`. This batch closes #97, #98, #100, and #101 — four
> small, independent Home-screen fixes on the macOS client (one of them also touches
> Android). Read each issue with `gh issue view <n>` before starting; they contain the
> specific file/function diagnosis and shouldn't be re-derived.
>
> **Another worktree is running Batch B in parallel**, working in
> `macos/CouchTour/MiniPlayerView.swift`, `NowPlayingInspector.swift`, and adding a
> cross-section navigation route to `AppModel.swift`. Stay out of those three files. If you
> need to navigate from Home, use `HomeView`'s own `NavigationStack` and its existing
> `.navigationDestination(for: ShowSummary.self)` / `(for: ArtistRef.self)` — Home is inside
> the detail stack, so it doesn't need Batch B's mechanism.
>
> The four fixes:
>
> 1. **#100 first — it's a one-liner and the smallest thing that can go wrong.** `HomeView`'s
>    `.sheet(item: $tourPickerArtist)` has no `onDismiss:`, so the Next Stop shelf keeps its
>    stale show after `TourPickerSheet` saves a preference. Reload `tourPreferences` before
>    re-resolving Next Stop — `reloadDiscovery()` reads that state, so ordering matters.
>    Check `clearPreference()` takes the same path. Then verify whether Android's
>    `loadOnce(Triple(today, favoritedArtists…, preferencesMap))` already invalidates
>    correctly; if it does, say so in the PR and change nothing there.
>
> 2. **#101 — Surprise Me from starred artists.** Change the call site on both clients from
>    the merged catalog to the already-computed `favoritedArtists` list. You need to decide
>    what the button does with zero starred artists — disable with an explanation, or fall
>    back to the full catalog. Pick one, make both platforms agree, and record it in
>    DECISIONS.md as superseding D157. `pickRandomShow` itself doesn't change.
>
> 3. **#98 — Continue Listening tap targets.** Split `ResumeCardView` into two targets:
>    artwork/title/subtitle navigate to the show, the play button resumes and gets much
>    bigger. The hit regions must not overlap — a click on play must not also navigate.
>    Nesting a `Button` inside a `NavigationLink`'s label swallows clicks on macOS, so expect
>    to reach for a `navigationPath` value or explicit z-ordering rather than the obvious
>    nesting. Resolving a `PlaybackProgress` back to a `ShowSummary` should reuse whatever
>    `Resume.swift` already does, not a second lookup path. Apply the same split to
>    `ContinueListeningView`'s rows so the sidebar section and the Home shelf agree.
>
> 4. **#97 — Feedback button.** Add a quiet Feedback affordance to `headerSection` that opens
>    a pre-filled GitHub new-issue URL via `NSWorkspace.shared.open`. Mirror Android's
>    `FeedbackButton.kt` body template, substituting desktop metadata: app version, current
>    `SidebarSection`, hardware model, macOS version, and beta-vs-production channel. Build
>    the URL with `URLComponents` so the query is properly percent-encoded. Title it
>    `Feedback (Couch Tour <version>)` to match Android's shape.
>
> Add unit tests wherever logic is testable in `CouchTourKit` — the Surprise Me artist
> selection and the feedback URL/body construction both are; the SwiftUI layout changes
> aren't, so verify those by building and launching the app (`macos/scripts/install.sh`) and
> actually clicking through Home.

---

## Batch B — Player bar navigation

> Work in a fresh worktree off `main`. This batch closes #99: on macOS, clicking the track
> title or date in the player bar should open that show, and clicking the artist name should
> open that artist — matching what Android's `NowPlaying.kt` already does. Read
> `gh issue view 99` first; it contains the architectural analysis.
>
> **Another worktree is running Batch A in parallel**, working in
> `macos/CouchTour/HomeView.swift`, `ContinueListeningView.swift`, and
> `Browse/TourPickerSheet.swift`. Stay out of those three files. You will both touch
> `AppModel.swift` — you're adding the navigation route, they are not, so keep your addition
> to new properties and don't reformat what's already there.
>
> The real work here is not the click handlers, it's the routing. `MiniPlayerView` lives in
> `RootView`'s outer `VStack`, outside the `NavigationSplitView` entirely, and each sidebar
> section deliberately builds its own fresh `NavigationStack` so drill-down state doesn't
> leak between sections (there's a comment in `RootView.swift` explaining this — don't
> undo it). So the player bar has no stack to push onto.
>
> Add a small cross-section navigation route on `AppModel`: set `selection` to the receiving
> section and hand that section's stack a pending destination to push. Follow the one-shot
> pattern `focusSearchField` already establishes — the receiving view consumes the value and
> clears it — so a later visit to that section doesn't re-push a stale destination. Don't
> invent a second mechanism alongside `selection` / `showNowPlaying` / `focusSearchField`.
>
> Decide which section receives the push (Artists is the natural home for both shows and
> artists) and write the reasoning into DECISIONS.md — Batch C and a future #98 follow-up
> will both reuse this.
>
> **Read `macos/CouchTour/BackButton.swift` before designing the push.** #96 landed shortly
> before this batch: it adds `BackButtonToolbarItem` / `.navigationBackButton()`, a toolbar
> back button driven by `@Environment(\.dismiss)` with a ⌘[ shortcut, and applies it to
> `PeriodsView`, `ShowsView`, `ShowDetailView`, and `LocalPlaylistView` — including the two
> destinations you'll be pushing. Use that helper rather than adding a second back
> affordance. It also creates a case worth checking by hand: a view pushed from the player
> bar onto a section stack the user never drilled into renders a Back button that dismisses
> to that section's root, which may be a screen they were never on. Confirm that lands
> somewhere sensible, and if it doesn't, say so in the PR rather than papering over it.
>
> Apply the same navigable identity to `NowPlayingInspector`'s header block, which renders
> the same artist/date/track text inertly today.
>
> Cover the routing logic with unit tests where it's separable from SwiftUI. Then build and
> launch the app and verify by hand: start a show, browse to an unrelated section, click the
> date in the player bar, and confirm you land on the right show without the previously
> browsed stack reappearing underneath.

---

## Batch C — Universal design pass and sidebar rethink

> **Do not start until #97, #98, #99, #100, and #101 have merged** — this batch rewrites the
> files all of them touch. Confirm with `gh issue view` before beginning.
>
> Work in a fresh worktree off an up-to-date `main`. This batch addresses #102: a design pass
> over the macOS client for universal design principles, plus a rethink of the sidebar. Read
> `gh issue view 102` in full first — it enumerates the specific problems already visible in
> the code, so don't spend a discovery pass re-finding them.
>
> **Split this into two deliverables, and stop between them.**
>
> **C1 — Proposal, no code.** Write a short design proposal covering:
>
> - the sidebar structure you'd replace the flat six-item list with, and why — including
>   what happens to Search (currently a navigation destination, which is why ⌘F has to route
>   through `AppModel.selection` + `focusSearchField`), what happens to Continue Listening as
>   a top-level peer once #98 makes Home's shelf fully navigable, and whether account/sync
>   move out of Home's cards and into the same place as Settings
> - the shared components you'd introduce (card, section header, shelf) and the token set —
>   radii, background treatments, type ramp — they'd standardize on, since the current
>   inconsistency is a direct result of every shelf re-implementing its own container
> - which universal-design heuristics drive which change, and any place a heuristic conflicts
>   with an existing convention in this app, called out rather than silently resolved
>
> Publish it as an artifact and stop there. Mike has said the sidebar is his call, not a
> unilateral one — do not restructure it before he responds.
>
> **C2 — Implementation, after Mike approves a direction.** Build the shared components
> first, then migrate the screens onto them; a per-site fix pass will just re-create the
> inconsistency. Alongside the structural work, close out the accessibility items #102 lists:
> `accessibilityLabel`s on every icon-only control (the tour-picker pencil, mute, transport
> buttons), non-color-only signals for sync-paired and FLAC/MP3 state, Dynamic Type behaviour
> for the hardcoded `.caption2` text and fixed artwork frames, and real error states in the
> four places that currently swallow failures into empty state (`reloadArtists`,
> `reloadProgress`, `loadTours`, and `ContinueListeningView`'s load path) — "no data" and
> "the request failed" must not look identical.
>
> Verify against VoiceOver, Increase Contrast, and Reduce Motion on a real launch of the app,
> not just a build. Log the whole pass in DECISIONS.md, noting which earlier UI decisions
> (D166, D167, D171) it supersedes.
