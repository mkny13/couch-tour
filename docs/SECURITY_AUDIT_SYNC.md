# Security audit: sync backend (#804, part of #801)

Previous pass: #475 (commit `c314a1e2`, 2026-10-02).
Diff this pass against commit `c314a1e2` (`git diff c314a1e2..HEAD -- sync/`).
Scope: `sync/` Cloudflare Worker backend, re-checked against D120 and D150–D154 across changes in `src/index.ts`, `schema.sql`, `types.ts`, test suite, and lockfile.
Line numbers refer to `sync/src/index.ts` at this PR.

## Findings fixed in this PR
| # | Finding | Fix | Test (fails without the fix) |
|---|---|---|---|
| 1 | Dev-only `undici` dependency under `@cloudflare/vitest-plugin` / nested `miniflare` had 1 high severity vulnerability in version range 7.0.0–7.30.0 (GHSA-4qv8-2p9j-2685, GHSA-f72v-6w45-q472, GHSA-xjj6-f33q-929c, GHSA-4w7w-5xrc-9h26, GHSA-2m5v-pch6-74hx, GHSA-qrxm-fqhp-jwvm, GHSA-9fm3-97px-rjf6, GHSA-55wc-3pg8-jfw6). | `npm update undici` (non-breaking, lockfile only within existing `"undici": "^7.29.1"` override): 7.30.0 → 7.30.2. | `npm audit` (found 0 vulnerabilities, see Dependencies) |

## Router at HEAD (routes & handlers)
Every route in the router at HEAD (lines 836–853) and the scheduled purge entrypoint (line 883), verified for authentication, body/field limits, SQL binding, and cross-group isolation:

| Route | Status / Change since `c314a1e2` | Auth | Size & Field Caps | SQL Binding & Group Isolation |
|---|---|---|---|---|
| `GET /health` | Unchanged | None (open by design) | N/A (no request body) | OK: Static query `SELECT 1` to test D1 connectivity for deploy gates. |
| `POST /pair/start` | Unchanged | Optional (open for new group bootstrap, authenticated for adding to existing group) | `readJson` 4 KiB cap (`MAX_PAIR_BODY_BYTES`, 413). `deviceName` capped at 128 chars, `platform` at 32 chars (400). | OK: Rate-limited via `PAIR_START_LIMITER` (5/10s/IP) on unauthenticated bootstrap branch. All `INSERT` queries bound via `.prepare().bind()`. |
| `POST /pair/claim` | Unchanged | None (unauthenticated; authenticated by single-use 8-char base32 code, 10-minute TTL) | `readJson` 4 KiB cap (`MAX_PAIR_BODY_BYTES`, 413). `code` capped at 64 chars, `deviceName` at 128, `platform` at 32 (400). | OK: `SELECT`, `UPDATE` and `INSERT` all bound with SHA-256 code hash. Raw token returned once. |
| `POST /sync` | Changed: added `favoriteArtistChanges` push/pull handling (`parseFavoriteArtistFields`, `applyIncomingFavoriteArtistChanges`) | Required (401 without valid bearer token) | `readJson` 2 MiB cap (`MAX_SYNC_BODY_BYTES`, 413). `changes` <= 500 (413). `favoriteArtistChanges` <= 500 (413), must be array (400). Field caps: `queueKey` / `artistKey` <= 512 chars (non-empty); `title`, `subtitle`, `trackTitle`, `artist` <= 1024 chars; non-negative ints for `trackIndex`, `positionMs`, `deletedAt` (400). | OK: Strictly scoped to caller's `device.groupId`. Key existence queries chunked to 90 bound parameters (`KEY_LOOKUP_CHUNK` <= 100 D1 limit). Every query uses `.prepare().bind()`. |
| `GET /devices` | Unchanged | Required (401 without valid bearer token) | N/A (no request body) | OK: Scoped to caller's `device.groupId` and `revokedAt IS NULL`. Never returns token hashes. |
| `DELETE /devices/{id}` | Unchanged | Required (401 without valid bearer token) | N/A (no request body) | OK: Looks up target device and enforces `target.groupId === device.groupId` (403 if in another group, 404 if not found). Bound `UPDATE devices SET revokedAt`. Revocation takes immediate effect on subsequent calls. |
| `scheduled` (`purgeOldTombstones`) | Changed: added `favorite_artists` tombstones to 180-day retention purge batch | Cron Trigger (internal event, not HTTP-facing) | N/A | OK: Queries candidate max seqs across `progress` and `favorite_artists` via index-backed `deletedAt < ?` range query. Bumps `retentionFloorSeq` in same batch before deleting tombstones. |

