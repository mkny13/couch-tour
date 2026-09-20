package dev.mike.couchtour

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.Index
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per queue the user has listened to. [queueKey] is namespaced ("show:1997-11-17")
 * so playlists ("playlist:some-slug") can be stored in the same table later without a migration.
 */
@Entity(
    tableName = "progress",
    indices = [
        Index("deletedAt"),
        Index("updatedAt"),
        Index("finished"),
        Index("artist")
    ]
)
data class Progress(
    @PrimaryKey val queueKey: String,
    val title: String,
    val subtitle: String,
    val artUrl: String?,
    val trackIndex: Int,
    val positionMs: Long,
    val trackTitle: String,
    val updatedAt: Long,
    /** Set when the queue played through to its end. */
    val finished: Boolean = false,
    /**
     * Set when the user removes it from "Continue listening" by hand. It stays in history;
     * playing it again clears the flag and brings it back.
     */
    val dismissed: Boolean = false,
    /**
     * The band, denormalised like the rest of the display fields so history renders without
     * a network call or a look at which backend the key belongs to.
     *
     * Its own column rather than part of [subtitle], because grouping history by artist off
     * a display string would mean splitting on a separator that venue names are free to
     * contain. Empty only on a row that somehow predates the v6 backfill.
     */
    val artist: String = "",
    /**
     * Epoch millis a queue was cleared, or null while it's live. A tombstone rather than a
     * real `DELETE`: a sync client needs to know a row was removed, not just that it's
     * absent, to avoid a later push from another device silently bringing it back.
     */
    val deletedAt: Long? = null,
)

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(progress: Progress)

    /** The "Continue listening" row: still going, and not hidden by hand. */
    @Query("SELECT * FROM progress WHERE finished = 0 AND dismissed = 0 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 25")
    fun inProgress(): Flow<List<Progress>>

    /** Everything ever played, including finished and dismissed queues. */
    @Query("SELECT * FROM progress WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun history(): Flow<List<Progress>>

    @Query("SELECT COUNT(*) FROM progress WHERE deletedAt IS NULL")
    fun historyCount(): Flow<Int>

    /** The bands in history, for grouping it. Blank artists are skipped — see [Progress.artist]. */
    @Query("SELECT DISTINCT artist FROM progress WHERE artist != '' AND deletedAt IS NULL ORDER BY artist")
    fun artists(): Flow<List<String>>

    /** The queue keys played through to the end — one query rather than a lookup per
     *  candidate show, for "which of these have I already heard?" (#22). */
    @Query("SELECT queueKey FROM progress WHERE finished = 1 AND deletedAt IS NULL")
    fun finishedKeys(): Flow<List<String>>

    @Query("SELECT * FROM progress WHERE queueKey = :key AND deletedAt IS NULL")
    suspend fun get(key: String): Progress?

    @Query("UPDATE progress SET dismissed = 1 WHERE queueKey = :key")
    suspend fun dismiss(key: String)

    @Query("UPDATE progress SET finished = 1 WHERE queueKey = :key")
    suspend fun markFinished(key: String)

    /**
     * Tombstones the row rather than deleting it, so a sync client can tell "removed" apart
     * from "never existed" — see [Progress.deletedAt]. Every read query filters it back out,
     * so this is invisible to the rest of the app; [put] un-deletes by writing a fresh row
     * with `deletedAt = null`, the same way it already clears `dismissed`.
     */
    @Query("UPDATE progress SET deletedAt = :now, updatedAt = :now WHERE queueKey = :key")
    suspend fun clear(key: String, now: Long)

    /**
     * Rows to push on the next sync: everything touched since the last successful push,
     * tombstones included — a delete has to reach the other device too. Unlike every other
     * query here, this deliberately does NOT filter `deletedAt IS NULL`; that filter is what
     * makes the rest of the app forget a cleared row, but sync needs to see it.
     */
    @Query("SELECT * FROM progress WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Progress>
}

@Entity(tableName = "artist_tour_preferences")
data class ArtistTourPreferenceEntity(
    @PrimaryKey @ColumnInfo(name = "artist_key") val artistKey: String,
    @ColumnInfo(name = "tour_name") val tourName: String? = null,
    @ColumnInfo(name = "year") val year: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ArtistTourPreferenceDao {
    @Query("SELECT * FROM artist_tour_preferences WHERE artist_key = :artistKey")
    suspend fun getPreference(artistKey: String): ArtistTourPreferenceEntity?

    @Query("SELECT * FROM artist_tour_preferences")
    fun getAllPreferences(): Flow<List<ArtistTourPreferenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(preference: ArtistTourPreferenceEntity)

    @Query("DELETE FROM artist_tour_preferences WHERE artist_key = :artistKey")
    suspend fun deletePreference(artistKey: String)
}

/** Shared string values for the taper_preferences.preference column (#173). */
object TaperPref {
    const val PREFERRED = "PREFERRED"
    const val AVOIDED = "AVOIDED"
}

@Entity(tableName = "taper_preferences")
data class TaperPreferenceEntity(
    @PrimaryKey @ColumnInfo(name = "taper_name") val taperName: String,
    // "PREFERRED" or "AVOIDED"; neutral tapers simply have no row.
    @ColumnInfo(name = "preference") val preference: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface TaperPreferenceDao {
    @Query("SELECT * FROM taper_preferences WHERE taper_name = :taperName")
    fun getPreferenceFlow(taperName: String): Flow<TaperPreferenceEntity?>

    @Query("SELECT * FROM taper_preferences")
    fun getAllPreferences(): Flow<List<TaperPreferenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(preference: TaperPreferenceEntity)

    @Query("DELETE FROM taper_preferences WHERE taper_name = :taperName")
    suspend fun deletePreference(taperName: String)
}

@Entity(
    tableName = "external_releases",
    primaryKeys = ["artist_key", "date"]
)
data class ExternalReleaseEntity(
    @ColumnInfo(name = "artist_key") val artistKey: String,
    val date: String,
    val platform: String,
    val url: String,
    @ColumnInfo(name = "is_heuristic", defaultValue = "0") val isHeuristic: Boolean = false,
)

@Dao
interface ExternalReleaseDao {
    @Query("SELECT * FROM external_releases WHERE artist_key = :artistKey AND date = :date")
    suspend fun get(artistKey: String, date: String): ExternalReleaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(release: ExternalReleaseEntity)
}

@Database(
    entities = [Progress::class, LocalPlaylistEntity::class, LocalPlaylistTrackEntity::class, ArtistTourPreferenceEntity::class, ExternalReleaseEntity::class, SourceLoudnessEntity::class, TaperPreferenceEntity::class],
    version = 14,
    exportSchema = true,
)
abstract class PhishInDb : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun artistTourPreferenceDao(): ArtistTourPreferenceDao
    abstract fun externalReleaseDao(): ExternalReleaseDao
    abstract fun sourceLoudnessDao(): SourceLoudnessDao
    abstract fun taperPreferenceDao(): TaperPreferenceDao

    companion object {
        /**
         * Adds the `finished` flag. Written as a real migration rather than a destructive
         * one: the listening history in this table is the whole point of it existing.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the `dismissed` flag. Same reasoning as [MIGRATION_1_2]: keep the history. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the pending-scrobble queue. Existing progress rows are untouched. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pending_scrobbles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `artist` TEXT NOT NULL, `track` TEXT NOT NULL, `album` TEXT NOT NULL,
                        `durationSec` INTEGER NOT NULL, `timestampSec` INTEGER NOT NULL)"""
                )
            }
        }

        /**
         * Drops the pending-scrobble queue. Built-in scrobbling was removed, so the table
         * has nothing left to feed it — external scrobblers never used it in the first
         * place, they read the MediaSession directly.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS pending_scrobbles")
            }
        }

        /**
         * Adds the band to each row, so history can be grouped by artist once there is more
         * than one. Existing rows are backfilled to Phish rather than left blank.
         *
         * That backfill is not the guess D21 declined to make. Until a second backend
         * existed, phish.in was the only thing this app could play, so every row already in
         * the table is Phish — playlist rows included, since their tracks are Phish too.
         * D21's case was different in kind: inferring `finished` needed a track duration the
         * table has never stored.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE progress SET artist = 'Phish'")
            }
        }

        /**
         * Adds the [Progress.deletedAt] tombstone. Existing rows get NULL, meaning live —
         * nothing in the table has actually been cleared by this migration. `clear()` itself
         * switches from a `DELETE` to setting this column, so a future sync client can tell
         * "removed" apart from "never existed" instead of a deletion silently reappearing
         * from a device that hadn't seen it yet.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN deletedAt INTEGER")
            }
        }

        /** Adds local playlists (#12, D161) — new tables only, `progress` is untouched. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `local_playlists` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `trackCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `local_playlist_tracks` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                        `backend` TEXT NOT NULL, `trackId` TEXT NOT NULL, `showDate` TEXT NOT NULL,
                        `artistSlug` TEXT, `recordingId` TEXT, `title` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL DEFAULT 0, `venueName` TEXT, `artUrl` TEXT,
                        FOREIGN KEY(`playlistId`) REFERENCES `local_playlists`(`id`) ON DELETE CASCADE)"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_playlist_tracks_playlistId` ON `local_playlist_tracks` (`playlistId`)"
                )
            }
        }

        /** Adds artist tour preferences (#68) for defunct/non-touring artists. */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (`artist_key` TEXT NOT NULL, `tour_name` TEXT, `year` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`artist_key`))"
                )
            }
        }

        /** Adds external releases for shows. */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `external_releases` (`artist_key` TEXT NOT NULL, `date` TEXT NOT NULL, `platform` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`artist_key`, `date`))"
                )
            }
        }

        /** Adds indices to speed up common read queries on progress and local_playlist_tracks. */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_deletedAt` ON `progress` (`deletedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_updatedAt` ON `progress` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_finished` ON `progress` (`finished`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_artist` ON `progress` (`artist`)")
                db.execSQL("DROP INDEX IF EXISTS `index_local_playlist_tracks_playlistId`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_playlist_tracks_playlistId_position` ON `local_playlist_tracks` (`playlistId`, `position`)")
            }
        }

        /** Adds the volume leveling source_loudness cache (#266). Local derived data only;
         *  never synced, so the sync payload is untouched. Written as a real migration so
         *  the progress history this database exists to hold survives untouched. */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_loudness` (`leveling_key` TEXT NOT NULL, `lufs` REAL NOT NULL, `peak_db` REAL NOT NULL, `sampled_tracks` INTEGER NOT NULL, `algorithm_version` INTEGER NOT NULL, `measured_at` INTEGER NOT NULL, PRIMARY KEY(`leveling_key`))"
                )
            }
        }

        /** Adds taper preferences for sorting/highlighting sources (#173). */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `taper_preferences` (`taper_name` TEXT NOT NULL, `preference` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`taper_name`))"
                )
            }
        }

        /** Adds `is_heuristic` flag to external_releases so the UI can distinguish
         *  automated date+venue matches from hand-curated ones (#181, D258). */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `external_releases` ADD COLUMN `is_heuristic` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile private var instance: PhishInDb? = null

        fun get(context: Context): PhishInDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhishInDb::class.java,
                // Predates the rename to Couch Tour and stays that way: the filename is
                // invisible to users, and changing it orphans every existing install's
                // listening history.
                "phishin.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            )
                .build().also { instance = it }
        }
    }
}
