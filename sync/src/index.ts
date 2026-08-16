import { authenticate } from "./auth";
import { randomId, randomPairingCode, randomToken, sha256Hex } from "./crypto";
import type { DeviceRow, Env, ProgressFields, ProgressRow } from "./types";

const PAIRING_TTL_MS = 10 * 60 * 1000;
const TOKEN_ROTATION_AGE_MS = 90 * 24 * 60 * 60 * 1000;
const TOKEN_GRACE_MS = 48 * 60 * 60 * 1000;
const FUTURE_CLOCK_CLAMP_MS = 24 * 60 * 60 * 1000;

function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

function badRequest(message: string): Response {
  return json({ error: message }, 400);
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

/**
 * Push-then-pull in one round trip. Incoming changes are merged with row-level
 * last-write-wins on `updatedAt` (ties go to whichever arrives at the server later, i.e. gets
 * the higher `seq` — see DECISIONS.md); everything the caller hasn't seen yet, including its
 * own just-applied pushes, comes back in `changes`.
 */
async function handleSync(request: Request, env: Env): Promise<Response> {
  const device = await authenticate(request, env);
  if (!device) return json({ error: "unauthorized" }, 401);

  const body = await readJson<SyncBody>(request);
  if (typeof body?.since !== "number" || !Array.isArray(body?.changes)) {
    return badRequest("since (number) and changes (array) are required");
  }
  const since = body.since;
  const incoming = body.changes as ProgressFields[];

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
  const keys = incoming.map((c) => c.queueKey);
  const placeholders = keys.map(() => "?").join(",");
  const existingRows = await env.DB.prepare(
    `SELECT queueKey, updatedAt FROM progress WHERE groupId = ? AND queueKey IN (${placeholders})`
  )
    .bind(device.groupId, ...keys)
    .all<{ queueKey: string; updatedAt: number }>();
  const existingByKey = new Map((existingRows.results ?? []).map((r) => [r.queueKey, r.updatedAt]));

  const accepted = incoming
    .map((change) => {
      // A badly clock-skewed device can't permanently pin a row into the future.
      const updatedAt = change.updatedAt > now + FUTURE_CLOCK_CLAMP_MS ? now : change.updatedAt;
      return { ...change, updatedAt };
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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
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
  },
} satisfies ExportedHandler<Env>;