## Checks
| Check | Result | Evidence |
|---|---|---|
| Token hashing at rest (D120) | OK | Tokens/codes generated via `crypto.getRandomValues` (256-bit token, `src/crypto.ts`), stored exclusively as SHA-256 hex digest (`auth.ts`, `handlePairStart`, `handlePairClaim`); raw token returned once over HTTPS. |
| Two-slot rotation | OK | `authenticate` accepts `previousTokenHash` only while `previousTokenExpiresAt > now`; `rotateTokenIfDue` rotates tokens older than 90 days or when previous token was used, granting 48h grace. |
| Revocation takes effect immediately | OK | `authenticate` verifies `revokedAt !== null` on every request. Revoking a device immediately causes 401 on next call (tested in `sync.test.ts`). |
| Routes needing auth | OK | `/sync`, `GET /devices`, `DELETE /devices/{id}` all return 401 without a valid device token; `DELETE` enforces group boundary (403 for cross-group target). Open by design: `/health`, `/pair/start`, `/pair/claim`. |
| Rate limit on `/pair/start` (D150) | OK | `PAIR_START_LIMITER`, 5/10s per `CF-Connecting-IP` on the unauthenticated bootstrap branch; declared for prod and staging in `wrangler.toml`. |
| `/pair/claim` guessing | OK, noted | Code space: 8 chars of 32-symbol alphabet (~10^12 possibilities) against 10-minute TTL (D127). Code length capped at 64 chars before hashing. |
| Request-size & field caps | OK | `MAX_SYNC_BODY_BYTES` (2 MiB), `MAX_CHANGES_PER_SYNC` (500) on both `changes` and `favoriteArtistChanges`; `MAX_PAIR_BODY_BYTES` (4 KiB); string and integer limits enforced via `text()`, `int()`, and `optionalNonNegativeInt()`. |
| Error bodies | OK | Top-level catch in `fetch` logs unhandled exceptions and returns generic `{error:"internal error"}` (500). Validation errors return specific field diagnostic strings; credentials and internal traces never leak to client. |
| Logging bounds | OK | `console.log` lines record timings and item counts only (`handleSync` line 518, `scheduled` line 886). Tokens, codes, and request bodies are never logged. |
| CORS absent (D154) | OK | No `Access-Control-*` headers anywhere in `sync/src/`. Clients are native mobile and desktop apps. |
| Tracked `.env` / `.dev.vars` | OK | `git ls-files sync` returns no `.env*` or `.dev.vars*` files. All variants ignored in `.gitignore`. |
| Secrets in `wrangler.toml` | OK | Contains only D1 database IDs, rate-limit namespace IDs, cron triggers, and environment names. No `[vars]` or secrets. Deploy tokens managed in GitHub Actions secrets. |
| Outbound surface | OK | No outbound `fetch(` or network calls in `sync/src/` (only the exported Worker `fetch` handler). |
| `Env` bindings | OK | `sync/src/types.ts`: only `DB` (D1Database) and `PAIR_START_LIMITER` (RateLimit). |
| SQL injection | OK | Every query in `sync/src/index.ts` and `sync/src/auth.ts` uses `.prepare().bind()`; dynamic `IN (?,?,...)` placeholders generated from chunk sizes with all parameters bound. |

## Dependencies
| Check | Result | Evidence |
|---|---|---|
| `npm audit --omit=dev` | OK | `found 0 vulnerabilities` (the Worker backend has 0 production runtime dependencies). |
| `npm audit` (all) | OK | `found 0 vulnerabilities`. Previously reported 1 high severity advisory on `undici` (7.0.0–7.30.0) under nested `miniflare`. Updated `undici` to 7.30.2 via `npm update undici` in `package-lock.json` (lockfile only, within existing `"undici": "^7.29.1"` override). Prior advisory issue #483 remains resolved. |

## Not covered
- Live production and staging instances were not probed directly; this is a source, schema, query plan, and lockfile audit.
