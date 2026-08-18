export interface Env {
  DB: D1Database;
  /** See the `[[ratelimits]]` block in wrangler.toml, and D150. */
  PAIR_START_LIMITER: RateLimit;
}

export interface DeviceRow {
  id: string;
  groupId: string;
  name: string;
  platform: string;
  tokenHash: string;
  tokenIssuedAt: number;
  previousTokenHash: string | null;
  previousTokenExpiresAt: number | null;
  createdAt: number;
  lastSeenAt: number | null;
  revokedAt: number | null;
}

/**
 * The 11 client columns, verbatim across Android's Progress and macOS's PlaybackProgress —
 * the wire shape both directions of `POST /sync` use.
 */
export interface ProgressFields {
  queueKey: string;
  title: string;
  subtitle: string;
  artUrl: string | null;
  trackIndex: number;
  positionMs: number;
  trackTitle: string;
  updatedAt: number;
  finished: boolean;
  dismissed: boolean;
  artist: string;
  deletedAt: number | null;
}

/**
 * The same row as stored in D1: SQLite has no boolean type, so `finished`/`dismissed` are
 * 0/1 here — see `toWireRow` for the conversion back to `ProgressFields`'s booleans.
 */
export interface ProgressRow extends Omit<ProgressFields, "finished" | "dismissed"> {
  groupId: string;
  finished: number;
  dismissed: number;
  seq: number;
  lastWriterDeviceId: string | null;
}
