package dev.mike.phishin

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
)

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(progress: Progress)

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 25")
    fun recent(): Flow<List<Progress>>

    @Query("SELECT * FROM progress WHERE queueKey = :key")
    suspend fun get(key: String): Progress?

    @Query("DELETE FROM progress WHERE queueKey = :key")
    suspend fun clear(key: String)
}

@Database(entities = [Progress::class], version = 1, exportSchema = true)
abstract class PhishInDb : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    companion object {
        @Volatile private var instance: PhishInDb? = null

        fun get(context: Context): PhishInDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhishInDb::class.java,
                "phishin.db"
            ).build().also { instance = it }
        }
    }
}
