import { env, createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { beforeEach, describe, expect, test } from "vitest";
import worker, { applyIncomingChanges, purgeOldTombstones } from "../src/index";
import { sha256Hex } from "../src/crypto";
import type { DeviceRow } from "../src/types";
import schemaSql from "../schema.sql?raw";

// Tables in child-before-parent order, so DROP TABLE never trips a foreign key still
// pointing at a not-yet-dropped table.
const TABLES = ["progress", "pairings", "devices", "seqs", "groups"];

/**
 * `D1Database.exec()` treats each newline as a separate statement rather than parsing
 * semicolons, so every statement is flattened onto one line before being split on `;`.
 */
async function applySchema(): Promise<void> {
  const statements = schemaSql
    .split("\n")
    .filter((line) => !line.trim().startsWith("--"))
    .join(" ")
    .split(";")
    .map((s) => s.trim())
    .filter(Boolean);
  for (const statement of statements) {
    await env.DB.exec(statement);
  }
}

// The pool doesn't isolate D1 storage between tests in the same file, so each test starts
// from a known-empty schema by dropping and recreating it explicitly.
beforeEach(async () => {
  for (const table of TABLES) {
    await env.DB.exec(`DROP TABLE IF EXISTS ${table}`);
  }
  await applySchema();
});

async function call(request: Request): Promise<Response> {
  const ctx = createExecutionContext();
  const response = await worker.fetch(request, env, ctx);
  await waitOnExecutionContext(ctx);
  return response;
}

function req(path: string, init: RequestInit = {}): Request {
  return new Request(`https://sync.test${path}`, {
    headers: { "content-type": "application/json", ...(init.headers ?? {}) },
    ...init,
  });
}

async function pairStart(deviceName = "device-a", platform = "test") {
  const response = await call(
    req("/pair/start", {
      method: "POST",
      // A unique per-call IP so PAIR_START_LIMITER's per-IP bucket never accumulates across
      // tests: D1 storage (and this rate limiter) isn't isolated between tests in this file,
      // and every test here pairs at least one fresh group.
      headers: { "CF-Connecting-IP": crypto.randomUUID() },
      body: JSON.stringify({ deviceName, platform }),
    })
  );
  expect(response.status).toBe(200);
  return (await response.json()) as {
    code: string;
    expiresAt: number;
    deviceId: string;
    deviceToken: string;
  };
}

async function pairClaim(code: string, deviceName = "device-b", platform = "test") {
  const response = await call(
    req("/pair/claim", { method: "POST", body: JSON.stringify({ code, deviceName, platform }) })
  );
  expect(response.status).toBe(200);
  return (await response.json()) as { deviceId: string; deviceToken: string };
}

/** Pairs two devices in a fresh group and returns their bearer tokens. */
async function pairTwoDevices() {
  const a = await pairStart();
  const b = await pairClaim(a.code);
  return { tokenA: a.deviceToken, tokenB: b.deviceToken };
}

async function deviceRowForToken(token: string): Promise<DeviceRow> {
  const hash = await sha256Hex(token);
  const row = await env.DB.prepare("SELECT * FROM devices WHERE tokenHash = ?")
    .bind(hash)
    .first<DeviceRow>();
  if (!row) throw new Error("device not found for token");
  return row;
}

function change(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    queueKey: "show-1|track-1",
    title: "Track One",
    subtitle: "Show One",
    artUrl: null,
    trackIndex: 0,
    positionMs: 1000,
    trackTitle: "Track One",
    updatedAt: Date.now(),
    finished: false,
    dismissed: false,
    artist: "Phish",
    deletedAt: null,
    ...overrides,
  };
}

async function sync(token: string, since: number, changes: Record<string, unknown>[]) {
  return call(
    req("/sync", {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
      body: JSON.stringify({ since, changes }),
    })
  );
}

