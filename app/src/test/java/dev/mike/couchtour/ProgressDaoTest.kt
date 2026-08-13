package dev.mike.couchtour

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressDaoTest {

    private lateinit var db: PhishInDb
    private lateinit var dao: ProgressDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PhishInDb::class.java,
        ).allowMainThreadQueries().build()
        dao = db.progressDao()
    }

    @After
    fun tearDown() = db.close()

    private fun progress(
        key: String,
        trackIndex: Int = 0,
        positionMs: Long = 0,
        finished: Boolean = false,
        dismissed: Boolean = false,
        updatedAt: Long = 1_000,
        trackTitle: String = "Track",
        artist: String = "Phish",
    ) = Progress(
        queueKey = key,
        title = key,
        subtitle = "subtitle",
        artUrl = null,
        trackIndex = trackIndex,
        positionMs = positionMs,
        trackTitle = trackTitle,
        updatedAt = updatedAt,
        finished = finished,
        dismissed = dismissed,
        artist = artist,
    )

    @Test
    fun `stores and reads back a row`() = runBlocking {
        dao.put(progress("show:1997-02-13", trackIndex = 5, positionMs = 35_342))

        val row = dao.get("show:1997-02-13")!!
        assertEquals(5, row.trackIndex)
        assertEquals(35_342L, row.positionMs)
        assertEquals(false, row.finished)
    }

    @Test
    fun `returns null for a queue never played`() = runBlocking {
        assertNull(dao.get("show:1970-01-01"))
    }

    @Test
    fun `replaces rather than duplicating on the same key`() = runBlocking {
        dao.put(progress("show:1997-02-13", positionMs = 1_000))
        dao.put(progress("show:1997-02-13", positionMs = 2_000))

        assertEquals(1, dao.inProgress().first().size)
        assertEquals(2_000L, dao.get("show:1997-02-13")!!.positionMs)
    }

    @Test
    fun `continue listening excludes finished queues`() = runBlocking {
        dao.put(progress("show:1997-02-13", finished = false))
        dao.put(progress("show:1992-12-02", finished = true))

        assertEquals(listOf("show:1997-02-13"), dao.inProgress().first().map { it.queueKey })
    }

    @Test
    fun `history contains finished and unfinished alike`() = runBlocking {
        dao.put(progress("show:1997-02-13", finished = false, updatedAt = 200))
        dao.put(progress("show:1992-12-02", finished = true, updatedAt = 100))

        assertEquals(
            listOf("show:1997-02-13", "show:1992-12-02"),
            dao.history().first().map { it.queueKey }
        )
    }

    @Test
    fun `dismissing hides from continue listening but keeps it in history`() = runBlocking {
        dao.put(progress("show:1997-02-13"))

        dao.dismiss("show:1997-02-13")

        assertEquals(0, dao.inProgress().first().size)
        assertEquals(1, dao.history().first().size)
        assertTrue(dao.get("show:1997-02-13")!!.dismissed)
    }

    @Test
    fun `dismissing preserves the stored position`() = runBlocking {
        dao.put(progress("show:1997-02-13", trackIndex = 5, positionMs = 35_342))

        dao.dismiss("show:1997-02-13")

        val row = dao.get("show:1997-02-13")!!
        assertEquals(5, row.trackIndex)
        assertEquals(35_342L, row.positionMs)
    }

    @Test
    fun `playing a dismissed queue again brings it back`() = runBlocking {
        dao.put(progress("show:1997-02-13"))
        dao.dismiss("show:1997-02-13")

        // Saving during playback writes dismissed = false, the same way finished clears.
        dao.put(progress("show:1997-02-13", positionMs = 5_000))

        assertEquals(1, dao.inProgress().first().size)
        assertFalse(dao.get("show:1997-02-13")!!.dismissed)
    }

    @Test
    fun `a finished queue stays out of continue listening even when not dismissed`() = runBlocking {
        dao.put(progress("show:1992-12-02", finished = true, dismissed = false))

        assertEquals(0, dao.inProgress().first().size)
        assertEquals(1, dao.history().first().size)
    }

    @Test
    fun `marking completed moves it out of continue listening`() = runBlocking {
        dao.put(progress("show:1997-02-13", trackIndex = 5, positionMs = 35_342))

        dao.markFinished("show:1997-02-13")

        assertEquals(0, dao.inProgress().first().size)
        assertTrue(dao.get("show:1997-02-13")!!.finished)
        assertEquals(1, dao.history().first().size)
    }

    @Test
    fun `marking completed leaves the position intact`() = runBlocking {
        dao.put(progress("show:1997-02-13", trackIndex = 5, positionMs = 35_342))

        dao.markFinished("show:1997-02-13")

        // Playing it again restarts from the top, but the row itself is not rewritten.
        val row = dao.get("show:1997-02-13")!!
        assertEquals(5, row.trackIndex)
        assertEquals(35_342L, row.positionMs)
    }

    @Test
    fun `history count covers every state`() = runBlocking {
        dao.put(progress("show:a"))
        dao.put(progress("show:b", finished = true))
        dao.put(progress("show:c", dismissed = true))

        assertEquals(3, dao.historyCount().first())
        assertEquals(1, dao.inProgress().first().size)
    }

    @Test
    fun `continue listening is newest first`() = runBlocking {
        dao.put(progress("show:a", updatedAt = 100))
        dao.put(progress("show:c", updatedAt = 300))
        dao.put(progress("show:b", updatedAt = 200))

        assertEquals(
            listOf("show:c", "show:b", "show:a"),
            dao.inProgress().first().map { it.queueKey }
        )
    }

    @Test
    fun `history is newest first`() = runBlocking {
        dao.put(progress("show:a", finished = true, updatedAt = 100))
        dao.put(progress("show:b", finished = true, updatedAt = 200))

        assertEquals(listOf("show:b", "show:a"), dao.history().first().map { it.queueKey })
    }

    @Test
    fun `replaying a finished queue returns it to continue listening`() = runBlocking {
        dao.put(progress("show:1992-12-02", finished = true))
        assertEquals(0, dao.inProgress().first().size)

        dao.put(progress("show:1992-12-02", finished = false))

        assertEquals(1, dao.inProgress().first().size)
        assertFalse(dao.get("show:1992-12-02")!!.finished)
    }

    @Test
    fun `clearing forgets a queue entirely`() = runBlocking {
        dao.put(progress("show:1997-02-13"))

        dao.clear("show:1997-02-13")

        // Unlike dismissing, this leaves no trace in history either.
        assertNull(dao.get("show:1997-02-13"))
        assertEquals(0, dao.inProgress().first().size)
        assertEquals(0, dao.history().first().size)
    }

    @Test
    fun `clearing an absent key is a no-op`() = runBlocking {
        dao.put(progress("show:1997-02-13"))

        dao.clear("show:nonexistent")

        assertEquals(1, dao.inProgress().first().size)
    }

    @Test
    fun `shows and playlists coexist under one table`() = runBlocking {
        dao.put(progress(showQueueKey("1997-02-13"), updatedAt = 100))
        dao.put(progress(playlistQueueKey("key-jams"), updatedAt = 200))

        val keys = dao.inProgress().first().map { it.queueKey }
        assertEquals(listOf("playlist:key-jams", "show:1997-02-13"), keys)
        assertEquals(QueueKind.PLAYLIST, parseQueueKey(keys[0])!!.kind)
        assertEquals(QueueKind.SHOW, parseQueueKey(keys[1])!!.kind)
    }

    @Test
    fun `continue listening is capped so the row cannot grow without bound`() = runBlocking {
        repeat(30) { dao.put(progress("show:$it", updatedAt = it.toLong())) }

        assertEquals(25, dao.inProgress().first().size)
    }

    // ---------------------------------------------------------------- artists

    @Test
    fun `lists the artists in history, distinct and sorted`() = runBlocking {
        dao.put(progress("show:1997-02-13", artist = "Phish"))
        dao.put(progress("relisten:grateful-dead/1977-05-08/a", artist = "Grateful Dead"))
        dao.put(progress("relisten:grateful-dead/1972-08-27/b", artist = "Grateful Dead"))
        dao.put(progress("relisten:wsp/2001-04-22/c", artist = "Widespread Panic"))

        assertEquals(
            listOf("Grateful Dead", "Phish", "Widespread Panic"),
            dao.artists().first(),
        )
    }

    @Test
    fun `filters history to one artist, newest first`() = runBlocking {
        dao.put(progress("relisten:grateful-dead/1972-08-27/b", artist = "Grateful Dead", updatedAt = 100))
        dao.put(progress("show:1997-02-13", artist = "Phish", updatedAt = 200))
        dao.put(progress("relisten:grateful-dead/1977-05-08/a", artist = "Grateful Dead", updatedAt = 300))

        assertEquals(
            listOf("relisten:grateful-dead/1977-05-08/a", "relisten:grateful-dead/1972-08-27/b"),
            dao.historyFor("Grateful Dead").first().map { it.queueKey },
        )
    }

    @Test
    fun `a row with no artist is left out of the artist list`() = runBlocking {
        // Nothing writes an empty artist today, but a row could predate the backfill on a
        // database restored from somewhere unexpected. An empty heading is worse than none.
        dao.put(progress("show:1997-02-13", artist = ""))
        dao.put(progress("show:1998-11-02", artist = "Phish"))

        assertEquals(listOf("Phish"), dao.artists().first())
    }
}
