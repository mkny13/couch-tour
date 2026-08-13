package dev.mike.couchtour

import android.content.Context
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
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per queue the user has listened to. [queueKey] is namespaced ("show:1997-11-17")
 * so playlists ("playlist:some-slug") can be stored in the same table later without a migration.
 */
@Entity(tableName = "progress")
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
)

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(progress: Progress)

    /** The "Continue listening" row: still going, and not hidden by hand. */
    @Query("SELECT * FROM progress WHERE finished = 0 AND dismissed = 0 ORDER BY updatedAt DESC LIMIT 25")
    fun inProgress(): Flow<List<Progress>>

    /** Everything ever played, including finished and dismissed queues. */
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC")
    fun history(): Flow<List<Progress>>

    @Query("SELECT COUNT(*) FROM progress")
    fun historyCount(): Flow<Int>

    /** The bands in history, for grouping it. Blank artists are skipped — see [Progress.artist]. */
    @Query("SELECT DISTINCT artist FROM progress WHERE artist != '' ORDER BY artist")
    fun artists(): Flow<List<String>>

    @Query("SELECT * FROM progress WHERE artist = :artist ORDER BY updatedAt DESC")
    fun historyFor(artist: String): Flow<List<Progress>>

    @Query("SELECT * FROM progress WHERE queueKey = :key")
    suspend fun get(key: String): Progress?

    @Query("UPDATE progress SET dismissed = 1 WHERE queueKey = :key")
    suspend fun dismiss(key: String)

    @Query("UPDATE progress SET finished = 1 WHERE queueKey = :key")
    suspend fun markFinished(key: String)

    @Query("DELETE FROM progress WHERE queueKey = :key")
    suspend fun clear(key: String)
}

@Database(
    entities = [Progress::class],
    version = 6,
    exportSchema = true,
)
abstract class PhishInDb : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

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
            )
                .build().also { instance = it }
        }
    }
}
