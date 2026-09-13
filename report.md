<!-- mahler:agent -->
## Security Audit Findings (Cloudflare Worker & D1 Sync)

I have completed the security audit of the sync backend. Overall, the architecture and implementation are solid, but two edge cases were identified and fixed to ensure perfect resilience against race conditions and network drops.

### 1. Token verification review (Checked)
- **Status**: Secure.
- **Findings**: Bearer token lookup via `tokenHash` and `previousTokenHash` correctly handles grace periods. Revocation is instantaneous (`revokedAt !== null`). Updating `lastSeenAt` is the only side-effect and operates correctly on the matched device.

### 2. Cryptographic pairing validation (Checked & Fixed)
- **Status**: Secure (Fixed race condition).
- **Findings**: 
  - Tokens and pairing codes are generated securely using `crypto.getRandomValues`. The base32 mapping has no modulo bias (256 divides evenly by 32).
  - **Issue**: `handlePairClaim` checked `claimedAt === null` and then used `DB.batch` to insert the device and update `claimedAt`. Since D1 lacks interactive transactions, two simultaneous claims for the same code could both read `claimedAt === null` and both insert a device, creating two valid devices for one single-use code.
  - **Fix**: Changed `handlePairClaim` to perform an atomic `UPDATE pairings ... WHERE claimedAt IS NULL RETURNING id` *before* inserting the device.

### 3. Input sanitization (Checked)
- **Status**: Secure.
- **Findings**: `parseProgressFields` enforces strict bounds checking (`typeof`, `length`, `isSafeInt`). D1 operations exclusively use parameterized bindings (`.bind()`), preventing SQL injection. Hard limits on `MAX_SYNC_BODY_BYTES` (2MB) and `MAX_CHANGES_PER_SYNC` (500) mitigate resource exhaustion.

### 4. Rate-limiting (Checked)
- **Status**: Secure.
- **Findings**: The unauthenticated `POST /pair/start` endpoint is correctly protected by `PAIR_START_LIMITER` (5 requests per 10s per IP) in `wrangler.toml`. Authenticated endpoints naturally rely on token verification, avoiding the need for strict IP limits.

### 5. Replay/abuse resistance (Checked & Fixed)
- **Status**: Secure (Fixed token rotation edge case).
- **Findings**:
  - `FUTURE_CLOCK_CLAMP_MS` and `TOMBSTONE_RETENTION_MS` behave as expected.
  - **Issue**: During token rotation (90 days), if the client's network drops before receiving the `X-Sync-Token-Rotated` header, they retry using the old token. The server would accept it (within 48h grace period) but *would not resend the new token* because `tokenIssuedAt` was already updated in the database and the server only stores the token hash. This would permanently lock the client out after 48h.
  - **Fix**: Updated the `handleSync` rotation logic to detect if the client is authenticating using the `previousTokenHash`. If so, it performs a new token rotation, ensuring the client gets a fresh token delivered successfully.
