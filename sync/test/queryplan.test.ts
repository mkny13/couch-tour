import { env } from "cloudflare:test";
import { beforeAll, describe, expect, test } from "vitest";
import schemaSql from "../schema.sql?raw";

// EXPLAIN QUERY PLAN audit of every statement in src/ that touches a table that grows
// (#507, report in docs/DB_QUERY_EFFICIENCY.md). Seeded so the planner sees real rows, then
// each plan is asserted to use an index and not a full scan or a temp B-tree sort.

const TABLES = ["progress", "pairings", "devices", "seqs", "groups"];

beforeAll(async () => {
  for (const table of TABLES) await env.DB.exec(`DROP TABLE IF EXISTS ${table}`);
  // D1 exec() treats each newline as a statement, so flatten and split on `;`.
  const statements = schemaSql
    .split("\n")
    .filter((line) => !line.trim().startsWith("--"))
    .join(" ")
    .split(";")
    .map((s) => s.trim())
    .filter(Boolean);
  for (const statement of statements) await env.DB.exec(statement);

  const seed: D1PreparedStatement[] = [];
  for (let g = 0; g < 5; g++) {
    seed.push(env.DB.prepare("INSERT INTO groups (id, createdAt) VALUES (?, 0)").bind(`g${g}`));
    seed.push(env.DB.prepare("INSERT INTO seqs (groupId, next) VALUES (?, 1)").bind(`g${g}`));
    for (let d = 0; d < 3; d++) {
      seed.push(
        env.DB.prepare(
          `INSERT INTO devices (id, groupId, name, platform, tokenHash, tokenIssuedAt, previousTokenHash, previousTokenExpiresAt, createdAt)
           VALUES (?, ?, 'n', 'p', ?, 0, ?, 0, 0)`
        ).bind(`d${g}-${d}`, `g${g}`, `th${g}-${d}`, `pth${g}-${d}`)
      );
    }
    seed.push(
      env.DB.prepare("INSERT INTO pairings (id, groupId, codeHash, expiresAt) VALUES (?, ?, ?, 0)").bind(`p${g}`, `g${g}`, `ch${g}`)
    );
    for (let i = 0; i < 100; i++) {
      seed.push(
        env.DB.prepare(
          `INSERT INTO progress (groupId, queueKey, title, subtitle, trackIndex, positionMs, trackTitle,
             updatedAt, finished, dismissed, artist, deletedAt, seq)
           VALUES (?, ?, 't', 's', 0, 0, 'tt', ?, 0, 0, 'a', ?, ?)`
        ).bind(`g${g}`, `k${i}`, i, i % 10 === 0 ? 5 : null, i + 1)
      );
    }
  }
  await env.DB.batch(seed);
  await env.DB.exec("ANALYZE");
});

async function plan(sql: string, ...args: unknown[]): Promise<string[]> {
  const rows = await env.DB.prepare(`EXPLAIN QUERY PLAN ${sql}`)
    .bind(...args)
    .all<{ detail: string }>();
  return (rows.results ?? []).map((r) => r.detail);
}

async function expectIndexBacked(sql: string, ...args: unknown[]) {
  const steps = await plan(sql, ...args);
  expect(steps.length, sql).toBeGreaterThan(0);
  for (const step of steps) {
    expect(step.startsWith("SCAN") && !step.includes("INDEX"), `full scan: ${step} for ${sql}`).toBe(false);
    expect(step.includes("TEMP B-TREE"), `temp sort: ${step} for ${sql}`).toBe(false);
  }
}

describe("query plans", () => {
  test("auth lookup by current or previous token hash", async () => {
    await expectIndexBacked(
      `SELECT * FROM devices WHERE (tokenHash = ?1) OR (previousTokenHash = ?1 AND previousTokenExpiresAt > ?2) LIMIT 1`,
      "th0-0",
      1
    );
  });

  test("device writes and lookups by id", async () => {
    await expectIndexBacked("UPDATE devices SET lastSeenAt = ?1 WHERE id = ?2", 1, "d0-0");
    await expectIndexBacked("SELECT groupId FROM devices WHERE id = ?", "d0-0");
    await expectIndexBacked("UPDATE devices SET revokedAt = ? WHERE id = ?", 1, "d0-0");
    await expectIndexBacked(
      "UPDATE devices SET previousTokenHash = ?, previousTokenExpiresAt = ?, tokenHash = ?, tokenIssuedAt = ? WHERE id = ?",
      "a", 1, "b", 1, "d0-0"
    );
  });

  test("device list for a group", async () => {
    await expectIndexBacked(
      "SELECT id, name, platform, createdAt, lastSeenAt FROM devices WHERE groupId = ? AND revokedAt IS NULL",
      "g0"
    );
  });

  test("pairing lookup and claim", async () => {
    await expectIndexBacked("SELECT * FROM pairings WHERE codeHash = ?", "ch0");
    await expectIndexBacked("UPDATE pairings SET claimedAt = ? WHERE id = ? AND claimedAt IS NULL RETURNING id", 1, "p0");
  });

  test("seq counter reads and bumps", async () => {
    await expectIndexBacked("SELECT retentionFloorSeq FROM seqs WHERE groupId = ?", "g0");
    await expectIndexBacked("UPDATE seqs SET next = next + ? WHERE groupId = ? RETURNING next", 1, "g0");
  });

  test("progress pull since a cursor", async () => {
    await expectIndexBacked("SELECT * FROM progress WHERE groupId = ? AND seq > ? ORDER BY seq ASC", "g0", 10);
  });

  test("progress existing-key lookup on push", async () => {
    await expectIndexBacked(
      "SELECT queueKey, updatedAt FROM progress WHERE groupId = ? AND queueKey IN (?,?,?)",
      "g0", "k1", "k2", "k3"
    );
  });

  test("tombstone purge: candidate scan and delete", async () => {
    // The deletedAt range scan is index-backed; GROUP BY groupId then sorts only the rows that
    // matched (tombstones older than 180 days), once a day. Acceptable, so only the GROUP BY
    // temp B-tree is allowed here.
    const steps = await plan(
      `SELECT groupId, MAX(seq) as maxSeq FROM progress WHERE deletedAt IS NOT NULL AND deletedAt < ? GROUP BY groupId`,
      100
    );
    expect(steps.some((s) => s.includes("USING") && s.includes("INDEX progress_deletedAt_seq"))).toBe(true);
    expect(steps.filter((s) => s.includes("TEMP B-TREE"))).toEqual(["USE TEMP B-TREE FOR GROUP BY"]);
    await expectIndexBacked("DELETE FROM progress WHERE groupId = ? AND deletedAt IS NOT NULL AND deletedAt < ?", "g0", 100);
  });
});
