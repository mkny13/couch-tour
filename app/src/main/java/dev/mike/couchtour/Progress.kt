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
)

/**
 * A play waiting to reach Last.fm. Scrobbles are queued rather than fired and forgotten so
 * that playing offline — the normal case on a train — doesn't silently lose them.
 */
@Entity(tableName = "pending_scrobbles")
data class PendingScrobble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artist: String,
    val track: String,
    val album: String,
    val durationSec: Int,
    val timestampSec: Long,
) {
    fun toScrobble() = Scrobble(artist, track, album, durationSec, timestampSec)
}

@Dao
interface ScrobbleDao {
    @Insert
    suspend fun add(scrobble: PendingScrobble)

    @Query("SELECT * FROM pending_scrobbles ORDER BY timestampSec ASC LIMIT 50")
    suspend fun oldest(): List<PendingScrobble>

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun count(): Int

    @Query("DELETE FROM pending_scrobbles")
    suspend fun clear()
}

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
    entities = [Progress::class, PendingScrobble::class],
    version = 4,
    exportSchema = true,
)
abstract class PhishInDb : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun scrobbleDao(): ScrobbleDao

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

        @Volatile private var instance: PhishInDb? = null

        fun get(context: Context): PhishInDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhishInDb::class.java,
                // Predates the rename to Couch Tour and stays that way: the filename is
                // invisible to users, and changing it orphans every existing install's
                // listening history.
                "phishin.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