describe("happy paths", () => {
  test("health check", async () => {
    const response = await call(req("/health"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ok" });
  });

  test("pair -> claim -> sync round trip: a push from one device is pulled by the other", async () => {
    const { tokenA, tokenB } = await pairTwoDevices();

    const pushResponse = await sync(tokenA, 0, [change()]);
    expect(pushResponse.status).toBe(200);
    const pushBody = (await pushResponse.json()) as { seq: number; changes: unknown[] };
    expect(pushBody.changes).toHaveLength(1);

    const pullResponse = await sync(tokenB, 0, []);
    expect(pullResponse.status).toBe(200);
    const pullBody = (await pullResponse.json()) as { seq: number; changes: { queueKey: string }[] };
    expect(pullBody.changes).toHaveLength(1);
    expect(pullBody.changes[0].queueKey).toBe("show-1|track-1");
    expect(pullBody.seq).toBe(pushBody.seq);
  });

  test("returns 410 when since is below the retention floor, but since = 0 is exempt", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);
    await env.DB.prepare("UPDATE seqs SET retentionFloorSeq = ? WHERE groupId = ?")
      .bind(10, device.groupId)
      .run();

    const stale = await sync(tokenA, 5, []);
    expect(stale.status).toBe(410);

    const fresh = await sync(tokenA, 0, []);
    expect(fresh.status).toBe(200);
  });

  test("full resync (since = 0) with active rows below retentionFloorSeq advances cursor to at least retentionFloorSeq", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);

    // Active row created before the purge raised retentionFloorSeq
    await env.DB.prepare(
      `INSERT INTO progress
         (groupId, queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle,
          updatedAt, finished, dismissed, artist, deletedAt, seq, lastWriterDeviceId)
       VALUES (?, 'active-1', 't', 's', NULL, 0, 0, 't', ?, 0, 0, 'a', NULL, 1, ?)`
    )
      .bind(device.groupId, Date.now(), device.id)
      .run();

    // Retention floor is raised to 5 (e.g. by purgeOldTombstones)
    await env.DB.prepare("UPDATE seqs SET next = 6, retentionFloorSeq = 5 WHERE groupId = ?")
      .bind(device.groupId)
      .run();

    // Client performs a full resync with since = 0
    const resync = await sync(tokenA, 0, []);
    expect(resync.status).toBe(200);
    const body = (await resync.json()) as { seq: number; changes: { queueKey: string }[] };
    expect(body.changes).toHaveLength(1);
    expect(body.changes[0].queueKey).toBe("active-1");
    // Returned seq must be bounded by retentionFloorSeq (5), not the active row's seq (1)
    expect(body.seq).toBeGreaterThanOrEqual(5);

    // Follow-up sync with the returned seq must succeed (200), not trigger a 410 resync loop
    const followUp = await sync(tokenA, body.seq, []);
    expect(followUp.status).toBe(200);
    const followUpBody = (await followUp.json()) as { changes: unknown[] };
    expect(followUpBody.changes).toHaveLength(0);
  });

  test("full resync (since = 0) with empty table under non-zero retentionFloorSeq advances cursor to retentionFloorSeq", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);

    await env.DB.prepare("UPDATE seqs SET next = 6, retentionFloorSeq = 5 WHERE groupId = ?")
      .bind(device.groupId)
      .run();

    const resync = await sync(tokenA, 0, []);
    expect(resync.status).toBe(200);
    const body = (await resync.json()) as { seq: number; changes: unknown[] };
    expect(body.changes).toHaveLength(0);
    expect(body.seq).toBe(5);

    const followUp = await sync(tokenA, body.seq, []);
    expect(followUp.status).toBe(200);
    const followUpBody = (await followUp.json()) as { changes: unknown[] };
    expect(followUpBody.changes).toHaveLength(0);
  });
});

