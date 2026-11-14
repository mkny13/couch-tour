package dev.mike.couchtour

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceResumeTest {

    private lateinit var serviceController: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService
    private lateinit var controller: MediaController
    private val queueKey = "show:1997-11-22"

    private val servers = CatalogServers(
        phishIn = mapOf(
            "/shows/1997-11-22" to """{
                "date":"1997-11-22","venue_name":"Hampton Coliseum","audio_status":"complete",
                "tracks":[
                    {"id":1,"title":"Tweezer","position":1,"duration":600000,"audio_status":"complete",
                     "mp3_url":"https://example.invalid/1.mp3"},
                    {"id":2,"title":"Black-Eyed Katy","position":2,"duration":600000,"audio_status":"complete",
                     "mp3_url":"https://example.invalid/2.mp3"},
                    {"id":3,"title":"Halley's Comet","position":3,"duration":600000,"audio_status":"complete",
                     "mp3_url":"https://example.invalid/3.mp3"}
                ]
            }""",
        ),
        relisten = mapOf(
            "/v3/artists" to """[{"uuid":"a-1","name":"Widespread Panic","slug":"widespread-panic","show_count":100}]""",
            // The real RelistenApi.show path (v2) and the required track `uuid`: with either
            // wrong the resume never loads a queue and the test passes without exercising it.
            "/v2/artists/widespread-panic/shows/2000-05-06" to """{
                "id":1001,"display_date":"2000-05-06",
                "sources":[{
                    "id":2001,"uuid":"src-1234",
                    "sets":[{
                        "index":1,"name":"Set 1",
                        "tracks":[
                            {"uuid":"t-3001","title":"Chilly Water","duration":400,"mp3_url":"https://example.invalid/rec1.mp3"},
                            {"uuid":"t-3002","title":"Space Wrangler","duration":500,"mp3_url":"https://example.invalid/rec2.mp3"},
                            {"uuid":"t-3003","title":"Pigeons","duration":600,"mp3_url":"https://example.invalid/rec3.mp3"}
                        ]
                    }]
                }]
            }""",
        ),
    )

    @Before
    fun setUp() {
        servers.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))

        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        service = serviceController.get()

        val token = service.session!!.token
        val future = MediaController.Builder(app, token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        controller = future.get()
    }

    @After
    fun tearDown() {
        controller.release()
        serviceController.destroy()
        servers.shutdown()
    }

    @Test
    fun `initial preparation callback with zero position does not clobber saved resume position`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dao = PhishInDb.get(app).progressDao()

        val saved = Progress(
            queueKey = queueKey,
            title = "1997-11-22",
            subtitle = "Hampton Coliseum",
            artUrl = null,
            trackIndex = 2,
            positionMs = 45_000L,
            trackTitle = "Halley's Comet",
            updatedAt = 1_000L,
            artist = "Phish",
        )
        runBlocking { dao.put(saved) }

        val vm = PlayerViewModel(app)
        vm.attach(controller)

        // Trigger resume through ViewModel
        vm.resume(saved)

        val deadline = System.currentTimeMillis() + 5_000
        while (controller.mediaItemCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Before playback starts, player is at position 0 (e.g. seeking/buffering stream)
        // Simulate preparation callback while position is 0
        service.session!!.player.seekTo(2, 0L)
        service.playerListener.onPlaybackStateChanged(Player.STATE_BUFFERING)

        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        shadowOf(Looper.getMainLooper()).idle()

        val recorded = runBlocking { dao.get(queueKey) }
        assertNotNull("Progress should be saved in DB", recorded)
        assertEquals("Track index must remain at the saved non-first track", 2, recorded!!.trackIndex)
        assertEquals("Position must remain at the saved nonzero timestamp", 45_000L, recorded.positionMs)
        // Title must describe the retained track, not the track-0 item the player passed through.
        assertEquals("Track title must match the retained track index", "Halley's Comet", recorded.trackTitle)
    }

    @Test
    fun `initial transition callback for track 0 during resume does not clobber saved track index`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dao = PhishInDb.get(app).progressDao()

        val saved = Progress(
            queueKey = queueKey,
            title = "1997-11-22",
            subtitle = "Hampton Coliseum",
            artUrl = null,
            trackIndex = 2,
            positionMs = 45_000L,
            trackTitle = "Halley's Comet",
            updatedAt = 1_000L,
            artist = "Phish",
        )
        runBlocking { dao.put(saved) }

        val vm = PlayerViewModel(app)
        vm.attach(controller)

        // Trigger resume through ViewModel
        vm.resume(saved)

        val deadline = System.currentTimeMillis() + 5_000
        while (controller.mediaItemCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Simulate initial transition callback through track 0 before reaching track 2
        service.session!!.player.seekTo(0, 0L)
        service.playerListener.onMediaItemTransition(
            service.session!!.player.getMediaItemAt(0),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )

        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        shadowOf(Looper.getMainLooper()).idle()

        val recorded = runBlocking { dao.get(queueKey) }
        assertNotNull("Progress should be saved in DB", recorded)
        assertEquals("Track index must remain at the saved non-first track", 2, recorded!!.trackIndex)
        assertEquals("Position must remain at the saved nonzero timestamp", 45_000L, recorded.positionMs)
        // Title must describe the retained track, not the track-0 item the player passed through.
        assertEquals("Track title must match the retained track index", "Halley's Comet", recorded.trackTitle)
    }

    @Test
    fun `recording resume for non-phish artist preserves saved track and position`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dao = PhishInDb.get(app).progressDao()

        val recordingKey = "relisten:widespread-panic/2000-05-06/src-1234"
        val saved = Progress(
            queueKey = recordingKey,
            title = "2000-05-06",
            subtitle = "Oak Mountain",
            artUrl = null,
            trackIndex = 1,
            positionMs = 65_000L,
            trackTitle = "Space Wrangler",
            updatedAt = 1_000L,
            artist = "Widespread Panic",
        )
        runBlocking { dao.put(saved) }

        val vm = PlayerViewModel(app)
        vm.attach(controller)

        vm.resume(saved)

        val deadline = System.currentTimeMillis() + 5_000
        while (controller.mediaItemCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Simulate initial buffering callback while position is 0
        service.session!!.player.seekTo(1, 0L)
        service.playerListener.onPlaybackStateChanged(Player.STATE_BUFFERING)

        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        shadowOf(Looper.getMainLooper()).idle()

        val recorded = runBlocking { dao.get(recordingKey) }
        assertNotNull("Recording progress should be in DB", recorded)
        assertEquals("Track index must remain at saved track", 1, recorded!!.trackIndex)
        assertEquals("Position must remain at saved timestamp", 65_000L, recorded.positionMs)
    }

    @Test
    fun `progress updates naturally once playback advances past resume timestamp`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dao = PhishInDb.get(app).progressDao()

        val saved = Progress(
            queueKey = queueKey,
            title = "1997-11-22",
            subtitle = "Hampton Coliseum",
            artUrl = null,
            trackIndex = 2,
            positionMs = 45_000L,
            trackTitle = "Halley's Comet",
            updatedAt = 1_000L,
            artist = "Phish",
        )
        runBlocking { dao.put(saved) }

        val vm = PlayerViewModel(app)
        vm.attach(controller)

        vm.resume(saved)

        val deadline = System.currentTimeMillis() + 5_000
        while (controller.mediaItemCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Playback advances to 50_000ms and pauses
        service.session!!.player.seekTo(2, 50_000L)
        service.playerListener.onIsPlayingChanged(false)

        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        shadowOf(Looper.getMainLooper()).idle()

        val recorded = runBlocking { dao.get(queueKey) }
        assertNotNull("Progress should be saved in DB", recorded)
        assertEquals(2, recorded!!.trackIndex)
        assertEquals("Position should advance to new timestamp", 50_000L, recorded.positionMs)
    }
}
