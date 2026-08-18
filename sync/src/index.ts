import { authenticate } from "./auth";
import { randomId, randomPairingCode, randomToken, sha256Hex } from "./crypto";
import type { DeviceRow, Env, ProgressFields, ProgressRow } from "./types";

const PAIRING_TTL_MS = 10 * 60 * 1000;
const TOKEN_ROTATION_AGE_MS = 90 * 24 * 60 * 60 * 1000;
const TOKEN_GRACE_MS = 48 * 60 * 60 * 1000;
const FUTURE_CLOCK_CLAMP_MS = 24 * 60 * 60 * 1000;
const TOMBSTONE_RETENTION_MS = 180 * 24 * 60 * 60 * 1000;

/**
 * Request-shape limits. Workers will happily hand us a 100 MB body (that's the account-plan
 * default), and `changes` used to be an unbounded array cast straight to `ProgressFields[]`
 * and written to D1 — so these are the only thing standing between a malformed or hostile
 * push and the database.
 *
 * MAX_CHANGES_PER_SYNC is a product limit, not a platform one: both clients chunk their
 * pushes below it (see `MAX_PUSH_BATCH` on either side), so a client only trips this by
 * being broken or hostile. KEY_LOOKUP_CHUNK is the platform one — D1 allows at most 100
 * bound parameters per query, and the pre-push existence lookup binds one per queueKey plus
 * the groupId. Before chunking, any push of 100+ rows died on `D1_ERROR: too many SQL
 * variables`, which a first pair with a long listening history hit for real.
 */
const MAX_SYNC_BODY_BYTES = 2 * 1024 * 1024;
const MAX_CHANGES_PER_SYNC = 500;
const KEY_LOOKUP_CHUNK = 90;

/** Bounds on individual field sizes, so one row can't approach D1's 2 MB per-row ceiling. */
const MAX_QUEUE_KEY_CHARS = 512;
const MAX_TEXT_CHARS = 1024;

function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

function badRequest(message: string): Response {
  return json({ error: message }, 400);
}

