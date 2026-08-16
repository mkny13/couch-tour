package dev.mike.couchtour

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The v1 -> v2 migration adds the `finished` flag. A destructive migration would have been
 * shorter, but it drops the listening history the table exists to hold — so this builds a
 * real v1 database, opens it with the current schema, and checks the rows came through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    // Straight from app/schemas/dev.mike.couchtour.PhishInDb/1.json.
    private val v1CreateTable = """
        CREATE TABLE IF NOT EXISTS `progress` (
            `queueKey` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
            `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
            `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`queueKey`)
        )
    """.trimIndent()
    private val v1IdentityHash = "db5a97926aec82a06ff094ca2e38ff6d"

    // From app/schemas/dev.mike.couchtour.PhishInDb/2.json.
    private val v2CreateTable = """
        CREATE TABLE IF NOT EXISTS `progress` (
            `queueKey` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
            `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
            `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
            `finished` INTEGER NOT NULL,
            PRIMARY KEY(`queueKey`)
        )
    """.trimIndent()
    private val v2IdentityHash = "be05c275dc8057db9c62c7fe0280aa57"

    // From app/schemas/dev.mike.couchtour.PhishInDb/4.json.
    private val v4CreateTable = """
        CREATE TABLE IF NOT EXISTS `progress` (
            `queueKey` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
            `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
            `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
            `finished` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL,
            PRIMARY KEY(`queueKey`)
        )
    """.trimIndent()
    private val v4PendingScrobblesTable = """
        CREATE TABLE IF NOT EXISTS `pending_scrobbles` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `artist` TEXT NOT NULL, `track` TEXT NOT NULL, `album` TEXT NOT NULL,
            `durationSec` INTEGER NOT NULL, `timestampSec` INTEGER NOT NULL
        )
    """.trimIndent()
    private val v4IdentityHash = "e8c073632e44866ca4fd0fda3bcea1c7"

    // From app/schemas/dev.mike.couchtour.PhishInDb/5.json — same as v4's progress table,
    // with pending_scrobbles dropped.
    private val v5CreateTable = v4CreateTable
    private val v5IdentityHash = "2748fc6495e0489eaa1ea10e27656425"

    // From app/schemas/dev.mike.couchtour.PhishInDb/6.json.
    private val v6CreateTable = """
        CREATE TABLE IF NOT EXISTS `progress` (
            `queueKey` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
            `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
            `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
            `finished` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, `artist` TEXT NOT NULL,
            PRIMARY KEY(`queueKey`)
        )
    """.trimIndent()
    private val v6IdentityHash = "4d896ba9c2e33ae0f04a776ea8820b5f"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("migration-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    private fun createV1DatabaseWithRows() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(v1CreateTable)
        // Room's own bookkeeping, so it recognises this as a genuine v1 database.
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v1IdentityHash),
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt)
               VALUES ('show:1992-12-02','1992-12-02','Newport Music Hall',NULL,22,169397,'Rocky Top',200)"""
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt)
               VALUES ('show:1997-02-13','1997-02-13','Shepherd''s Bush',NULL,5,35342,'Taste',100)"""
        )
        db.version = 1
        db.close()
    }

    private fun createV2DatabaseWithRows() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(v2CreateTable)
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v2IdentityHash),
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished)
               VALUES ('show:1992-12-02','1992-12-02','Newport',NULL,22,169397,'Rocky Top',200,1)"""
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished)
               VALUES ('show:1997-02-13','1997-02-13','Shepherd''s Bush',NULL,5,35342,'Taste',100,0)"""
        )
        db.version = 2
        db.close()
    }

    private fun createV4DatabaseWithRows() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(v4CreateTable)
        db.execSQL(v4PendingScrobblesTable)
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v4IdentityHash),
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed)
               VALUES ('show:1992-12-02','1992-12-02','Newport',NULL,22,169397,'Rocky Top',200,1,0)"""
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed)
               VALUES ('show:1997-02-13','1997-02-13','Shepherd''s Bush',NULL,5,35342,'Taste',100,0,0)"""
        )
        db.execSQL(
            """INSERT INTO pending_scrobbles (artist, track, album, durationSec, timestampSec)
               VALUES ('Phish','Tweezer','1997-11-17',300,1700000000)"""
        )
        db.version = 4
        db.close()
    }

    private fun createV5DatabaseWithRows() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(v5CreateTable)
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v5IdentityHash),
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed)
               VALUES ('show:1992-12-02','1992-12-02','Newport',NULL,22,169397,'Rocky Top',200,1,0)"""
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed)
               VALUES ('playlist:key-jams','Key Jams','by mfhgreyboy',NULL,5,35342,'Taste',100,0,0)"""
        )
        db.version = 5
        db.close()
    }

    private fun createV6DatabaseWithRows() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(v6CreateTable)
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v6IdentityHash),
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed, artist)
               VALUES ('show:1992-12-02','1992-12-02','Newport',NULL,22,169397,'Rocky Top',200,1,0,'Phish')"""
        )
        db.execSQL(
            """INSERT INTO progress
               (queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle, updatedAt, finished, dismissed, artist)
               VALUES ('show:1997-02-13','1997-02-13','Shepherd''s Bush',NULL,5,35342,'Taste',100,0,0,'Phish')"""
        )
        db.version = 6
        db.close()
    }

    private fun openWithCurrentSchema(): PhishInDb =
        Room.databaseBuilder(context, PhishInDb::class.java, dbFile.name)
            .addMigrations(
                PhishInDb.MIGRATION_1_2,
                PhishInDb.MIGRATION_2_3,
                PhishInDb.MIGRATION_3_4,
                PhishInDb.MIGRATION_4_5,
                PhishInDb.MIGRATION_5_6,
                PhishInDb.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()

    @Test
    fun `migrating from v1 keeps every row`() = runBlocking {
        createV1DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val rows = db.progressDao().inProgress().first()
            assertEquals(2, rows.size)
            assertEquals(
                listOf("show:1992-12-02", "show:1997-02-13"),
                rows.map { it.queueKey }.sorted(),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrating preserves the stored position and track`() = runBlocking {
        createV1DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val row = db.progressDao().get("show:1992-12-02")!!
            assertEquals(22, row.trackIndex)
            assertEquals(169_397L, row.positionMs)
            assertEquals("Rocky Top", row.trackTitle)
            assertEquals("Newport Music Hall", row.subtitle)
        } finally {
            db.close()
        }
    }

    @Test
    fun `existing rows default to not finished and not dismissed`() = runBlocking {
        createV1DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            // Rows that predate the flags are not backfilled; inferring would be a guess.
            val row = db.progressDao().get("show:1992-12-02")!!
            assertFalse(row.finished)
            assertFalse(row.dismissed)
            assertEquals(2, db.progressDao().inProgress().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrated database accepts finished rows`() = runBlocking {
        createV1DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val dao = db.progressDao()
            dao.put(dao.get("show:1992-12-02")!!.copy(finished = true))

            assertEquals(listOf("show:1997-02-13"), dao.inProgress().first().map { it.queueKey })
            assertEquals(2, dao.history().first().size)
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------ v2 -> v3

    @Test
    fun `migrating from v2 keeps rows and the finished flag`() = runBlocking {
        createV2DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val dao = db.progressDao()
            assertEquals(2, dao.history().first().size)
            // The v2 value must survive, not reset to the new column's default.
            assertTrue(dao.get("show:1992-12-02")!!.finished)
            assertFalse(dao.get("show:1997-02-13")!!.finished)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 rows arrive not dismissed`() = runBlocking {
        createV2DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val dao = db.progressDao()
            assertFalse(dao.get("show:1997-02-13")!!.dismissed)
            // Finished stays out of the row, unfinished stays in it.
            assertEquals(listOf("show:1997-02-13"), dao.inProgress().first().map { it.queueKey })
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------ v4 -> v5

    @Test
    fun `migrating from v4 drops the pending scrobbles table and keeps progress rows`() = runBlocking {
        createV4DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            // Built-in scrobbling was removed; the queue table it fed has nothing left to
            // read it, so v5 drops it. Progress is a different table entirely and survives.
            val dao = db.progressDao()
            assertEquals(2, dao.history().first().size)
            assertTrue(dao.get("show:1992-12-02")!!.finished)
            assertFalse(dao.get("show:1997-02-13")!!.finished)

            val tableExists = db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'pending_scrobbles'"
            ).use { it.count > 0 }
            assertFalse(tableExists)
        } finally {
            db.close()
        }
    }

    @Test
    fun `dismissing works on a migrated database`() = runBlocking {
        createV2DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val dao = db.progressDao()
            dao.dismiss("show:1997-02-13")

            assertEquals(0, dao.inProgress().first().size)
            assertEquals(2, dao.history().first().size)
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------ v5 -> v6

    @Test
    fun `migrating from v5 keeps every row and its position`() = runBlocking {
        createV5DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val rows = db.progressDao().history().first()
            assertEquals(2, rows.size)
            val show = rows.first { it.queueKey == "show:1992-12-02" }
            assertEquals(22, show.trackIndex)
            assertEquals(169397L, show.positionMs)
            assertEquals("Rocky Top", show.trackTitle)
            assertTrue(show.finished)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrating from v5 backfills every existing row as Phish`() = runBlocking {
        // Not a guess, which is what separates this from D21: until the second backend
        // existed, phish.in was the only thing the app could play, so every row already in
        // the table is Phish — including the playlist rows, whose tracks are Phish too.
        createV5DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val rows = db.progressDao().history().first()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.artist == "Phish" })
        } finally {
            db.close()
        }
    }

    @Test
    fun `a database migrated all the way from v1 is also backfilled`() = runBlocking {
        // The whole chain runs, so the backfill has to survive four migrations ahead of it.
        createV1DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val rows = db.progressDao().history().first()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.artist == "Phish" })
            assertEquals(listOf("Phish"), db.progressDao().artists().first())
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------ v6 -> v7

    @Test
    fun `migrating from v6 keeps every row, live and not deleted`() = runBlocking {
        createV6DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val dao = db.progressDao()
            assertEquals(2, dao.history().first().size)
            assertNull(dao.get("show:1992-12-02")!!.deletedAt)
            assertNull(dao.get("show:1997-02-13")!!.deletedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun `clearing works on a database migrated from v6`() = runBlocking {
        createV6DatabaseWithRows()

        val db = openWithCurrentSchema()
        try {
            val dao = db.progressDao()
            dao.clear("show:1997-02-13", now = 9_999)

            assertNull(dao.get("show:1997-02-13"))
            assertEquals(1, dao.history().first().size)
        } finally {
            db.close()
        }
    }
}
