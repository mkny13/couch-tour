# Smoke Test Journeys

This document defines the user journey smoke specification for Couch Tour on macOS and Android.
Every journey defines an end-to-end user flow with an explicit, deterministic pass condition
queried against accessibility identifiers (`macos/CouchTour/AXIdentifiers.swift` on macOS and
`app/src/main/java/dev/mike/couchtour/A11yTags.kt` on Android).

## Exit Codes

All smoke test scripts and runners adhere to the following exit-code convention:

- `0` — **Complete**: The runner completed all tests regardless of verdict. The generated report decides pass or fail.
- `1` — **Usage Error**: Command-line arguments or runner configuration are invalid.
- `2` — **Preflight / Setup Failure**: Environment prerequisites are unmet (e.g. app not running, no Android device attached, missing accessibility permissions). A failing test journey is never exit 2.

---

## Journeys Overview

| id | platforms | fixture | asserts |
|---|---|---|---|
| `launch-cold-start` | mac, android | none | app reaches Home; nav identifiers present |
| `home-sections-after-relaunch` | mac, android | seeded-favorite | all three Home sections present after a force-quit + relaunch |
| `browse-artists-to-artist` | mac, android | none | Artists section → `moe.` artist screen opens |
| `search-artist-hit` | mac, android | none | query `moe` yields an artist hit under `search.section.artists` |
| `favorite-persists-across-relaunch` | mac, android | signed-in | favorited artist still in `sidebar.favorites.list` / `favorites.list` after relaunch |
| `no-unfavorited-in-favorites` | mac, android | signed-in | every row in the favorites list is a favorited artist |
| `next-stop-chip-focus` | mac, android | none | tapping a Next Stop artist chip changes selection to the artist, not the tour picker |
| `jam-chart-note-details` | mac, android | none | `jam_chart.note` present with details and a source link |
| `library-phishin-playlists` | mac, android | signed-in | Library lists the phish.in account's playlists |
| `favorite-syncs-android-to-mac` | sync (android→mac) | staging-group | an artist favorited on Android appears in the Mac sidebar favorites |
| `favorite-syncs-mac-to-android` | sync (mac→android) | staging-group | an artist un-favorited on the Mac disappears from the Android favorites |
| `in-progress-syncs-android-to-mac` | sync (android→mac) | staging-group | a track started on Android appears in the Mac In Progress section |
| `in-progress-syncs-mac-to-android` | sync (mac→android) | staging-group | In Progress cleared on the Mac disappears from the Android In Progress section |
| `nav-reaches-every-destination` | mac, android | none | all five `nav.*` destinations reachable and show their section identifier |
| `search-result-sections` | mac, android | none | artists/shows/tracks sections render for a query that has all three |
| `live-data-not-mockup` | mac, android | signed-in | a known current artist resolves to a real phish.in show — the #345 mockup-favorites regression guard |

---

## Journey Specifications

### `launch-cold-start`

- **id**: `launch-cold-start`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: Launch the application from a cold-start state. Wait for the initial interface and navigation structure to render.
- **pass condition**: On macOS, `sidebar.nav.home` is present. On Android, `nav.home` is present.

### `home-sections-after-relaunch`

- **id**: `home-sections-after-relaunch`
- **platforms**: `mac, android`
- **fixture**: `seeded-favorite`
- **steps**: With an active session having at least one track in progress and at least one favorited artist, terminate (force-quit) the application and relaunch it. Inspect the Home screen.
- **pass condition**: All three Home sections are present: on macOS, `home.in_progress` (with at least one `home.in_progress.card.<queueKey>` card), `home.next_tour_stops`, and `home.on_this_date` are present; on Android, `home.section.in-progress`, `home.section.next-tour-stops`, and `home.section.on-this-date` are present.

### `browse-artists-to-artist`

- **id**: `browse-artists-to-artist`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: Navigate to the Artists section. Scroll or search within the artists list and select artist `moe.` to open its catalog screen.
- **pass condition**: On macOS, `sidebar.nav.artists` navigates to `artists.list`, and selecting the `artists.row.<backend>.<id>` row for `moe.` opens `artist.screen`; on Android, navigation from `nav.home` reaches the artist catalog screen for `moe.`.

### `search-artist-hit`

- **id**: `search-artist-hit`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: Navigate to Search. Type `moe` into the search field and submit or wait for search results to settle.
- **pass condition**: On macOS, `search.field` receives query and `search.tab.artists` is present with hits > 0; on Android, `search.results` displays `search.section.artists` containing at least one artist result row.

### `favorite-persists-across-relaunch`