describe("F1: duplicate queueKey within one push", () => {
  test("keeps the entry with the newest updatedAt and allocates exactly one seq", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);
    // Newer listed first: a naive array-order merge (last statement in the D1 batch wins)
    // would let the older entry clobber it. Only updatedAt should decide the outcome.
    const newer = change({ queueKey: "dup-key", trackTitle: "Newer", updatedAt: 2000 });
    const older = change({ queueKey: "dup-key", trackTitle: "Older", updatedAt: 1000 });

    const response = await sync(tokenA, 0, [newer, older]);
    expect(response.status).toBe(200);
    const body = (await response.json()) as { changes: { trackTitle: string; updatedAt: number }[] };
    expect(body.changes).toHaveLength(1);
    expect(body.changes[0].trackTitle).toBe("Newer");
    expect(body.changes[0].updatedAt).toBe(2000);

    // A fresh group's seqs.next starts at 1; exactly one accepted change should bump it to 2.
    // If both duplicates had been accepted (the bug), it would be 3, and the surviving row
    // would carry the lower of the two allocated seqs, orphaning the other slot.
    const seqs = await env.DB.prepare("SELECT next FROM seqs WHERE groupId = ?")
      .bind(device.groupId)
      .first<{ next: number }>();
    expect(seqs!.next).toBe(2);
  });

  test("on an exact updatedAt tie, keeps the later occurrence in the array", async () => {
    const { tokenA } = await pairTwoDevices();
    const first = change({ queueKey: "dup-key", trackTitle: "First", updatedAt: 5000 });
    const second = change({ queueKey: "dup-key", trackTitle: "Second", updatedAt: 5000 });

    const response = await sync(tokenA, 0, [first, second]);
    const body = (await response.json()) as { changes: { trackTitle: string }[] };
    expect(body.changes).toHaveLength(1);
    expect(body.changes[0].trackTitle).toBe("Second");
  });
});

describe("F2: cursor arithmetic under a concurrent push", () => {
  test("the returned seq is never lower than a row it returns, even when another device's push lands mid-request", async () => {
    const { tokenA, tokenB } = await pairTwoDevices();

    // Delay device A's final "what's changed" SELECT until device B's push has fully
    // committed — reproducing "another device pushes between the read and the bump"
    // deterministically, rather than hoping two Promise.all'd requests happen to interleave
    // at the right point. `env` here is the exact object `worker.fetch` receives (this suite
    // calls the worker directly rather than through a wrapped `SELF`), so patching it is
    // visible to the in-flight request.
    const originalPrepare = env.DB.prepare.bind(env.DB);
    let intercepted = false;
    let releaseGate: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseGate = resolve;
    });
    env.DB.prepare = ((sql: string) => {
      const stmt = originalPrepare(sql);
      if (!intercepted && sql.startsWith("SELECT * FROM progress WHERE groupId")) {
        intercepted = true;
        // The real code calls `.bind(...)` before `.all()`, and `.bind()` returns a distinct
        // statement object — patching `stmt.all` directly would be shadowed by the bound
        // statement's own (unpatched) `.all`. Patch the statement `.bind()` returns instead.
        const originalBind = stmt.bind.bind(stmt);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (stmt as any).bind = (...bindArgs: unknown[]) => {
          const bound = originalBind(...bindArgs);
          const originalAll = bound.all.bind(bound);
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (bound as any).all = async (...args: unknown[]) => {
            await gate;
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            return (originalAll as any)(...args);
          };
          return bound;
        };
      }
      return stmt;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;

    try {
      const aPromise = sync(tokenA, 0, []);

      // Device B's push runs and fully commits — bumping the seq counter and inserting its
      // row — while device A's request is parked at the gate above.
      const bResponse = await sync(tokenB, 0, [change({ queueKey: "b-key" })]);
      expect(bResponse.status).toBe(200);

      releaseGate!();
      const aResponse = await aPromise;
      expect(aResponse.status).toBe(200);
      const aBody = (await aResponse.json()) as { seq: number; changes: { queueKey: string }[] };

      expect(aBody.changes).toHaveLength(1);
      expect(aBody.changes[0].queueKey).toBe("b-key");

      const trueRow = await env.DB.prepare("SELECT seq FROM progress WHERE queueKey = ?")
        .bind("b-key")
        .first<{ seq: number }>();
      expect(aBody.seq).toBeGreaterThanOrEqual(trueRow!.seq);

      // A follow-up sync with the returned cursor must not re-pull the same row.
      const followUp = await sync(tokenA, aBody.seq, []);
      const followUpBody = (await followUp.json()) as { changes: unknown[] };
      expect(followUpBody.changes).toHaveLength(0);
    } finally {
      env.DB.prepare = originalPrepare;
    }
  });
});

