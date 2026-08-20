package dev.mike.couchtour

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPlaylistDaoTest {

    private lateinit var db: PhishInDb
    private lateinit var dao: LocalPlaylistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PhishInDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.localPlaylistDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(playlistId: String, position: Int, backend: Backend, trackId: String, title: String) =
        LocalPlaylistTrackEntity(
            playlistId = playlistId, position = position, backend = backend.id,
            trackId = trackId, showDate = "1997-11-17", title = title,
        )

    @Test
    fun `addTrack appends in order and bumps trackCount`() = runBlocking {
        dao.insertPlaylist(LocalPlaylistEntity("p1", "Key Jams", createdAt = 0, updatedAt = 0))

        dao.addTrack(track("p1", 0, Backend.PHISHIN, "1", "Tweezer"), now = 100)
        dao.addTrack(track("p1", 0, Backend.RELISTEN, "uuid-2", "Scarlet Begonias"), now = 200)

        val playlist = dao.playlist("p1")!!
        assertEquals(2, playlist.trackCount)
        assertEquals(200L, playlist.updatedAt)
        assertEquals(listOf("Tweezer", "Scarlet Begonias"), dao.tracksOnce("p1").map { it.title })
        assertEquals(listOf(0, 1), dao.tracksOnce("p1").map { it.position })
    }

    @Test
    fun `removeTrack drops the row and decrements trackCount`() = runBlocking {
        dao.insertPlaylist(LocalPlaylistEntity("p1", "Key Jams", createdAt = 0, updatedAt = 0))
        dao.addTrack(track("p1", 0, Backend.PHISHIN, "1", "Tweezer"), now = 100)
        dao.addTrack(track("p1", 0, Backend.PHISHIN, "2", "Sand"), now = 200)
        val rowId = dao.tracksOnce("p1").first { it.title == "Tweezer" }.rowId

        dao.removeTrack(rowId, "p1", now = 300)

        val playlist = dao.playlist("p1")!!
        assertEquals(1, playlist.trackCount)
        assertEquals(300L, playlist.updatedAt)
        assertEquals(listOf("Sand"), dao.tracksOnce("p1").map { it.title })
    }

    @Test
    fun `deletePlaylist cascades to its tracks`() = runBlocking {
        dao.insertPlaylist(LocalPlaylistEntity("p1", "Key Jams", createdAt = 0, updatedAt = 0))
        dao.addTrack(track("p1", 0, Backend.PHISHIN, "1", "Tweezer"), now = 100)

        dao.deletePlaylist("p1")

        assertNull(dao.playlist("p1"))
        assertTrue(dao.tracksOnce("p1").isEmpty())
    }

    @Test
    fun `playlists lists newest-updated first`() = runBlocking {
        dao.insertPlaylist(LocalPlaylistEntity("old", "Old", createdAt = 0, updatedAt = 100))
        dao.insertPlaylist(LocalPlaylistEntity("new", "New", createdAt = 0, updatedAt = 200))

        assertEquals(listOf("New", "Old"), dao.playlists().first().map { it.name })
    }
}
