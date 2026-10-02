# DB query efficiency audit (#507, part of #502)

Re-audit of Room (Android), GRDB (macOS) and D1 (sync). Covers the queries added since the
previous pass (#210 → #239/#240/#241, closed 2026-09-16), which left no report. The next pass
should diff against this file.

**Method.** Each store has a seeded `EXPLAIN QUERY PLAN` test that fails if a query on a
growing table full-scans or uses a temp B-tree sort:

- Android: `app/src/test/java/dev/mike/couchtour/QueryPlanTest.kt` (Robolectric, in-memory Room)
- macOS: `macos/Packages/CouchTourKit/Tests/CouchTourKitTests/QueryPlanTests.swift` (in-memory GRDB)
- sync: `sync/test/queryplan.test.ts` (Miniflare D1)

Verdicts: **index** = index-backed, **acceptable** = scan or sort that is deliberate (reason given).

**Result: no schema change.** Every query on a growing table is index-backed or an acceptable
scan. No migration, schema JSON or `Dnnn` entry was needed. Growing tables: `progress`,
`local_playlist_tracks`, `source_loudness`, D1 `progress`, D1 `devices`.

## Android (Room, schema v14)

Indexes on `progress`: `deletedAt`, `updatedAt`, `finished`, `artist`. `local_playlist_tracks`:
`(playlistId, position)`. Android's plans use SQLite's older `SCAN TABLE` wording.

| Query | Location | Plan | Verdict |
|---|---|---|---|
| `progress` in-progress, `ORDER BY updatedAt DESC LIMIT 25` | `Progress.kt` `ProgressDao.inProgress` | walks `updatedAt` index, no sort | index |
| `progress` history, `deletedAt IS NULL ORDER BY updatedAt DESC` | `ProgressDao.history` | `updatedAt` index, no sort | index |
| `COUNT(*)` of live progress | `ProgressDao.historyCount` | full scan | acceptable: O(n) however planned, an index is walked end to end too |
| `DISTINCT artist ... ORDER BY artist` | `ProgressDao.artists` | `artist` index, no sort | index |
| finished keys `finished = 1 AND deletedAt IS NULL` | `ProgressDao.finishedKeys` | index on `finished`/`deletedAt` | index |
| `progress` by `queueKey` | `ProgressDao.get`, `dismiss`, `markFinished`, `clear` | primary key | index |
| `progress WHERE updatedAt > :since` | `ProgressDao.changedSince` | `updatedAt` index | index |
| `local_playlists WHERE id` and all `UPDATE`/`DELETE` by id | `LocalPlaylist.kt` | primary key | index |
| `local_playlists ORDER BY updatedAt DESC` | `LocalPlaylistDao.playlists` | scan + temp sort | acceptable: one row per user playlist (a handful) |
| tracks of a playlist `ORDER BY position` | `tracks`, `tracksOnce` | `(playlistId, position)` index, no sort | index |
| `MAX(position)` per playlist | `maxPosition` | covering `(playlistId, position)` index | index |
| all tracks `ORDER BY rowId DESC` | `LocalPlaylistDao.allTracks` | rowid walk, no sort | acceptable: unfiltered read of every playlist track by design (Library screen) |
| track by `rowId`, delete/reposition by `rowId` | `trackIdOf`, `deleteTrack`, `setPosition` | integer primary key | index |
| `source_loudness` by `leveling_key` (+ `algorithm_version`) | `SourceLoudness.kt` | primary key | index |
| `DELETE FROM source_loudness` | `SourceLoudnessDao.clear` | full delete by design | acceptable |
| `artist_tour_preferences`, `taper_preferences` | `Progress.kt` | primary key lookups; unfiltered lists of tiny tables | acceptable: one row per artist/taper the user customised |
| `external_releases` by `(artist_key, date)` | `ExternalReleaseDao.get` | composite primary key | index |

Loops: `reorder` runs one `UPDATE` per track by integer primary key inside one transaction.
That is a per-row write loop, not a read N+1, and a playlist is small. Left as is.

## macOS (GRDB)

Indexes (`ProgressStore.swift` migration `v10_progressIndexes`) are partial and ordered:
`updatedAt DESC WHERE deletedAt IS NULL`, `updatedAt DESC WHERE finished = 0 AND dismissed = 0
AND deletedAt IS NULL`, `(artist, updatedAt DESC) WHERE deletedAt IS NULL`, `updatedAt`.
`LocalPlaylist.swift` `v10_localPlaylistIndexes`: `(playlistId, position)`.

| Query | Location | Plan | Verdict |
|---|---|---|---|
| `inProgress()` | `ProgressStore.swift` | `SCAN ... USING INDEX idx_progress_in_progress_updated_at` (ordered walk, no sort) | index |
| `history()` | `ProgressStore.swift` | `idx_progress_live_updated_at`, no sort | index |
| `historyCount()` | `ProgressStore.swift` | `SCAN progress` | acceptable: O(n) however planned |
| `artists()` | `ProgressStore.swift` | `idx_progress_artist_updated_at`, no sort | index |
| `historyFor(artist:)` | `ProgressStore.swift` | `SEARCH ... idx_progress_artist_updated_at (artist=?)` | index |
| `get`, `dismiss`, `markFinished`, `clear`, `rawRow` by `queueKey` | `ProgressStore.swift` | primary key autoindex | index |
| `changedSince(_:)` | `ProgressStore.swift` | `SEARCH ... idx_progress_changed_since_updated_at (updatedAt>?)` | index |
| tour/taper preference reads, saves, deletes | `ProgressStore.swift` | primary key; unfiltered lists of tiny tables | acceptable |
| `source_loudness` by `leveling_key` | `ProgressStore.swift` | primary key autoindex | index |
| `local_playlists` list `ORDER BY updatedAt DESC` | `LocalPlaylist.swift` | `SCAN` + temp sort | acceptable: a handful of playlists |
| playlist by id, rename, touch | `LocalPlaylist.swift` | primary key autoindex | index |
| tracks of a playlist `ORDER BY position` | `LocalPlaylist.swift` | `idx_local_playlist_tracks_playlist_position`, no sort | index |
| `MAX(position)` | `LocalPlaylist.swift` | `COVERING INDEX idx_local_playlist_tracks_playlist_position` | index |
| delete/reposition track by `rowId` | `LocalPlaylist.swift` | integer primary key | index |

Loops: `reorderTracks` (one `UPDATE` per row by primary key, one transaction) and `addTracks`
(one `INSERT` per row, one transaction) are write loops over a small playlist. `Migration.swift`
`importFrom` does a primary-key `fetchOne` per imported row. It runs once, when the user imports
history from another database, in one transaction, and each lookup is a primary-key seek. Left
as is; batching would add code for a one-off path.

## Sync (D1)

Schema indexes (`sync/schema.sql`): `devices_previousTokenHash`, `devices_group_revoked
(groupId, revokedAt, id, name, platform, createdAt, lastSeenAt)`, `pairings_codeHash`,
`progress_seq (groupId, seq)`, `progress_deletedAt_seq (deletedAt, groupId, seq)`.
`db:migrate` runs `schema.sql` as is (`wrangler d1 execute --file`), so any new index there
must use `CREATE INDEX IF NOT EXISTS`; none was added.

| Query | Location | Plan | Verdict |
|---|---|---|---|
| auth: `devices WHERE tokenHash = ? OR (previousTokenHash = ? AND ...)` | `src/auth.ts` | `MULTI-INDEX OR` over `devices_2` (tokenHash) and `devices_previousTokenHash` | index |
| `UPDATE devices SET lastSeenAt` (every authenticated request) | `src/auth.ts` | primary key | index (see note) |
| device list `WHERE groupId = ? AND revokedAt IS NULL` | `src/index.ts` `handleDevicesList` | `COVERING INDEX devices_group_revoked` | index |
| device by id, revoke, token rotation `UPDATE` | `src/index.ts` | primary key | index |
| pairing by `codeHash` | `src/index.ts` `handlePairClaim` | `pairings_codeHash` | index |
| pairing claim `UPDATE ... WHERE id` | `src/index.ts` | primary key | index |
| `INSERT` into groups, seqs, devices, pairings | `src/index.ts` | no read | n/a |
| `seqs` read and bump by `groupId` | `src/index.ts` | primary key | index |
| pull `progress WHERE groupId = ? AND seq > ? ORDER BY seq` | `src/index.ts` sync handler | `progress_seq (groupId=? AND seq>?)`, no sort | index |
| existing-key lookup `groupId = ? AND queueKey IN (...)` | `applyIncomingChanges` | primary key `(groupId, queueKey)` | index |
| upsert `INSERT ... ON CONFLICT (groupId, queueKey)` | `applyIncomingChanges` | primary key | index |
| purge candidates `deletedAt IS NOT NULL AND deletedAt < ? GROUP BY groupId` | `purgeOldTombstones` | `progress_deletedAt_seq` range scan, temp B-tree for `GROUP BY` only | acceptable: the sort covers only tombstones older than 180 days, once a day |
| purge `DELETE ... WHERE groupId = ? AND deletedAt ...` | `purgeOldTombstones` | `COVERING INDEX progress_deletedAt_seq` | index |

Loops: the existing-key lookup is chunked `IN (...)` (not one query per row) and the upserts go
through one `db.batch`. The purge issues one batch per group that has expired tombstones, and
only groups with expired rows are visited. No N+1 found.

Notes, not changed (no scan involved, so out of this issue's fix scope):
- `devices.previousTokenHash` is `UNIQUE` (autoindex) and also has the explicit
  `devices_previousTokenHash` index. They duplicate each other. The cost is one extra index
  write on a tiny table, and dropping it needs a D1 migration for no read gain.
- Every authenticated request writes `devices.lastSeenAt`. This is a write, not a scan.

## Changes made

None to schema or queries. Added the three plan tests above, which turn "every growing-table
query is index-backed" into a failing check for the next pass. Test counts in `README.md`
updated.