- **id**: `favorite-persists-across-relaunch`
- **platforms**: `mac, android`
- **fixture**: `signed-in`
- **steps**: In a signed-in session, favorite an artist if not already favorited. Force-quit the application and relaunch it. Inspect the favorites list.
- **pass condition**: On macOS, `sidebar.favorites.list` is present and contains `sidebar.favorites.row` for the favorited artist; on Android, `favorites.list` is present and contains `favorites.row.<artistKey>` for the favorited artist.

### `no-unfavorited-in-favorites`

- **id**: `no-unfavorited-in-favorites`
- **platforms**: `mac, android`
- **fixture**: `signed-in`
- **steps**: In a signed-in session, view the favorites list in the sidebar or Home screen. Compare all displayed entries with the user's actual favorited artists.
- **pass condition**: Every row rendered in the favorites list corresponds to a confirmed favorited artist: on macOS, all children of `sidebar.favorites.list` match `sidebar.favorites.row` for favorited artists; on Android, all children of `favorites.list` match `favorites.row.<artistKey>` for favorited artists (no un-favorited or default mockup artists appear).

### `next-stop-chip-focus`

- **id**: `next-stop-chip-focus`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: On the Home screen, locate the Next Tour Stops card section. Tap or click an artist chip within one of the tour stop cards.
- **pass condition**: Clicking the artist chip changes selection to the artist screen rather than opening the tour picker: on macOS, `home.next_tour_stops` contains the artist chip (`home.next_tour_stops.chip.<backend>.<id>`) and clicking it navigates (to `show.detail`) without activating `home.track_tour`; on Android, `home.section.next-tour-stops` row item chip (`home.section.next-tour-stops.row.<showKey>`) focuses the artist screen rather than the tour picker dialog.

### `jam-chart-note-details`

- **id**: `jam-chart-note-details`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: Navigate to a track known to have jam chart annotations (e.g. a Phish jam chart selection) and open the track / Now Playing view.
- **pass condition**: `jam_chart.note` is present, displaying jam chart note text and a source link (on macOS, `jam_chart.note` plus the `jam_chart.source` link; on Android, `A11yTags` lacks a distinct jam chart tag at HEAD, which is noted as a platform tag gap, but the condition asserts on `jam_chart.note`).

### `library-phishin-playlists`

- **id**: `library-phishin-playlists`
- **platforms**: `mac, android`
- **fixture**: `signed-in`
- **steps**: In a signed-in session, navigate to the Library screen.
- **pass condition**: On macOS, selecting `sidebar.nav.library` shows `library.screen` with at least one phish.in account playlist row, `library.row.account-playlist-<slug>` (local `library.row.playlist-*`, `track-*` and `liked-*` rows do not count; with none, the journey is a fixture-unavailable `SKIP`, not a pass); on Android, selecting `nav.library` displays the user's phish.in account playlists.

## Two-client sync round trips

These four journeys need both clients, so neither single-platform runner can run them. They are
orchestrated by `scripts/smoke/sync-roundtrip.sh` (invoked by `run-smoke.sh` when both platforms are
selected) and reported under platform `sync`, one result line per direction. Each is one half of a
round trip: an action on one client (`--sync-step <action>`), then an assertion on the other client
(`--sync-step <assertion>`) polled for the timeout (default **30s**, `--timeout`).

