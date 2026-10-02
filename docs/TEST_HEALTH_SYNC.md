# Sync backend test health (pass:tests, issue #449 / #426)

Suite: `sync/test/sync.test.ts` (vitest + `@cloudflare/vitest-pool-workers`, real Miniflare D1).
Count: **14 tests**, unchanged, so README and CLAUDE.md keep "14 tests".
Whole run: ~0.5s wall, ~160ms of test time. Slowest tests are 15-17ms (round trip, F2 concurrency,
empty-table resync); everything else is 8-14ms.

## Isolation: fine, no change
`beforeEach` drops every table and re-applies `schema.sql`, so no test sees another's rows. Each
test pairs its own group, and `pairStart` uses a unique `CF-Connecting-IP` so the rate limiter
never accumulates. Verified with `--sequence.shuffle` three times: 14/14 passed each time.

## False greens: 5 tightened, all fixed here
Each tightened assertion was checked by breaking the handler in `src/index.ts` locally (reverted afterwards).

| Test | Weakness | Fix | Breakage that now fails it |
|---|---|---|---|
| 410 below retention floor | 410 asserted by status only; `since = 0` branch by status only | assert error body and that the `since = 0` cursor is the floor (10) | changing the 410 error text; ignoring the floor in `currentSeq` |
| full resync with active rows below floor | `toBeGreaterThanOrEqual(5)` passes for any larger seq | `toBe(5)` | floor + 1 in `currentSeq` |
| exact `updatedAt` tie | no status assertion before reading the body | assert 200 | n/a (body assertion already caught dedup regressions) |
| `applyIncomingChanges` | only checked the returned count, which passes if nothing is written | read the row back (title, seq) | skipping `env.DB.batch(statements)` |
| F2 concurrency | gate was released only on the happy path, so a failed assertion could leave request A parked | release the gate in `finally` | n/a (robustness) |

No `it.skip`, `it.todo`, un-awaited `expect(...).rejects`, or tests without `expect` exist.
Not tightened: `health check` asserts the body but not the `SELECT 1`; a dead D1 binding is
covered by the deploy smoke gate (#249), so a unit test would add little.

## Time creep: none
No timers, sleeps or real-time waits in the suite. F2 uses a promise gate, not a delay.

## Leaked resources: none found
The only global patched, `env.DB.prepare` in F2, is restored in `finally`. Miniflare/D1 lifecycle
is owned by the pool and torn down per run. `vitest run --reporter=hanging-process` exited
cleanly with no hanging handles.

## Filed issues
None; every finding was local to test code.