describe("F3: negative numeric fields are rejected", () => {
  test("negative trackIndex is a 400", async () => {
    const { tokenA } = await pairTwoDevices();
    const response = await sync(tokenA, 0, [change({ trackIndex: -1 })]);
    expect(response.status).toBe(400);
    const body = (await response.json()) as { error: string };
    expect(body.error).toContain("changes[0].trackIndex");
  });

  test("negative positionMs is a 400", async () => {
    const { tokenA } = await pairTwoDevices();
    const response = await sync(tokenA, 0, [change({ positionMs: -1 })]);
    expect(response.status).toBe(400);
    const body = (await response.json()) as { error: string };
    expect(body.error).toContain("changes[0].positionMs");
  });

  test("negative deletedAt is a 400", async () => {
    const { tokenA } = await pairTwoDevices();
    const response = await sync(tokenA, 0, [change({ deletedAt: -1 })]);
    expect(response.status).toBe(400);
    const body = (await response.json()) as { error: string };
    expect(body.error).toContain("changes[0].deletedAt");
  });
});

describe("F4: tombstone purge row count", () => {
  test("rowsPurged matches the number of rows the delete actually removed", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);
    const now = Date.now();
    const TOMBSTONE_RETENTION_MS = 180 * 24 * 60 * 60 * 1000;
    const oldEnough = now - TOMBSTONE_RETENTION_MS - 1000;

    const insertTombstone = (queueKey: string, seq: number) =>
      env.DB.prepare(
        `INSERT INTO progress
           (groupId, queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle,
            updatedAt, finished, dismissed, artist, deletedAt, seq, lastWriterDeviceId)
         VALUES (?, ?, 't', 's', NULL, 0, 0, 't', ?, 0, 1, 'a', ?, ?, ?)`
      ).bind(device.groupId, queueKey, oldEnough, oldEnough, seq, device.id);

    await env.DB.batch([
      insertTombstone("k1", 1),
      insertTombstone("k2", 2),
      env.DB.prepare("UPDATE seqs SET next = 3 WHERE groupId = ?").bind(device.groupId),
    ]);

    const stats = await purgeOldTombstones(env, now);
    expect(stats.groupsPurged).toBe(1);
    expect(stats.rowsPurged).toBe(2);

    const remaining = await env.DB.prepare("SELECT COUNT(*) as c FROM progress WHERE groupId = ?")
      .bind(device.groupId)
      .first<{ c: number }>();
    expect(remaining!.c).toBe(0);

    const floor = await env.DB.prepare("SELECT retentionFloorSeq FROM seqs WHERE groupId = ?")
      .bind(device.groupId)
      .first<{ retentionFloorSeq: number }>();
    expect(floor!.retentionFloorSeq).toBe(2);
  });

  test("leaves tombstones newer than the retention window untouched", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);
    const now = Date.now();

    await env.DB.prepare(
      `INSERT INTO progress
         (groupId, queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle,
          updatedAt, finished, dismissed, artist, deletedAt, seq, lastWriterDeviceId)
       VALUES (?, 'recent', 't', 's', NULL, 0, 0, 't', ?, 0, 1, 'a', ?, 1, ?)`
    )
      .bind(device.groupId, now, now, device.id)
      .run();

    const stats = await purgeOldTombstones(env, now);
    expect(stats.groupsPurged).toBe(0);
    expect(stats.rowsPurged).toBe(0);

    const remaining = await env.DB.prepare("SELECT COUNT(*) as c FROM progress WHERE groupId = ?")
      .bind(device.groupId)
      .first<{ c: number }>();
    expect(remaining!.c).toBe(1);
  });
});

// Exercises applyIncomingChanges directly to confirm it's usable as a standalone unit (the
// F2 test above already covers it indirectly via the full /sync round trip).
describe("applyIncomingChanges", () => {
  test("accepts a change for a brand-new key", async () => {
    const { tokenA } = await pairTwoDevices();
    const device = await deviceRowForToken(tokenA);
    const accepted = await applyIncomingChanges(
      env,
      device,
      [change({ queueKey: "direct-key" }) as never],
      Date.now()
    );
    expect(accepted).toBe(1);
  });
});