- **Fixture `staging-group`**: both betas are installed and already paired to the isolated staging
  sync group (#359). Pairing is not automated. Env: `CCTV_SMOKE_SYNC_ARTIST_MAC` (`<backend>.<id>`)
  and `CCTV_SMOKE_SYNC_ARTIST_ANDROID` (`<artistKey>`) name the same artist on each platform; the
  per-platform control hooks are listed in README.md.
- **Clean state**: teardown always runs `scripts/smoke-sync-reset.sh --yes`, even when a step fails
  or times out, so the group is left as found (this also removes the pairings; re-pair afterwards).
- **Result semantics**: a timeout is `FAIL` attributed to the asserting direction. A control or fixture
  that doesn't exist yet is `SKIP`, never `PASS`. A second direction that needs state from a failed
  first direction is `SKIP`.
- `favorite-syncs-*` accurately `FAIL`s while favorites don't sync (#351).

### `favorite-syncs-android-to-mac`

- **id**: `favorite-syncs-android-to-mac`
- **platforms**: `sync` (android→mac)
- **fixture**: `staging-group`
- **steps**: On Android, favorite the fixture artist (`favorite-add`). On macOS, poll for it (`favorite-present`).
- **pass condition**: Within the timeout, `sidebar.favorites.row.<backend>.<id>` appears inside `sidebar.favorites.list` on macOS.

### `favorite-syncs-mac-to-android`

- **id**: `favorite-syncs-mac-to-android`
- **platforms**: `sync` (mac→android)
- **fixture**: `staging-group`
- **steps**: Needs `favorite-syncs-android-to-mac` to have passed. On macOS, un-favorite the fixture artist (`favorite-remove`). On Android, poll for its removal (`favorite-absent`).
- **pass condition**: Within the timeout, `favorites.row.<artistKey>` is gone from `favorites.list` on Android.

### `in-progress-syncs-android-to-mac`

- **id**: `in-progress-syncs-android-to-mac`
- **platforms**: `sync` (android→mac)
- **fixture**: `staging-group`
- **steps**: On Android, start playback of a track (`progress-start`) so it enters the In Progress queue. On macOS, poll Home (`progress-present`).
- **pass condition**: Within the timeout, `home.in_progress.card.<queueKey>` appears under `home.in_progress` on macOS.

### `in-progress-syncs-mac-to-android`

- **id**: `in-progress-syncs-mac-to-android`
- **platforms**: `sync` (mac→android)
- **fixture**: `staging-group`
- **steps**: Needs `in-progress-syncs-android-to-mac` to have passed. On macOS, clear the In Progress entry (`progress-clear`). On Android, poll Home (`progress-absent`).
- **pass condition**: Within the timeout, no `home.section.in-progress.row.<queueKey>` remains under `home.section.in-progress` on Android.

### `nav-reaches-every-destination`

- **id**: `nav-reaches-every-destination`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: Tap or click each primary navigation destination in sequence.
- **pass condition**: On macOS, each sidebar item is selectable and shows its screen identifier: `sidebar.nav.home`→`home.screen`, `sidebar.nav.artists`→`artists.screen`, `sidebar.nav.search`→`search.screen`, `sidebar.nav.library`→`library.screen`, `sidebar.nav.history`→`history.screen`, `sidebar.nav.settings`→`settings.screen`; on Android, all bottom/drawer navigation destinations are present and selectable: `nav.home`, `nav.search`, `nav.library`, `nav.history`, and `nav.settings`.

### `search-result-sections`

- **id**: `search-result-sections`
- **platforms**: `mac, android`
- **fixture**: `none`
- **steps**: Navigate to Search and submit a query matching artists, shows, and tracks (e.g. `ghost`).
- **pass condition**: On macOS, search tab categories `search.tab.artists`, `search.tab.shows`, and `search.tab.tracks` render with positive result counts; on Android, `search.results` displays `search.section.artists`, `search.section.shows`, and `search.section.tracks`.

### `live-data-not-mockup`

- **id**: `live-data-not-mockup`
- **platforms**: `mac, android`
- **fixture**: `signed-in`
- **steps**: From the favorites list, select a favorited artist (e.g. Phish) and navigate into the shows list.
- **pass condition**: On macOS, selecting the artist from `sidebar.favorites.list` opens `artist.screen`; a period (`artist.period.row.*`) lists loaded `shows.row.<date>` entries and opening one shows `show.detail` (live data, not hardcoded mockup shows); on Android, selecting the artist from `favorites.list` loads live show data from the backend (regression guard for #345).

---

## Appendix: Report Grammar

This appendix specifies the report grammar contract defined in #358 and implemented by the promotion gate:

- `smoke-reports/<tag>.md` contains, anywhere, a line `Tag: <tag>` and **exactly one** line matching `^Smoke: (PASS|FAIL)$`.
- A `PASS` report may carry any number of `Waived: <journey-id> - <reason>` lines. A journey the report lists as failed must be covered by a `Waived:` line for `PASS` to hold.
- Any other line is ignored by the gate. Per-journey rows, screenshots, and bug links are therefore free-form — keep the grammar this small so a hand-written report and a runner-written one are both readable.

### Runner Result-Line Contract

The shared runner contract implemented in `scripts/smoke/lib.sh`:

- One line per journey, tab-separated:
  ```text
  <platform>\t<journey-id>\t<status>\t<evidence>
  ```
- `status` is exactly one of `PASS`, `FAIL`, `SKIP`. `SKIP` carries a human-readable reason in `evidence` and means the fixture wasn't available. `SKIP` is not `PASS`.
- The tag lives in a header comment of the results file (`# tag: <tag>`), not on every line.
- `evidence` is a single line, identifier-level where possible, and must never contain account data (no artist names, no playlist names, no URLs with user-specific content). It is committed, in a public repo.
