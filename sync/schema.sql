-- Couch Tour sync backend schema (D1 / SQLite). See DECISIONS.md's sync iteration for the
-- design this implements. Applied with:
--   npm run db:migrate:local   (local dev)
--   npm run db:migrate:remote  (the real Cloudflare D1 database, once one exists)

-- A pair (or more) of devices that sync progress with each other. Created implicitly by the
-- first device to start a pairing.
CREATE TABLE groups (
    id TEXT PRIMARY KEY,
    createdAt INTEGER NOT NULL
);

-- One row per paired device. tokenHash is SHA-256 of the device's current bearer token — the
-- token itself is never stored, so a database leak yields no working credentials.
-- previousTokenHash/previousTokenExpiresAt hold the prior token through a 48h grace window
-- after rotation, so a client that crashes between receiving a new token and persisting it
-- isn't locked out — see the rotation logic in src/index.ts.
CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    groupId TEXT NOT NULL REFERENCES groups(id),
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    tokenHash TEXT NOT NULL UNIQUE,
    tokenIssuedAt INTEGER NOT NULL,
    previousTokenHash TEXT UNIQUE,
    previousTokenExpiresAt INTEGER,
    createdAt INTEGER NOT NULL,
    lastSeenAt INTEGER,
    revokedAt INTEGER
);
CREATE INDEX devices_previousTokenHash ON devices(previousTokenHash);

-- A short-lived pairing code, single-use, looked up by the code alone (D127) — no separate
-- pairing id, so a human can type the whole thing. codeHash is SHA-256 of the code shown on
-- screen, same reasoning as devices.tokenHash: a leaked database row can't be used to claim a
-- pairing after the fact. No per-row attempt counter either — the code space (8 base32
-- characters, ~10^12 possibilities) against a 10-minute TTL is the actual defense.
CREATE TABLE pairings (
    id TEXT PRIMARY KEY,
    groupId TEXT NOT NULL REFERENCES groups(id),
    codeHash TEXT NOT NULL,
    expiresAt INTEGER NOT NULL,
    claimedAt INTEGER
);
CREATE INDEX pairings_codeHash ON pairings(codeHash);

-- The synced progress rows: the 11 client columns verbatim (queueKey, title, subtitle,
-- artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed, artist), plus
-- deletedAt (the tombstone both clients already write locally), plus two server-only fields:
-- seq, the monotonic per-group ordering cursor sync clients page through, and
-- lastWriterDeviceId, kept for display/debugging only — conflict resolution itself is
-- row-level last-write-wins on updatedAt, with seq as the tie-break.
CREATE TABLE progress (
    groupId TEXT NOT NULL REFERENCES groups(id),
    queueKey TEXT NOT NULL,
    title TEXT NOT NULL,
    subtitle TEXT NOT NULL,
    artUrl TEXT,
    trackIndex INTEGER NOT NULL,
    positionMs INTEGER NOT NULL,
    trackTitle TEXT NOT NULL,
    updatedAt INTEGER NOT NULL,
    finished INTEGER NOT NULL,
    dismissed INTEGER NOT NULL,
    artist TEXT NOT NULL,
    deletedAt INTEGER,
    seq INTEGER NOT NULL,
    lastWriterDeviceId TEXT,
    PRIMARY KEY (groupId, queueKey)
);
CREATE INDEX progress_seq ON progress(groupId, seq);

-- The seq counter itself, one row per group. Allocated with a single
-- `UPDATE seqs SET next = next + ? WHERE groupId = ? RETURNING next` — D1 has no interactive
-- transactions, so the counter bump and the row writes it labels both go in one db.batch()
-- rather than a read-then-write across two round trips, which would race.
--
-- retentionFloorSeq is the seq below which a client's `since` cursor can no longer be
-- trusted, because tombstoned rows below it may have been purged already — a stale-cursor
-- client would then miss a delete instead of seeing it, and silently resurrect the row on its
-- next push. `since` is a seq value, not a timestamp, so this floor has to live on the same
-- scale rather than being compared against wall-clock time. No purge job exists yet in this
-- MVP, so the floor stays 0 (nothing purged, every cursor still trustworthy) until one is
-- built to raise it — see the 180-day tombstone-purge note in DECISIONS.md.
CREATE TABLE seqs (
    groupId TEXT PRIMARY KEY REFERENCES groups(id),
    next INTEGER NOT NULL,
    retentionFloorSeq INTEGER NOT NULL DEFAULT 0
);
