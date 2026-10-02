# Codebase health: sync backend & scripts (#490, part of #487)

Scope: `sync/src`, `scripts/*.py`, `scripts/*.sh`, `scripts/smoke/*.sh`. Security was audited separately (#475, #476) and is not repeated here. Next pass: diff against this file.

## Leaks

| Where | Finding | Action |
|---|---|---|
| `sync/src/index.ts` (all D1 calls) | Every `prepare(...).run/first/all` and `batch` is awaited; no floating promises. The only `waitUntil` (`scheduled`) wraps the purge promise. | None needed |
| `sync/src/index.ts` `readJson` | Request body reader is cancelled on the over-cap path; otherwise drained to `done`. No response bodies are left unread. | None needed |
| `sync/src` module scope | Only constants; no per-request state at module scope. | None needed |
| `scripts/beta_guard.py:224` | `open()` is inside `with`. | None needed |
| `scripts/uat-server.py:~650` | `HTTPServer(...).serve_forever()` runs for the process lifetime; the process exit closes the socket. | Left as is (CLI entry point) |
| `scripts/*.py` | No `subprocess.Popen`; no `open()` outside `with`. | None needed |

No leaks found.

## Dead code

| Where | Finding | Action |
|---|---|---|
| `sync/src/index.ts` `handleSync` | Declared-`Content-Length` pre-check duplicated the one inside `readJson`, with the same 413 message. | Removed the duplicate |
| `sync/src/index.ts` (comment in `handleSync`) | Stale reference to "line 331". | Reworded |
| `sync/src` exports | `applyIncomingChanges` and `purgeOldTombstones` are exported only for `sync/test/sync.test.ts`; all others used. | Kept |
| `scripts/*.py` | AST scan for top-level functions referenced once: none. | None |
| `scripts/smoke/*.sh`, `scripts/*.sh` | Only apparent orphans are `android_run_*` journey functions, which are dispatched by journey id. | Kept |

## Complexity

| Where | Finding | Action |
|---|---|---|
| `sync/src/index.ts` `handleSync` (was ~115 lines) | Mixed request parsing/validation, token rotation, cursor check, pull. | Extracted `parseSyncRequest` and `rotateTokenIfDue`; behavior unchanged, 18 sync tests pass |
| `scripts/uat-server.py` `update` (72 lines, longest in `scripts/`) | Locate, file-bug and rewrite phases interleaved. | Extracted `locate_item` and `rewrite_item`; UAT server tests pass |

Splitting `index.ts` into modules is out of scope for this pass; it is still 700+ lines with the router at the bottom.

## Longest functions (after this pass, approximate lines)

| # | Function | File | Lines |
|---|---|---|---|
| 1 | `run_guard` | `scripts/beta_guard.py` | 67 |
| 2 | `handleSync` | `sync/src/index.ts` | ~65 |
| 3 | `applyIncomingChanges` | `sync/src/index.ts` | ~105 (mostly comments and SQL) |
| 4 | `discover_live_phish` | `scripts/generate_heuristic_matches.py` | 47 |
| 5 | `update` | `scripts/uat-server.py` | ~40 |
| 6 | `parseProgressFields` | `sync/src/index.ts` | 25 |
| 7 | `purgeOldTombstones` | `sync/src/index.ts` | 30 |
| 8 | `parse_releases` | `scripts/beta_guard.py` | 33 |
| 9 | `main` | `scripts/generate_heuristic_matches.py` | 31 |
| 10 | `main` | `scripts/uat-server.py` | 29 |

Test-only functions and `run-android.sh` journey functions are excluded.
