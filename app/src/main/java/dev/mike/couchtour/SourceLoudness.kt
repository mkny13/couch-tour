package dev.mike.couchtour

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A cached loudness measurement for one *source* — a whole show mix or a Relisten tape —
 * keyed by the queue-key grammar (`show:<date>`, `relisten:<artist>/<date>/<sourceId>`).
 * Volume leveling (#266) derives one static gain per source from it; the meter itself is
 * a later slice, this is only the cache it will write into.
 *
 * Strictly local, derived data: never synced (a row can always be re-measured), and every
 * row carries [algorithmVersion] so a change to the meter makes old rows stale instead of
 * wrong. A row whose version doesn't match the current meter counts as a cache miss —
 * see [SourceLoudnessDao.getCurrent].
 */
@Entity(tableName = "source_loudness")
data class SourceLoudnessEntity(
    @PrimaryKey
    @ColumnInfo(name = "leveling_key")
    val key: String,
    /** ITU-R BS.1770-4 integrated loudness of the source, in LUFS. */
    @ColumnInfo(name = "lufs") val lufs: Double,
    /** Sample peak of the source, in dBFS — caps any boosting gain so it never clips. */
    @ColumnInfo(name = "peak_db") val peakDb: Double,
    /** How many tracks of the source were sampled into this one measurement. */
    @ColumnInfo(name = "sampled_tracks") val sampledTracks: Int,
    /**
     * Version of the meter that produced this row. Bump it whenever the measurement
     * changes meaningfully; rows written by an older meter are treated as absent.
     */
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: Int,
    /** Epoch millis the measurement was taken, for cache eviction decisions later. */
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
)

@Dao
interface SourceLoudnessDao {
    @Query("SELECT * FROM source_loudness WHERE leveling_key = :key")
    suspend fun get(key: String): SourceLoudnessEntity?

    /**
     * The row for [key], but only if [algorithmVersion] produced it. A stale version is a
     * miss, not a wrong answer: the caller re-measures rather than leveling off old math.
     */
    @Query("SELECT * FROM source_loudness WHERE leveling_key = :key AND algorithm_version = :algorithmVersion")
    suspend fun getCurrent(key: String, algorithmVersion: Int): SourceLoudnessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sourceLoudness: SourceLoudnessEntity)

    @Query("DELETE FROM source_loudness")
    suspend fun clearAll()
}
