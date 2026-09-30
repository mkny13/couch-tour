# Smoke Tests

This directory is shared with the smoke journey runner (#357). Please coordinate file names rather than adding a second README.

## `ax-tree.sh`

This script dumps the accessibility tree of the running macOS "Couch Tour Beta" app, scoped to the identifiers in `macos/CouchTour/AXIdentifiers.swift`. It is used for cheap, deterministic UI checks (e.g. "is there an Artists row?") without hanging on AppleScript's `entire contents`.

### Prerequisites

You need the beta app installed and running:
```bash
macos/scripts/install-beta.sh
```
Launch "Couch Tour Beta" from `/Applications`.

### Usage

```bash
./scripts/smoke/ax-tree.sh [--max-depth N] [--all] [--identifiers-only]
```

- `--max-depth N`: Override the default maximum depth of 6.
- `--all`: List every element that has an `AXIdentifier`, even if it isn't in `AXIdentifiers.swift`.
- `--identifiers-only`: Print only the identifier column.

### Exit Codes

- `0`: Success
- `2`: App not running
- `3`: No matching elements found

### Example

```bash
$ ./scripts/smoke/ax-tree.sh
home.in_progress	AXGroup	In Progress
home.next_tour_stops	AXGroup	Next Tour Stops
home.on_this_date	AXGroup	On This Date
sidebar.nav.artists	AXRow	Artists
sidebar.nav.history	AXRow	History
sidebar.nav.home	AXRow	Home
sidebar.nav.library	AXRow	Library
sidebar.nav.search	AXRow	Search
sidebar.nav.settings	AXRow	Settings
```

## Journeys

Smoke user journeys and pass conditions are defined in [`JOURNEYS.md`](JOURNEYS.md).
Runner result lines follow the tab-separated format `<platform>\t<journey-id>\t<status>\t<evidence>` with status `PASS`, `FAIL`, or `SKIP`.
Exit codes follow the shared convention: `0` = completed run (report decides pass/fail), `1` = usage error, `2` = preflight/setup failure.
