# Sync backend test health (pass:tests, issue #449 / #426)

Suite: `sync/test/sync.test.ts` (vitest + `@cloudflare/vitest-pool-workers`, real Miniflare D1).
Count: **44 tests** (36 in `sync/test/sync.test.ts` + 8 in `sync/test/queryplan.test.ts`), matching README.md.
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

## Security audit #804 mutation checks (issue #823)

All 13 tests added in `sync/test/sync.test.ts` under `describe("security audit #804: route authorization, isolation, and favorite artist caps")` were mutation-tested by breaking guarded behavior locally in `sync/src/index.ts` and verifying failure. Status-only assertions in `/devices` tests were tightened with error body and database checks.

| # | Test | Break in `sync/src/index.ts` | Observed failure |
|---|---|---|---|
| 1 | `/sync rejects favoriteArtistChanges exceeding 500 entries with 413` | delete `favoriteArtistChanges.length > MAX_CHANGES_PER_SYNC` check | `AssertionError: expected 200 to be 413` |
| 2 | `/sync rejects non-array favoriteArtistChanges with 400` | delete `!Array.isArray(favoriteArtistChanges)` check | `AssertionError: expected 500 to be 400` (unhandled `TypeError` mapping non-array) |
| 3 | `/sync rejects an over-long artistKey with 400` | raise `MAX_QUEUE_KEY_CHARS` to 1024 in `parseFavoriteArtistFields` | `AssertionError: expected 200 to be 400` |
| 4 | `/sync rejects an empty artistKey with 400` | delete `artistKey.length === 0` check | `AssertionError: expected 200 to be 400` |
| 5 | `/sync rejects negative deletedAt in favoriteArtistChanges with 400` | replace `optionalNonNegativeInt` with permissive int parse | `AssertionError: expected 200 to be 400` |
| 6 | `/sync rejects non-integer updatedAt in favoriteArtistChanges with 400` | replace `int(...)` with permissive parse | `AssertionError: expected 200 to be 400` |
| 7 | `GET /devices returns 401 when unauthenticated` | delete `if (!device) return 401` in `handleDevicesList` | `AssertionError: expected 500 to be 401` (`device.groupId` threw on null) |
| 8 | `GET /devices lists group devices and respects group boundaries` | drop `WHERE groupId = ?` filter in `handleDevicesList` | `AssertionError: expected [ ... ] to have a length of 2 but got 4` |
| 9 | `DELETE /devices/:id requires authentication and returns 401 without bearer token` | delete auth check in `handleDeviceRevoke` | `AssertionError: expected 404 to be 401` (unknown device id matched before auth) |
| 10 | `DELETE /devices/:id returns 404 for unknown device id` | delete `if (!target) return 404` in `handleDeviceRevoke` | `AssertionError: expected 500 to be 404` (`target.groupId` threw on null) |
| 11 | `DELETE /devices/:id rejects revoking a device in another group with 403` | delete `target.groupId !== device.groupId` 403 check in `handleDeviceRevoke` | `AssertionError: expected 200 to be 403` |
| 12 | `DELETE /devices/:id revokes a device in the same group and immediately rejects its sync requests with 401` | delete `UPDATE devices SET revokedAt` in `handleDeviceRevoke` | `AssertionError: expected 200 to be 401` (revoked device could still sync) |
| 13 | `purgeOldTombstones purges favorite_artists tombstones and counts them` | drop `deleteFavoritesResult.meta.changes` from `rowsPurged` | `AssertionError: expected +0 to be 1` |

## Time creep: none
No timers, sleeps or real-time waits in the suite. F2 uses a promise gate, not a delay.

## Leaked resources: none found
The only global patched, `env.DB.prepare` in F2, is restored in `finally`. Miniflare/D1 lifecycle
is owned by the pool and torn down per run. `vitest run --reporter=hanging-process` exited
cleanly with no hanging handles.

## Filed issues
None; every finding was local to test code.