function tooLarge(message: string): Response {
  return json({ error: message }, 413);
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

// -------------------------------------------------------------- pairing

interface PairStartBody {
  deviceName?: unknown;
  platform?: unknown;
}

/**
 * Bootstraps a new group + device when called with no token (the very first pairing), or
 * mints a new pairing code inside the caller's existing group when called with one (adding a
 * further device). Either way it returns a short-lived code for the other device to claim.
 */
async function handlePairStart(request: Request, env: Env): Promise<Response> {
  const body = await readJson<PairStartBody>(request);
  const caller = await authenticate(request, env);

  let groupId: string;
  let bootstrapped: { deviceId: string; deviceToken: string } | null = null;

  if (caller) {
    groupId = caller.groupId;
  } else {
    // Only the unauthenticated branch is limited: this is the one path where a caller with no
    // credentials makes the database grow, four rows at a time (D150). An authenticated device
    // minting an extra pairing code is already bounded by owning a token we can revoke.
    const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
    const { success } = await env.PAIR_START_LIMITER.limit({ key: `pair-start:${ip}` });
    if (!success) return json({ error: "too many pairing attempts; try again shortly" }, 429);

    if (typeof body?.deviceName !== "string" || typeof body?.platform !== "string") {
      return badRequest("deviceName and platform are required to start a new group");
    }
    const now = Date.now();
    groupId = randomId();
    const deviceId = randomId();
    const token = randomToken();
    const tokenHash = await sha256Hex(token);

    await env.DB.batch([
      env.DB.prepare("INSERT INTO groups (id, createdAt) VALUES (?, ?)").bind(groupId, now),
      env.DB.prepare("INSERT INTO seqs (groupId, next) VALUES (?, 1)").bind(groupId),
      env.DB.prepare(
        `INSERT INTO devices (id, groupId, name, platform, tokenHash, tokenIssuedAt, createdAt)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      ).bind(deviceId, groupId, body.deviceName, body.platform, tokenHash, now, now),
    ]);

    bootstrapped = { deviceId, deviceToken: token };
  }

  const now = Date.now();
  const code = randomPairingCode();
  const codeHash = await sha256Hex(code);
  const expiresAt = now + PAIRING_TTL_MS;

  await env.DB.prepare(
    "INSERT INTO pairings (id, groupId, codeHash, expiresAt) VALUES (?, ?, ?, ?)"
  )
    .bind(randomId(), groupId, codeHash, expiresAt)
    .run();

  return json({ code, expiresAt, ...bootstrapped });
}

interface PairClaimBody {
  code?: unknown;
  deviceName?: unknown;
  platform?: unknown;
}

interface PairingRow {
  id: string;
  groupId: string;
  codeHash: string;
  expiresAt: number;
  claimedAt: number | null;
}

/**
 * The second device redeems the code the first device showed, and gets its own token back.
 * Looked up by the code alone — no separate pairing id, so a human can type the whole thing
 * (D127 supersedes D122's pairingId+code design, which a UUID makes untypeable). Brute-forcing
 * is impractical on its own terms: 8 base32 characters is roughly 10^12 possibilities against
 * a 10-minute window, so no separate attempt-counting is needed on top of that.
 */
async function handlePairClaim(request: Request, env: Env): Promise<Response> {
  const body = await readJson<PairClaimBody>(request);
  if (
    typeof body?.code !== "string" ||
    typeof body?.deviceName !== "string" ||
    typeof body?.platform !== "string"
  ) {
    return badRequest("code, deviceName, and platform are required");
  }

  const codeHash = await sha256Hex(body.code);
  const pairing = await env.DB.prepare("SELECT * FROM pairings WHERE codeHash = ?")
    .bind(codeHash)
    .first<PairingRow>();
  if (!pairing) return json({ error: "incorrect code" }, 401);

  const now = Date.now();
  if (pairing.claimedAt !== null) return json({ error: "pairing already claimed" }, 410);
  if (now > pairing.expiresAt) return json({ error: "pairing expired" }, 410);

  const deviceId = randomId();
  const token = randomToken();
  const tokenHash = await sha256Hex(token);

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO devices (id, groupId, name, platform, tokenHash, tokenIssuedAt, createdAt)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).bind(deviceId, pairing.groupId, body.deviceName, body.platform, tokenHash, now, now),
    env.DB.prepare("UPDATE pairings SET claimedAt = ? WHERE id = ?").bind(now, pairing.id),
  ]);

  return json({ deviceId, deviceToken: token });
}

// ----------------------------------------------------------------- sync

interface SyncBody {
  since?: unknown;
  changes?: unknown;
}

// ------------------------------------------------------- incoming change validation

function isSafeInt(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value);
}

function text(value: unknown, field: string, max: number): string {
  if (typeof value !== "string") throw new ValidationError(`${field} must be a string`);
  if (value.length > max) throw new ValidationError(`${field} exceeds ${max} characters`);
  return value;
}

function optionalText(value: unknown, field: string, max: number): string | null {
  if (value === null || value === undefined) return null;
  return text(value, field, max);
}

function int(value: unknown, field: string): number {
  if (!isSafeInt(value)) throw new ValidationError(`${field} must be an integer`);
  return value;
}

function optionalInt(value: unknown, field: string): number | null {
  if (value === null || value === undefined) return null;
  return int(value, field);
}

function bool(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") throw new ValidationError(`${field} must be a boolean`);
  return value;
}

/** Thrown by the field helpers above and turned into a 400 by `handleSync`. */
class ValidationError extends Error {}

/**
 * Validates one incoming change, field by field. `handleSync` used to cast the whole array
 * `as ProgressFields[]` and hand it to D1 — a lie the type system happily accepted, since
 * nothing had actually checked the wire data. A row with a numeric `title` or a missing
 * `queueKey` would then either write nonsense or throw inside `.bind()` and 500 the endpoint.
 *
 * Absent nullable fields are normalized to explicit null here rather than in
 * `applyIncomingChanges`: both clients omit null-valued optionals by default
 * (kotlinx.serialization only writes properties differing from their default without
 * `encodeDefaults`; Swift's synthesized `Codable` uses `encodeIfPresent`), and D1's `.bind()`
 * rejects `undefined` outright.
 */
function parseProgressFields(value: unknown, index: number): ProgressFields {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ValidationError(`changes[${index}] must be an object`);
  }
  const c = value as Record<string, unknown>;
  const at = (field: string) => `changes[${index}].${field}`;

  const queueKey = text(c.queueKey, at("queueKey"), MAX_QUEUE_KEY_CHARS);
  if (queueKey.length === 0) throw new ValidationError(`${at("queueKey")} must not be empty`);

  return {
    queueKey,
    title: text(c.title, at("title"), MAX_TEXT_CHARS),
    subtitle: text(c.subtitle, at("subtitle"), MAX_TEXT_CHARS),
    artUrl: optionalText(c.artUrl, at("artUrl"), MAX_TEXT_CHARS),
    trackIndex: int(c.trackIndex, at("trackIndex")),
    positionMs: int(c.positionMs, at("positionMs")),
    trackTitle: text(c.trackTitle, at("trackTitle"), MAX_TEXT_CHARS),
    updatedAt: int(c.updatedAt, at("updatedAt")),
    finished: bool(c.finished, at("finished")),
    dismissed: bool(c.dismissed, at("dismissed")),
    artist: text(c.artist, at("artist"), MAX_TEXT_CHARS),
    deletedAt: optionalInt(c.deletedAt, at("deletedAt")),
  };
}

/**
 * Push-then-pull in one round trip. Incoming changes are merged with row-level
 * last-write-wins on `updatedAt` (ties go to whichever arrives at the server later, i.e. gets
 * the higher `seq` — see DECISIONS.md); everything the caller hasn't seen yet, including its
 * own just-applied pushes, comes back in `changes`.
 */
async function handleSync(request: Request, env: Env): Promise<Response> {
  const device = await authenticate(request, env);
  if (!device) return json({ error: "unauthorized" }, 401);

  // Cheap pre-read rejection: a declared oversize body never gets parsed, let alone reaches
  // D1. Content-Length is absent on a chunked upload, so it's a fast path rather than the
  // actual guarantee — MAX_CHANGES_PER_SYNC below is what bounds the work either way.
  const declaredLength = Number(request.headers.get("content-length") ?? "");
  if (Number.isFinite(declaredLength) && declaredLength > MAX_SYNC_BODY_BYTES) {
    return tooLarge(`request body exceeds ${MAX_SYNC_BODY_BYTES} bytes`);
  }

  const body = await readJson<SyncBody>(request);
  if (!isSafeInt(body?.since) || !Array.isArray(body?.changes)) {
    return badRequest("since (integer) and changes (array) are required");
  }
  const since = body.since;
  if (since < 0) return badRequest("since must not be negative");

  if (body.changes.length > MAX_CHANGES_PER_SYNC) {
    return tooLarge(`changes exceeds ${MAX_CHANGES_PER_SYNC} entries; push in smaller batches`);
  }

  let incoming: ProgressFields[];
  try {
    incoming = body.changes.map(parseProgressFields);
  } catch (e) {
    if (e instanceof ValidationError) return badRequest(e.message);
    throw e;
  }

  const now = Date.now();

  const cursor = await env.DB.prepare("SELECT next, retentionFloorSeq FROM seqs WHERE groupId = ?")
    .bind(device.groupId)
    .first<{ next: number; retentionFloorSeq: number }>();
  if (!cursor) throw new Error(`no seq counter for group ${device.groupId}`);

  // `since` is a seq value, not a timestamp — it can only be compared against another seq.
  // retentionFloorSeq rises when a (not-yet-built) purge job removes old tombstones; a device
  // whose cursor sits below it can no longer trust that gap, since a delete it missed might
  // already be gone from this table. Today the floor never moves, so this can't yet fire in
  // production — it's here so the contract exists and is tested before the purge job does.
  //
  // since === 0 is a fresh client doing a full resync already — it has no prior gap to
  // distrust, so the floor never applies to it. Without this guard, a brand-new pairing would
  // 410-loop forever the moment the floor ever moves off 0, since 0 is less than anything
  // positive.
  if (since > 0 && since < cursor.retentionFloorSeq) {
    return json({ error: "cursor too old; full resync required" }, 410);
  }

  const responseHeaders: Record<string, string> = {};
  if (now - device.tokenIssuedAt > TOKEN_ROTATION_AGE_MS) {
    const newToken = randomToken();
    const newHash = await sha256Hex(newToken);
    await env.DB.prepare(
      `UPDATE devices
       SET previousTokenHash = tokenHash, previousTokenExpiresAt = ?,
           tokenHash = ?, tokenIssuedAt = ?
       WHERE id = ?`
    )
      .bind(now + TOKEN_GRACE_MS, newHash, now, device.id)
      .run();
    responseHeaders["X-Sync-Token-Rotated"] = newToken;
  }

  if (incoming.length > 0) {
    await applyIncomingChanges(env, device, incoming, now);
  }

  const rows = await env.DB.prepare(
    "SELECT * FROM progress WHERE groupId = ? AND seq > ? ORDER BY seq ASC"
  )
    .bind(device.groupId, since)
    .all<ProgressRow>();

  const head = await env.DB.prepare("SELECT next FROM seqs WHERE groupId = ?")
    .bind(device.groupId)
    .first<{ next: number }>();
  const currentSeq = head ? head.next - 1 : since;

  return json(
    {
      seq: Math.max(currentSeq, since),
      changes: (rows.results ?? []).map(toWireRow),
    },
    200,
    responseHeaders
  );
}

function toWireRow(row: ProgressRow): ProgressFields {
  const { groupId: _groupId, seq: _seq, lastWriterDeviceId: _writer, ...fields } = row;
  return {
    ...fields,
    finished: Boolean(fields.finished),
    dismissed: Boolean(fields.dismissed),
  };
}

async function applyIncomingChanges(
  env: Env,
  device: DeviceRow,
  incoming: ProgressFields[],
  now: number
): Promise<void> {
  // Chunked because D1 allows at most 100 bound parameters per query and this binds one per
  // key plus the groupId. A single `IN (...)` over every incoming key 500'd the whole push
  // the moment a client had 100+ changed rows — which is exactly what a first pair with a
  // long listening history looks like.
  const keys = incoming.map((c) => c.queueKey);
  const existingByKey = new Map<string, number>();
  for (let i = 0; i < keys.length; i += KEY_LOOKUP_CHUNK) {
    const chunk = keys.slice(i, i + KEY_LOOKUP_CHUNK);
    const placeholders = chunk.map(() => "?").join(",");
    const existingRows = await env.DB.prepare(
      `SELECT queueKey, updatedAt FROM progress WHERE groupId = ? AND queueKey IN (${placeholders})`
    )
      .bind(device.groupId, ...chunk)
      .all<{ queueKey: string; updatedAt: number }>();
    for (const r of existingRows.results ?? []) existingByKey.set(r.queueKey, r.updatedAt);
  }

  const accepted = incoming
    .map((change) => {
      // A badly clock-skewed device can't permanently pin a row into the future.
      const updatedAt = change.updatedAt > now + FUTURE_CLOCK_CLAMP_MS ? now : change.updatedAt;
      // Normalize absent nullable fields to explicit null. Both clients omit null-valued
      // optionals by default rather than sending them — kotlinx.serialization only writes
      // properties differing from their default unless `encodeDefaults` is set, and Swift's
      // synthesized `Codable` uses `encodeIfPresent` for Optionals. D1's `.bind()` rejects
      // `undefined` outright, so an omitted `artUrl`/`deletedAt` threw and the whole push
      // 500'd. Both clients now send explicit nulls, but the server accepting either shape is
      // the actual fix: a client that gets this wrong should not be able to 500 the endpoint.
      return {
        ...change,
        updatedAt,
        artUrl: change.artUrl ?? null,
        deletedAt: change.deletedAt ?? null,
      };
    })
    .filter((change) => {
      const existingUpdatedAt = existingByKey.get(change.queueKey);
      // >= rather than >: on an exact tie, whichever push reaches the server wins, since it
      // is — by definition — the one arriving now. That's the seq tie-break in practice.
      return existingUpdatedAt === undefined || change.updatedAt >= existingUpdatedAt;
    });

  if (accepted.length === 0) return;

  const bumped = await env.DB.prepare(
    "UPDATE seqs SET next = next + ? WHERE groupId = ? RETURNING next"
  )
    .bind(accepted.length, device.groupId)
    .first<{ next: number }>();
  if (!bumped) throw new Error(`no seq counter for group ${device.groupId}`);
  const firstSeq = bumped.next - accepted.length;

  const statements = accepted.map((change, i) =>
    env.DB.prepare(
      `INSERT INTO progress
         (groupId, queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle,
          updatedAt, finished, dismissed, artist, deletedAt, seq, lastWriterDeviceId)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (groupId, queueKey) DO UPDATE SET
         title = excluded.title, subtitle = excluded.subtitle, artUrl = excluded.artUrl,
         trackIndex = excluded.trackIndex, positionMs = excluded.positionMs,
         trackTitle = excluded.trackTitle, updatedAt = excluded.updatedAt,
         finished = excluded.finished, dismissed = excluded.dismissed, artist = excluded.artist,
         deletedAt = excluded.deletedAt, seq = excluded.seq,
         lastWriterDeviceId = excluded.lastWriterDeviceId`
    ).bind(
      device.groupId,
      change.queueKey,
      change.title,
      change.subtitle,
      change.artUrl,
      change.trackIndex,
      change.positionMs,
      change.trackTitle,
      change.updatedAt,
      change.finished ? 1 : 0,
      change.dismissed ? 1 : 0,
      change.artist,
      change.deletedAt,
      firstSeq + i,
      device.id
    )
  );

  await env.DB.batch(statements);
}

// -------------------------------------------------------------- retention

/**
 * Raises `retentionFloorSeq` before deleting the tombstones it covers, per group, in one
 * `env.DB.batch()` each — never the other order. A client that read the old floor and is
 * mid-request when the delete alone had already landed could pull a `since` that's now a lie:
 * a gap it can no longer trust exists (D121), but nothing yet told it to distrust it. Raising
 * the floor first means that gap always 410s (D126's `since = 0` exemption still applies to
 * anyone doing a full resync anyway) rather than silently under-syncing.
 */
async function purgeOldTombstones(
  env: Env,
  now: number
): Promise<{ groupsPurged: number; rowsPurged: number }> {
  const cutoff = now - TOMBSTONE_RETENTION_MS;
  const candidates = await env.DB.prepare(
    `SELECT groupId, MAX(seq) as maxSeq, COUNT(*) as count
     FROM progress
     WHERE deletedAt IS NOT NULL AND deletedAt < ?
     GROUP BY groupId`
  )
    .bind(cutoff)
    .all<{ groupId: string; maxSeq: number; count: number }>();

  const groups = candidates.results ?? [];
  let rowsPurged = 0;

  for (const group of groups) {
    await env.DB.batch([
      env.DB.prepare(
        "UPDATE seqs SET retentionFloorSeq = MAX(retentionFloorSeq, ?) WHERE groupId = ?"
      ).bind(group.maxSeq, group.groupId),
      env.DB.prepare(
        "DELETE FROM progress WHERE groupId = ? AND deletedAt IS NOT NULL AND deletedAt < ?"
      ).bind(group.groupId, cutoff),
    ]);
    rowsPurged += group.count;
  }

  return { groupsPurged: groups.length, rowsPurged };
}

// -------------------------------------------------------------- devices

async function handleDevicesList(request: Request, env: Env): Promise<Response> {
  const device = await authenticate(request, env);
  if (!device) return json({ error: "unauthorized" }, 401);

  const rows = await env.DB.prepare(
    "SELECT id, name, platform, createdAt, lastSeenAt FROM devices WHERE groupId = ? AND revokedAt IS NULL"
  )
    .bind(device.groupId)
    .all<Pick<DeviceRow, "id" | "name" | "platform" | "createdAt" | "lastSeenAt">>();

  return json({
    devices: (rows.results ?? []).map((r) => ({
      deviceId: r.id,
      name: r.name,
      platform: r.platform,
      createdAt: r.createdAt,
      lastSeenAt: r.lastSeenAt,
      isSelf: r.id === device.id,
    })),
  });
}

/** Any paired device can revoke any other in its own group — the lost-device recovery path. */
async function handleDeviceRevoke(request: Request, env: Env, targetId: string): Promise<Response> {
  const device = await authenticate(request, env);
  if (!device) return json({ error: "unauthorized" }, 401);

  const target = await env.DB.prepare("SELECT groupId FROM devices WHERE id = ?")
    .bind(targetId)
    .first<{ groupId: string }>();
  if (!target) return json({ error: "no such device" }, 404);
  if (target.groupId !== device.groupId) return json({ error: "not in your group" }, 403);

  await env.DB.prepare("UPDATE devices SET revokedAt = ? WHERE id = ?")
    .bind(Date.now(), targetId)
    .run();

  return json({ revoked: true });
}

// ------------------------------------------------------------------ router

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const { pathname } = url;
  const { method } = request;

  if (method === "POST" && pathname === "/pair/start") return handlePairStart(request, env);
  if (method === "POST" && pathname === "/pair/claim") return handlePairClaim(request, env);
  if (method === "POST" && pathname === "/sync") return handleSync(request, env);
  if (method === "GET" && pathname === "/devices") return handleDevicesList(request, env);

  const deviceMatch = pathname.match(/^\/devices\/([^/]+)$/);
  if (method === "DELETE" && deviceMatch) {
    return handleDeviceRevoke(request, env, decodeURIComponent(deviceMatch[1]));
  }

  return json({ error: "not found" }, 404);
}

export default {
  /**
   * No CORS headers anywhere, deliberately: both clients are native (OkHttp on Android,
   * URLSession on macOS) and no browser ever calls this. Omitting `Access-Control-Allow-Origin`
   * means a page on any origin can still *send* a request but cannot read the response, which
   * is the correct default for an API whose entire auth model is a bearer token. Adding
   * permissive CORS "just in case" would be a strict downgrade — see D149.
   *
   * The catch is what keeps internals off the wire. An uncaught throw leaves the response to
   * the platform: `wrangler dev` returns the exception with a full stack trace and the
   * developer's absolute filesystem paths, and production returns Cloudflare's generic 1101
   * page. Neither is ours to rely on, and the `no seq counter for group ${groupId}` throw
   * below would have been the thing leaking. Details go to the log; the client gets a bare 500.
   */
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      console.error("Unhandled error", e);
      return json({ error: "internal error" }, 500);
    }
  },

  /**
   * Cloudflare Cron Trigger entry point (`wrangler.toml`'s `[triggers]`) — the 180-day
   * tombstone purge flagged since D121/D126 and never wired to anything until now. `waitUntil`
   * so the platform doesn't tear down the Worker mid-purge the moment this returns.
   */
  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      purgeOldTombstones(env, Date.now()).then((stats) => {
        console.log(`Tombstone purge: ${stats.rowsPurged} row(s) across ${stats.groupsPurged} group(s)`);
      })
    );
  },
} satisfies ExportedHandler<Env>;
