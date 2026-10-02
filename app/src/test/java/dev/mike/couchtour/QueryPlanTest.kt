package dev.mike.couchtour

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EXPLAIN QUERY PLAN audit of every query on a table that grows (#507, report in
 * docs/DB_QUERY_EFFICIENCY.md). The DB is seeded so the planner sees real rows, then each
 * query's plan is asserted to use an index rather than a full scan or a temp B-tree sort.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueryPlanTest {

    private lateinit var db: PhishInDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PhishInDb::class.java,
        ).allowMainThreadQueries().build()
        val sql = db.openHelper.writableDatabase
        for (i in 0 until 500) {
            sql.execSQL(
                "INSERT INTO progress (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, " +
                    "updatedAt, finished, dismissed, artist, deletedAt) VALUES (?, 't', 's', NULL, 0, 0, 'tt', ?, ?, ?, ?, ?)",
                arrayOf<Any?>("k$i", i.toLong(), if (i % 3 == 0) 1 else 0, if (i % 5 == 0) 1 else 0, "artist${i % 7}", if (i % 11 == 0) 5L else null),
            )
            sql.execSQL("INSERT INTO source_loudness VALUES (?, -14.0, -1.0, 3, 1, 0)", arrayOf<Any?>("lk$i"))
        }
        for (p in 0 until 20) {
            sql.execSQL("INSERT INTO local_playlists (id, name, trackCount, createdAt, updatedAt) VALUES (?, 'p', 0, 0, ?)", arrayOf<Any?>("pl$p", p.toLong()))
            for (pos in 0 until 25) {
                sql.execSQL(
                    "INSERT INTO local_playlist_tracks (playlistId, position, backend, trackId, showDate, title, durationMs) " +
                        "VALUES (?, ?, 'phishin', 'x', '1997-01-01', 't', 1)",
                    arrayOf<Any?>("pl$p", pos),
                )
            }
        }
        sql.execSQL("ANALYZE")
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun plan(sql: String, vararg args: String): List<String> {
        val steps = mutableListOf<String>()
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            while (c.moveToNext()) steps += c.getString(detail)
        }
        return steps
    }

    private fun assertIndexBacked(sql: String, vararg args: String) {
        val steps = plan(sql, *args)
        assertTrue("no plan for $sql", steps.isNotEmpty())
        for (step in steps) {
            assertFalse("full scan: $step for $sql", step.startsWith("SCAN") && !step.contains("INDEX"))
            assertFalse("temp sort: $step for $sql", step.contains("TEMP B-TREE"))
        }
    }

    @Test
    fun progressReadsAreIndexBacked() {
        assertIndexBacked("SELECT * FROM progress WHERE finished = 0 AND dismissed = 0 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 25")
        assertIndexBacked("SELECT * FROM progress WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
        assertIndexBacked("SELECT DISTINCT artist FROM progress WHERE artist != '' AND deletedAt IS NULL ORDER BY artist")
        assertIndexBacked("SELECT queueKey FROM progress WHERE finished = 1 AND deletedAt IS NULL")
        assertIndexBacked("SELECT * FROM progress WHERE queueKey = ? AND deletedAt IS NULL", "k1")
        assertIndexBacked("SELECT * FROM progress WHERE updatedAt > ?", "100")
    }

    @Test
    fun progressWritesAreIndexBacked() {
        assertIndexBacked("UPDATE progress SET dismissed = 1 WHERE queueKey = ?", "k1")
        assertIndexBacked("UPDATE progress SET finished = 1 WHERE queueKey = ?", "k1")
        assertIndexBacked("UPDATE progress SET deletedAt = 1, updatedAt = 1 WHERE queueKey = ?", "k1")
    }

    @Test
    fun localPlaylistQueriesAreIndexBacked() {
        assertIndexBacked("SELECT * FROM local_playlists WHERE id = ?", "pl1")
        assertIndexBacked("SELECT * FROM local_playlist_tracks WHERE playlistId = ? ORDER BY position", "pl1")
        assertIndexBacked("SELECT COALESCE(MAX(position), -1) FROM local_playlist_tracks WHERE playlistId = ?", "pl1")
        assertIndexBacked("SELECT trackId FROM local_playlist_tracks WHERE rowId = 1")
        assertIndexBacked("UPDATE local_playlist_tracks SET position = 1 WHERE rowId = 1 AND playlistId = ?", "pl1")
        assertIndexBacked("DELETE FROM local_playlist_tracks WHERE rowId = 1")
        assertIndexBacked("UPDATE local_playlists SET updatedAt = 1 WHERE id = ?", "pl1")
    }

    @Test
    fun sourceLoudnessLookupsAreIndexBacked() {
        assertIndexBacked("SELECT * FROM source_loudness WHERE leveling_key = ?", "lk1")
        assertIndexBacked("SELECT * FROM source_loudness WHERE leveling_key = ? AND algorithm_version = 1", "lk1")
    }

    /** O(n) however it is planned: an index would still be walked end to end. */
    @Test
    fun historyCountIsAKnownFullScan() {
        val steps = plan("SELECT COUNT(*) FROM progress WHERE deletedAt IS NULL")
        assertFalse(steps.any { it.contains("TEMP B-TREE") })
    }

    /** Reads every track by design (no WHERE), walking the rowid b-tree backwards: no sort. */
    @Test
    fun allTracksIsAKnownFullReadWithoutASort() {
        val steps = plan("SELECT * FROM local_playlist_tracks ORDER BY rowId DESC")
        assertFalse(steps.any { it.contains("TEMP B-TREE") })
    }

    /** One row per playlist, so the sort is over a handful of rows. */
    @Test
    fun playlistListSortIsAcceptable() {
        val steps = plan("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
        assertTrue(steps.any { it.startsWith("SCAN") })
    }
}
