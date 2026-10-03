# Smoke reports

`scripts/smoke/run-smoke.sh` writes one report per beta tag here: `smoke-reports/<tag>.md`.
The promotion gate (`scripts/promote-beta.sh`, #358) reads these files and refuses to promote a
tag without a passing report for that exact tag.

## Committed vs local

- **Committed:** `<tag>.md`, the evidence of a run.
- **Not committed (gitignored):** `<tag>/*.png` failure screenshots and the per-platform result
  and stderr files. The screenshots show the owner's signed-in library, favorites, and listening
  history, and this repo is public. Pass `--commit-screenshots` to `run-smoke.sh` to opt out.
  See DECISIONS.md D327.

## Grammar the gate parses

```
Tag: <tag>                          exactly one line, equal to the tag
Smoke: PASS | FAIL                  exactly one line; nothing else starts with "Smoke: "
Waived: <journey-id> - <reason>     zero or more lines, only on a PASS
```

Everything else (the per-journey table, the Skipped table, failure details, the footer) is for
humans and is ignored by the gate. `SKIP` is neither pass nor fail: it is listed under "Skipped
(not verified)". A platform that could not be run at all can never produce a `PASS`.

`scripts/smoke/test-report.sh` pins this grammar with fixtures; `report.sh` self-checks it.

## Every escaped bug adds a journey

When a bug reaches production, add a journey for it to `scripts/smoke/JOURNEYS.md` and both
runners (`scripts/smoke/check-journeys.sh` fails if the spec and runners disagree). A failing
journey is filed as a `mahler`-labelled issue by `run-smoke.sh` and deduped by its
`smoke-journey:<id>` marker.
