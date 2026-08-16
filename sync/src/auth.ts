import { sha256Hex } from "./crypto";
import type { DeviceRow, Env } from "./types";

/**
 * Looks up the calling device by its bearer token, accepting either the current token or a
 * still-in-grace previous one (see the rotation logic in index.ts). Revoked devices are
 * rejected outright — that's the whole mechanism `DELETE /devices/{id}` relies on to take
 * effect on the very next request, with no separate expiry window to wait out.
 */
export async function authenticate(request: Request, env: Env): Promise<DeviceRow | null> {
  const authHeader = request.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) return null;
  const token = authHeader.slice("Bearer ".length).trim();
  if (!token) return null;

  const hash = await sha256Hex(token);
  const now = Date.now();

  const device = await env.DB.prepare(
    `SELECT * FROM devices
     WHERE (tokenHash = ?1)
        OR (previousTokenHash = ?1 AND previousTokenExpiresAt > ?2)
     LIMIT 1`
  )
    .bind(hash, now)
    .first<DeviceRow>();

  if (!device || device.revokedAt !== null) return null;

  await env.DB.prepare("UPDATE devices SET lastSeenAt = ?1 WHERE id = ?2").bind(now, device.id).run();

  return device;
}
