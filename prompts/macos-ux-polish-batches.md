# macOS UX Polish — Batch Prompts

Working prompts for **Phase 2 Batch 4** in [ROADMAP.md](../ROADMAP.md), filed from Mike's
testing of macOS beta **v0.57-beta** on 2026-08-26.

**Sequencing.** A and B ran in parallel worktrees and have both merged; C was gated on them
because it rewrites the files they edit. C1 (proposal) is done and Mike chose a direction, so
**C2 is the only remaining work** — its prompt is at the bottom of this file. The A and B
prompts below are kept as the record of what was asked for.

| Batch | Issues | Status |
|---|---|---|
| A | [#97](https://github.com/mkny13/couch-tour/issues/97), [#98](https://github.com/mkny13/couch-tour/issues/98), [#100](https://github.com/mkny13/couch-tour/issues/100), [#101](https://github.com/mkny13/couch-tour/issues/101) | **Merged** — #107 (D200), refined by #110 (D201) |
| B | [#99](https://github.com/mkny13/couch-tour/issues/99) | **Merged** — #112 (D202) |
| C | [#102](https://github.com/mkny13/couch-tour/issues/102) | C1 done, direction chosen. **C2 is ready to run.** |

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

## Batch C — No sidebar, plus the universal design pass

C1 ran and Mike picked a direction. **Visual spec:**
<https://claude.ai/code/artifact/73b5bde4-8953-43c6-aed7-ccbade364e0e> — read it before starting;
it is the source of truth for layout, and this prompt is the source of truth for scope.

### The direction, settled

The sidebar is **removed entirely** — not trimmed, not replaced with a tab strip. A tab strip was
mocked up and rejected for being the same menu-of-destinations in a smaller box. What replaces it:

- **Home is the hub.** Its existing "Library & Explore" quick-link tiles stop being decorative and
  become the app's navigation. Give them chevrons so they read as the entry points they now are.
- **One `NavigationStack` for the window**, not six. You drill in from Home and pop with ⌘[ —
  the shortcut `BackButton.swift` already provides.
- **A breadcrumb in the toolbar** does the orientation job the sidebar's selected row used to do,
  and does it better: it shows the path, not just the destination.
- **Search is a persistent toolbar field.** ⌘F focuses it directly. This deletes the
  `selection` + `focusSearchField` hop *and* the macOS 15 `.searchFocused` gate that made ⌘F a
  no-op on the 14.0 deployment target.
- **Continue Listening and History merge** into one "Listening" destination, reached from its tile.
- **Settings stays exactly as it is** — the existing ⌘, `Settings` scene with its Playback,
  Account, and Sync tabs. No popover; that was considered and dropped. Home keeps its
  "Settings & status" tile section, and every tile plus the section header opens that window at
  the matching tab. Delete `HomeView`'s duplicate Account and Sync **sheets** — those forms must
  exist in exactly one place.
- **The version footer and "Check for Updates…"** move off the sidebar's bottom edge. The Settings
  Playback tab already shows both, so they stop being duplicated rather than moving anywhere.
- **⌘1–⌘4 stay as menu-bar items** jumping to Home, Artists, Listening, and Playlists. Invisible
  chrome costs nothing, and they cover the one real regression: a cross-section jump now routes
  through Home.

### Two things that will bite

**Batch B's routing collapses into this.** #112 (D202) added `PlayerBarDestination` and
`AppModel.navigate(to:)` — a one-shot `pendingArtistsDestination` that switches `selection` to
Artists and hands that section's stack a value to push. With no sections and one stack, that
becomes a plain push onto the single path. Simplify it; don't build around it. `PlayerBarDestination`
itself stays useful and is unit-tested in `CouchTourKit` — keep the type, drop the section hop.

**`SidebarSection` disappears with the sidebar,** and `FeedbackButton` takes one as its
`currentScreen` parameter. It needs a replacement screen identifier. The breadcrumb's leaf is the
natural source and is better feedback metadata than a section name ever was.

### Scope

Alongside the structural work, close out the accessibility items in #102. Build the shared
components first — a card, a section header, a shelf — then migrate screens onto them; a
per-site fix pass just re-creates the drift, since the absence of a shared component is what
caused it. They belong in `macos/CouchTour/DesignSystem/`, not in `CouchTourKit`, which is
deliberately UI-free.

- `accessibilityLabel`s on every icon-only control — the tour-picker pencil, mute, all three
  transport buttons.
- Non-colour-only signals for sync-paired and FLAC/MP3 state. The spec draws these as a
  "✓ Paired" pill and a glyph-plus-label codec badge.
- Real error states in the four load paths that currently discard the error into empty state:
  `reloadArtists`, `reloadProgress`, `loadTours`, and `ContinueListeningView`'s load. "No data"
  and "the request failed" must not render identically. `ErrorView` already exists and is used
  correctly elsewhere.
- A confirmation on the tour picker's `Clear / Default`, which is marked destructive and confirms
  nothing.
- **Text scaling, correctly framed.** #102's wording says "Dynamic Type," which is an iOS concept
  this doesn't have. The actionable macOS version: replace hardcoded sizes like the FLAC badge's
  `.font(.system(size: 9, weight: .bold))` with semantic styles, and fixed artwork frames with
  `ScaledMetric`. Work from this, not from the issue's wording.

### Verification

A real launch, not just a build: VoiceOver over the transport and tour picker, Increase Contrast
in both appearances, and the Accessibility text-size setting at a couple of steps. Confirm ⌘[ pops
correctly from a destination reached via the player bar, and that ⌘F focuses the field from a
cold launch.

Log the pass in DECISIONS.md. It supersedes **D169** (Search as its own sidebar section — the
stack-isolation reasoning survives, the section doesn't), **D171** (History as its own flat screen,
and the Settings-scene placement, which this reinforces rather than reverses), **D197** (Home's
duplicate Account/Sync sheets), and simplifies **D202** (Batch B's cross-section route). Update the
README's test count if it moves.
