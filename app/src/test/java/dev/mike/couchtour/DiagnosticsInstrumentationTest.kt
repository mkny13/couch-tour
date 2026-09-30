package dev.mike.couchtour

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsInstrumentationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var testFilesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        testFilesDir = tmpFolder.newFolder("filesDir")
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = testFilesDir
            override fun getApplicationContext(): Context = this
        }
        DiagnosticsLog.resetForTest()
        DiagnosticsLog.init(context)
        DiagnosticsLog.flushBlocking()

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        DiagnosticsLog.clear()
        DiagnosticsLog.resetForTest()
    }

    @Test
    fun `timing event listener records start connected and end for a 500 server response`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val client = OkHttpClient.Builder()
            .eventListenerFactory { TimingEventListener("TestApi") }
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v2/test_endpoint"))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(500, response.code)
        }

        DiagnosticsLog.flushBlocking()
        val lines = DiagnosticsLog.tailLines(20)

        assertTrue("Expected api.call phase=start", lines.any { it.contains("api.call\tpath=/api/v2/test_endpoint phase=start ms=0 reused=false") })
        assertTrue("Expected api.call phase=connected", lines.any { it.contains("api.call\tpath=/api/v2/test_endpoint phase=connected ms=") && it.contains("reused=") })
        assertTrue("Expected api.call phase=end", lines.any { it.contains("api.call\tpath=/api/v2/test_endpoint phase=end ms=") && it.contains("reused=") })
    }

    @Test
    fun `timing event listener records phase failed for dropped connection`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val client = OkHttpClient.Builder()
            .eventListenerFactory { TimingEventListener("TestApi") }
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v2/dropped"))
            .build()

        try {
            client.newCall(request).execute().close()
        } catch (_: IOException) {
            // Expected
        }

        DiagnosticsLog.flushBlocking()
        val lines = DiagnosticsLog.tailLines(20)

        assertTrue("Expected api.call phase=failed", lines.any {
            it.contains("api.call\tpath=/api/v2/dropped phase=failed ms=") && it.contains("error=")
        })
    }

    @Test
    fun `redaction denylist holds for sync error line`() {
        DiagnosticsLog.log(
            "sync.error",
            DiagnosticsLog.Level.WARN,
            "code" to "unauthorized",
            "authToken" to "jwt_secret_token_value",
            "pairingCode" to "123456",
            "syncKey" to "secret_sync_key",
            "password" to "my_password"
        )
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(5)
        val line = lines.last { it.contains("sync.error") }

        // Short code should be preserved
        assertTrue(line.contains("code=unauthorized"))

        // Credentials must be redacted
        assertTrue(line.contains("authToken=***"))
        assertTrue(line.contains("pairingCode=***"))
        assertTrue(line.contains("syncKey=***"))
        assertTrue(line.contains("password=***"))

        // Raw values must not appear anywhere
        assertFalse(line.contains("jwt_secret_token_value"))
        assertFalse(line.contains("123456"))
        assertFalse(line.contains("secret_sync_key"))
        assertFalse(line.contains("my_password"))
    }

    @Test
    fun `library counts is emitted with zeros when lists are empty`() {
        emitLibraryCounts(0, 0, 0)
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(5)
        val line = lines.last { it.contains("library.counts") }
        assertTrue(line.contains("library.counts\tplaylists=0 shows=0 tracks=0 total=0"))

        val summary = DiagnosticsLog.summaryLines()
        assertTrue(summary.contains("Library counts: playlists=0 shows=0 tracks=0 total=0"))
    }

    @Test
    fun `no line in log contains full URL with query string header auth token or pairing code for API and sync paths`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = OkHttpClient.Builder()
            .eventListenerFactory { TimingEventListener("TestApi") }
            .build()

        val sensitiveToken = "Bearer-Secret-Token-12345"
        val sensitivePairing = "PAIRING-998877"
        val url = server.url("/api/v2/search?query=tweezer&code=$sensitivePairing&token=$sensitiveToken")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", sensitiveToken)
            .header("X-Custom-Auth", "secret-header")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Also log sync events with short codes and potential sensitive fields
        DiagnosticsLog.log("sync.start")
        DiagnosticsLog.log("sync.end", "pushed" to 5, "pulled" to 2, "ms" to 42, "pushed_upto" to 1000L, "pulled_seq" to 12L)
        DiagnosticsLog.log("sync.error", DiagnosticsLog.Level.WARN, "code" to "server")

        DiagnosticsLog.flushBlocking()

        val exported = DiagnosticsLog.exportText(context)
        val lines = exported.lines().filter { it.isNotBlank() }

        // Ensure we have logged events
        assertTrue(lines.any { it.contains("api.call") })
        assertTrue(lines.any { it.contains("sync.start") })
        assertTrue(lines.any { it.contains("sync.end") })
        assertTrue(lines.any { it.contains("sync.error") })

        for (l in lines) {
            assertFalse("Log line must not contain query string: $l", l.contains("?query="))
            assertFalse("Log line must not contain token in query string: $l", l.contains(sensitiveToken))
            assertFalse("Log line must not contain pairing code: $l", l.contains(sensitivePairing))
            assertFalse("Log line must not contain header name or value: $l", l.contains("Authorization") || l.contains("secret-header"))
            assertFalse("Log line must not contain full http URL: $l", l.contains("http://") || l.contains("https://"))
        }
    }

    @Test
    fun `sync error codes are short codes and never raw exception messages`() {
        val unauthorizedEx = SyncException("HTTP 401 https://sync.example.com/sync?token=secret_123", code = 401)
        val goneEx = SyncException("HTTP 410 cursor expired", code = 410)
        val serverEx = SyncException("HTTP 500 internal server error", code = 500)
        val networkEx = java.net.SocketTimeoutException("timeout reading from https://sync.example.com")
        val otherEx = IllegalStateException("Something broke with url https://sync.example.com")

        assertEquals("unauthorized", syncErrorCode(unauthorizedEx))
        assertEquals("gone", syncErrorCode(goneEx))
        assertEquals("server", syncErrorCode(serverEx))
        assertEquals("network", syncErrorCode(networkEx))
        assertEquals("other", syncErrorCode(otherEx))

        // Log each code and verify
        for (ex in listOf(unauthorizedEx, goneEx, serverEx, networkEx, otherEx)) {
            val code = syncErrorCode(ex)
            DiagnosticsLog.log("sync.error", DiagnosticsLog.Level.WARN, "code" to code)
        }
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(10)
        val errorLines = lines.filter { it.contains("sync.error") }
        assertEquals(5, errorLines.size)
        assertTrue(errorLines.any { it.contains("code=unauthorized") })
        assertTrue(errorLines.any { it.contains("code=gone") })
        assertTrue(errorLines.any { it.contains("code=server") })
        assertTrue(errorLines.any { it.contains("code=network") })
        assertTrue(errorLines.any { it.contains("code=other") })

        // Verify no raw exception messages or URLs were logged
        assertFalse(lines.any { it.contains("https://") })
        assertFalse(lines.any { it.contains("secret_123") })
    }

    @Test
    fun `favorites and toggle events emit expected diagnostics lines`() {
        LikedTracks.init(context)
        SavedShows.init(context)
        Favorites.init(context)

        LikedTracks.toggle("track-uuid-99")
        SavedShows.toggle("1997-11-17")
        Favorites.toggle("phish")

        DiagnosticsLog.flushBlocking()
        val lines = DiagnosticsLog.tailLines(10)

        assertTrue("Expected liked track favorite", lines.any { it.contains("library.favorite\tkind=track id=track-uuid-99 on=true") })
        assertTrue("Expected saved show favorite", lines.any { it.contains("library.favorite\tkind=show key=1997-11-17 on=true") })
        assertTrue("Expected artist favorite", lines.any { it.contains("library.favorite\tkind=artist key=phish on=true") })
    }

    @Test
    fun `local playlist addTrack and removeTrack emit library playlist events`() = kotlinx.coroutines.runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, PhishInDb::class.java).allowMainThreadQueries().build()
        val dao = db.localPlaylistDao()
        dao.insertPlaylist(LocalPlaylistEntity("pl-1", "My Mix", 0, 0, 0))
        val track = LocalPlaylistTrackEntity(rowId = 1L, playlistId = "pl-1", position = 0, backend = "phishin", trackId = "12345", showDate = "1997-11-17", title = "Ghost")
        dao.addTrack(track, now = 100L)
        dao.removeTrack(1L, "pl-1", now = 200L)
        db.close()

        DiagnosticsLog.flushBlocking()
        val lines = DiagnosticsLog.tailLines(10)
        assertTrue(lines.any { it.contains("library.playlist\taction=add track=12345 playlist=pl-1") })
        assertTrue(lines.any { it.contains("library.playlist\taction=remove track=12345 playlist=pl-1") })
    }

    @Test
    fun `playback start stop and progress format verification`() {
        DiagnosticsLog.log("playback.start", "track" to "12345", "show" to "1997-11-17", "source" to "phishin", "resume" to false)
        DiagnosticsLog.log("playback.progress", "track" to "12345", "pct" to 42, "status" to "saved")
        DiagnosticsLog.log("playback.stop")
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(10)
        assertTrue(lines.any { it.contains("playback.start\ttrack=12345 show=1997-11-17 source=phishin resume=false") })
        assertTrue(lines.any { it.contains("playback.progress\ttrack=12345 pct=42 status=saved") })
        assertTrue(lines.any { it.contains("playback.stop") })
    }

    @Test
    fun `nav route logs pattern only without query or arguments`() {
        DiagnosticsLog.log("nav.route", "route" to "youtube/{videoId}")
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(5)
        assertTrue(lines.any { it.contains("nav.route\troute=youtube/{videoId}") })
    }
}
