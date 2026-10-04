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

## Running the smoke suite

Three entry points, all run from the repo root:

- `scripts/smoke/run-smoke.sh --tag <beta-tag>`: runs both platforms, writes
  `smoke-reports/<tag>.md`, and files bugs. This is the one to use after `scripts/cut-beta.sh`.
- `scripts/smoke/run-mac.sh` / `scripts/smoke/run-android.sh`: one platform, result lines on stdout.
  Add `--out <file>` to also write them to a file.

`run-smoke.sh` options: `--platform mac|android|both`, `--out <dir>`, `--waive <id> - <reason>`,
`--no-file-bugs`, `--commit-screenshots`, `--keep-going` (see `--help`). Exit codes: `0` = every
selected platform ran and a report was written, `1` = usage error, `2` = a platform could not be
run. If no platform could run, no report is written and no issue is filed. If some did, the
report is written, says which platform didn't run, is `FAIL`, and the exit code is still `2`.

Worked example, no device attached:

```
$ scripts/smoke/run-smoke.sh --tag v0.0-demo --platform android
== android: .../scripts/smoke/run-android.sh
ERROR: No Android device attached in 'device' state. adb devices output:\nList of devices attached
No platform could be run; no report written.
  android - No Android device attached in 'device' state. adb devices output:\nList of devices attached
$ echo $?
2
```

### Report grammar

`report.sh --results <file>... --tag <tag> --out <report.md> [--waive ...] [--not-run ...] [--issue ...]`
is a pure formatter (no app, device, or network) and `test-report.sh` tests it with fixtures in
`testdata/`. The report has exactly one `Tag: <tag>` line, exactly one `Smoke: PASS|FAIL` line,
and `Waived: <id> - <reason>` lines on a `PASS` only. `FAIL` if any journey fails without a
waiver, any platform didn't run, or there are no results. `SKIP` is listed under "Skipped (not
verified)" and neither passes nor blocks. Excerpt from `test-report.sh`'s failure fixture:

```
# Smoke report

Tag: v9.99-beta
Smoke: FAIL

1 passed, 1 failed (0 waived), 0 skipped.
```

`check-journeys.sh` fails if `JOURNEYS.md` and a runner's `ALL_JOURNEYS` list disagree.

### Bug filing

Each failing, unwaived journey is filed with `mahler add phish-in` and labelled `mahler` (via
`gh issue edit --add-label`; falls back to `gh issue create` with a loud warning if `mahler`
isn't on `PATH`). The body carries a `smoke-journey:<id>` marker; if an open issue with that
marker exists, `run-smoke.sh` comments on it with the new tag instead of filing a duplicate.
`--no-file-bugs` turns all of this off.

## Verifying shipped issues (#404)

A shipped issue counts as verified only with a comment citing a beta tag plus evidence (a smoke-report
line, a screenshot or dump, or an owner UAT note).

`verify-comment.sh [--dry-run] <issue> <tag> <evidence...>` posts it in one format: `<issue>` is `346` or
`#346`, `<tag>` looks like `v0.87` or `v0.87-beta`, and `--dry-run` prints the comment instead of posting.
The `<!-- mahler:verified -->` marker on the first line is what tooling looks for. Test:
`python3 scripts/smoke/test_verify_comment.py`.

`run-smoke.sh` lists closed `mahler:verifying` issues without that marker, and `report.sh` renders them in an
`## Unverified shipped issues` table (`Issue | Title | Status | Evidence / Notes`). An issue maps to a journey
when `JOURNEYS.md` mentions its `#N` in that journey, or the journey id appears in its title or body:
`covered` (a mapped journey passed), `still reproduces` (a mapped journey failed; this wins over a pass),
`not covered` (no mapped journey ran). `report.sh --unverified-issues <tsv> [--journeys <md>]` takes the list
from a file so `test-report.sh` can test it headlessly. `run-smoke.sh --auto-verify` (opt-in) then posts a
verification comment on each `covered` issue.
