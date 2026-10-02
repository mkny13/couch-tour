# Security audit: sync backend (#475, part of #473)

Previous pass: #220 (commit 73a7564, which only touched `package-lock.json`; no report was written).
Diff the next pass against this file. Scope: `sync/` Worker, re-checked against D120 and D150–D154 after `git diff 73a7564..HEAD -- sync/` (changes in `src/index.ts`, `schema.sql`, tests, lockfile).
Line numbers refer to `sync/src/index.ts` at this PR.

## Findings fixed in this PR
| # | Finding | Fix | Test (fails without the fix) |
|---|---|---|---|
| 1 | `POST /pair/start` (unauthenticated) and `/pair/claim` stored `deviceName`/`platform` with no length cap and parsed an unbounded body, so one unauthenticated request could write a very large row. | `deviceLabels()` caps name at 128 / platform at 32 chars (400); `/pair/*` bodies capped at 4 KiB (413). | `/pair/start rejects an oversized body…`, `…over-long deviceName…`, `/pair/claim rejects an over-long platform` |
| 2 | `/sync` only enforced `MAX_SYNC_BODY_BYTES` through `Content-Length`; a chunked upload (no header) was read in full. | `readJson(request, maxBytes)` counts streamed bytes and cancels past the cap (413); the header check stays as a fast path. | `/sync enforces the body cap on a chunked upload…` |
| 3 | `.gitignore` covered only `sync/.dev.vars`. | Added `sync/.dev.vars.*`, `sync/.env`, `sync/.env.*`. | `git check-ignore sync/.env.local sync/.dev.vars.staging` (no repo check; low value) |
| 4 | Top-level `wrangler` pulled undici 7.29.0 / sharp 0.35.2 (advisories). | `npm update wrangler` (non-breaking): 4.134 → 4.146, lockfile only. | `npm audit` (see Dependencies) |

## Checks
| Check | Result | Evidence |
|---|---|---|
| Token hashing at rest (D120) | OK | Tokens/codes are random (`crypto.getRandomValues`, 256-bit token, `src/crypto.ts`), stored only as SHA-256 (`auth.ts`, `handlePairStart/Claim`); raw token returned once. |
| Two-slot rotation | OK | `authenticate` accepts `previousTokenHash` only while `previousTokenExpiresAt > now`; `handleSync` detects previous-token use. |
| Revocation takes effect immediately | OK | `authenticate` rejects `revokedAt !== null` on every request. |
| Routes needing auth | OK | `/sync`, `GET /devices`, `DELETE /devices/{id}` all 401 without a device; `DELETE` also checks same group (403). Open by design: `/health`, `/pair/start`, `/pair/claim`. |
| Rate limit on `/pair/start` (D150) | OK | `PAIR_START_LIMITER`, 5/10s/IP on the unauthenticated branch only; declared for prod and staging in `wrangler.toml`. |
| `/pair/claim` guessing | OK, noted | No attempt limiter; relies on 8 chars of a 32-symbol alphabet (~2^40) and a 10-minute TTL (D127). Accepted; a limiter would be a design change, not done here. Code input also length-capped now. |
| Request-size caps | Fixed (#1, #2) | `MAX_SYNC_BODY_BYTES` 2 MiB, `MAX_CHANGES_PER_SYNC` 500, field caps via `text()`; pair routes now capped. |
| Error bodies | OK | Top-level `catch` in `fetch` returns `{error:"internal error"}` and logs the exception; error messages are static strings or validation text, never tokens or stack traces. Tokens are never logged (`console.log` lines print counts/timings only). |
| CORS absent (D154) | OK | No `Access-Control-*` anywhere in `src/`. |
| Tracked `.env` / `.dev.vars` | OK | `git ls-files` finds none. |
| Secrets in `wrangler.toml` | OK | Only D1 `database_id`s (identifiers, not credentials), rate-limit namespace ids, cron. No `[vars]`. Deploy credentials live in GitHub Actions secrets, not the repo. |
| Outbound surface | OK | No `fetch(` call in `src/` apart from the `export default { fetch }` handler; no `connect()`/sockets. |
| `Env` bindings | OK | `src/types.ts`: only `DB` (D1) and `PAIR_START_LIMITER`. |
| SQL injection | OK | Every query uses `.prepare().bind()`; no string-built SQL. |

## Dependencies
| Check | Result | Evidence |
|---|---|---|
| `npm audit --omit=dev` | OK | `found 0 vulnerabilities` (the Worker has no runtime dependencies). |
| `npm audit` (all) | Filed as #483 | 5 high, dev-only: undici <7.29.1 and sharp <0.35.4 under `@cloudflare/vitest-pool-workers@0.22.0` (latest), which pins its own nested miniflare. `npm audit fix --force` would downgrade it to 0.8.30; no fixed release exists. Top-level `wrangler` is already on patched versions after this PR. |

## Not covered
- Live prod/staging behaviour was not probed; this is a source and lockfile audit.
