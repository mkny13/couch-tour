package dev.mike.couchtour

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** Round-trips [SyncTokenStore], mirroring [TokenStoreTest]'s pattern in SessionTest.kt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTokenStoreTest {

    private fun store() = SyncTokenStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `round-trips a device token and id`() {
        val store = store()
        store.deviceToken = "ct_abc"
        store.deviceId = "device-1"
        assertEquals("ct_abc", store.deviceToken)
        assertEquals("device-1", store.deviceId)
    }

    @Test
    fun `defaults the cursors to zero`() {
        assertEquals(0L, store().lastSeq)
        assertEquals(0L, store().lastPushWatermark)
    }

    @Test
    fun `round-trips the cursors`() {
        val store = store()
        store.lastSeq = 42
        store.lastPushWatermark = 7
        assertEquals(42L, store.lastSeq)
        assertEquals(7L, store.lastPushWatermark)
    }

    @Test
    fun `clear wipes everything`() {
        val store = store()
        store.deviceToken = "ct_abc"
        store.deviceId = "device-1"
        store.lastSeq = 42
        store.lastPushWatermark = 7

        store.clear()

        assertNull(store.deviceToken)
        assertNull(store.deviceId)
        assertEquals(0L, store.lastSeq)
        assertEquals(0L, store.lastPushWatermark)
    }
}

/** Exercises SyncApi's outgoing requests against a local server, mirroring ApiRequestTest.kt. */
class SyncApiRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        SyncApi.baseUrl = server.url("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
        SyncApi.baseUrl = "https://couch-tour-sync.mkastellec.workers.dev".toHttpUrl()
    }

    private fun enqueue(body: String, code: Int = 200, rotatedToken: String? = null) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        rotatedToken?.let { response.setHeader("X-Sync-Token-Rotated", it) }
        server.enqueue(response)
    }

    private fun take(): RecordedRequest = server.takeRequest()

    @Test
    fun `pairStart posts device name and platform, no auth when bootstrapping`() = runBlocking {
        enqueue("""{"code":"ABCD1234","expiresAt":1000,"deviceId":"d1","deviceToken":"ct_x"}""")

        val response = SyncApi.pairStart("Pixel", "android", existingToken = null)

        val request = take()
        assertTrue(request.path!!.endsWith("/pair/start"))
        assertNull(request.getHeader("Authorization"))
        assertEquals("""{"deviceName":"Pixel","platform":"android"}""", request.body.readUtf8())
        assertEquals("ABCD1234", response.code)
        assertEquals("d1", response.deviceId)
        assertEquals("ct_x", response.deviceToken)
    }

    @Test
    fun `pairStart sends the bearer token when already paired`() = runBlocking {
        enqueue("""{"code":"ABCD1234","expiresAt":1000}""")

        SyncApi.pairStart("Pixel", "android", existingToken = "ct_existing")

        assertEquals("Bearer ct_existing", take().getHeader("Authorization"))
    }

    @Test
    fun `pairClaim posts just the code, name, and platform`() = runBlocking {
        enqueue("""{"deviceId":"d2","deviceToken":"ct_y"}""")

        val response = SyncApi.pairClaim("ABCD1234", "MacBook", "macos")

        val request = take()
        assertTrue(request.path!!.endsWith("/pair/claim"))
        assertEquals(
            """{"code":"ABCD1234","deviceName":"MacBook","platform":"macos"}""",
            request.body.readUtf8()
        )
        assertEquals("d2", response.deviceId)
        assertEquals("ct_y", response.deviceToken)
    }

    @Test
    fun `sync sends the bearer token, since, and changes`() = runBlocking {
        enqueue("""{"seq":5,"changes":[]}""")
        val change = SyncProgressWire(
            queueKey = "show:1997-11-17", title = "t", subtitle = "s", trackIndex = 0,
            positionMs = 100, trackTitle = "Track", updatedAt = 1000, finished = false,
            dismissed = false, artist = "Phish",
        )

        val (response, rotated) = SyncApi.sync("ct_token", since = 2, changes = listOf(change))

        val request = take()
        assertTrue(request.path!!.endsWith("/sync"))
        assertEquals("Bearer ct_token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().startsWith("""{"since":2,"changes":[{"queueKey":"show:1997-11-17""""))
        assertEquals(5L, response.seq)
        assertNull(rotated)
    }

    @Test
    fun `sync surfaces a rotated token from the response header`() = runBlocking {
        enqueue("""{"seq":1,"changes":[]}""", rotatedToken = "ct_new")

        val (_, rotated) = SyncApi.sync("ct_old", since = 0, changes = emptyList())

        assertEquals("ct_new", rotated)
    }

    @Test
    fun `devices parses the list`() = runBlocking {
        enqueue(
            """{"devices":[{"deviceId":"d1","name":"Pixel","platform":"android",""" +
                """"createdAt":100,"lastSeenAt":200,"isSelf":true}]}"""
        )

        val devices = SyncApi.devices("ct_token")

        assertEquals(1, devices.size)
        assertEquals("Pixel", devices[0].name)
        assertTrue(devices[0].isSelf)
    }

    @Test
    fun `revokeDevice sends a DELETE to the device path`() = runBlocking {
        enqueue("""{"revoked":true}""")

        SyncApi.revokeDevice("ct_token", "d1")

        val request = take()
        assertEquals("DELETE", request.method)
        assertTrue(request.path!!.endsWith("/devices/d1"))
    }

    @Test
    fun `a 401 throws SyncException marked unauthorized`() = runBlocking {
        enqueue("""{"error":"unauthorized"}""", code = 401)

        val error = runCatching { SyncApi.devices("ct_bad") }.exceptionOrNull()

        assertTrue(error is SyncException)
        assertTrue((error as SyncException).unauthorized)
    }

    @Test
    fun `a 410 throws SyncException marked gone`() = runBlocking {
        enqueue("""{"error":"cursor too old; full resync required"}""", code = 410)

        val error = runCatching { SyncApi.sync("ct_token", 1, emptyList()) }.exceptionOrNull()

        assertTrue(error is SyncException)
        assertTrue((error as SyncException).gone)
    }
}

/**
 * End-to-end [SyncSession] behaviour against a mock server and an in-memory Room database —
 * the same combination Robolectric/MockWebServer tests use elsewhere in this suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var db: PhishInDb

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        SyncApi.baseUrl = server.url("/")
        SyncSession.init(ApplicationProvider.getApplicationContext())
        SyncSession.unlink()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PhishInDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        SyncSession.unlink()
        db.close()
        server.shutdown()
        SyncApi.baseUrl = "https://couch-tour-sync.mkastellec.workers.dev".toHttpUrl()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun claim() {
        enqueue("""{"deviceId":"d2","deviceToken":"ct_y"}""")
        runBlocking { SyncSession.claimPairing("ABCD1234") }
        server.takeRequest() // the pair/claim request itself, out of the way for callers
    }

    @Test
    fun `sync is a no-op when unpaired`() = runBlocking {
        SyncSession.sync(db.progressDao())

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `claiming pairing stores the token`() = runBlocking {
        claim()

        assertTrue(SyncSession.paired.value)
    }

    @Test
    fun `sync pushes rows changed since the watermark`() = runBlocking {
        claim()
        db.progressDao().put(
            Progress(
                queueKey = "show:1997-11-17", title = "t", subtitle = "s", artUrl = null,
                trackIndex = 0, positionMs = 100, trackTitle = "Track", updatedAt = 5_000,
                artist = "Phish",
            )
        )
        enqueue("""{"seq":1,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        val pushed = server.takeRequest().body.readUtf8()
        assertTrue(pushed.contains(""""queueKey":"show:1997-11-17""""))
        assertTrue(pushed.contains(""""since":0"""))
    }

    @Test
    fun `sync does not repush a row already at the watermark`() = runBlocking {
        claim()
        db.progressDao().put(
            Progress(
                queueKey = "show:1997-11-17", title = "t", subtitle = "s", artUrl = null,
                trackIndex = 0, positionMs = 100, trackTitle = "Track", updatedAt = 5_000,
                artist = "Phish",
            )
        )
        enqueue("""{"seq":1,"changes":[]}""")
        SyncSession.sync(db.progressDao())
        server.takeRequest() // the first sync's push

        enqueue("""{"seq":1,"changes":[]}""")
        SyncSession.sync(db.progressDao())

        val secondPush = server.takeRequest().body.readUtf8()
        assertEquals("""{"since":1,"changes":[]}""", secondPush)
    }

    @Test
    fun `sync applies pulled rows into the local database`() = runBlocking {
        claim()
        enqueue(
            """{"seq":3,"changes":[{"queueKey":"show:1997-11-17","title":"t","subtitle":"s",""" +
                """"artUrl":null,"trackIndex":2,"positionMs":9000,"trackTitle":"Tweezer",""" +
                """"updatedAt":5000,"finished":false,"dismissed":false,"artist":"Phish","deletedAt":null}]}"""
        )

        SyncSession.sync(db.progressDao())

        val row = db.progressDao().get("show:1997-11-17")!!
        assertEquals(2, row.trackIndex)
        assertEquals(9_000L, row.positionMs)
    }

    @Test
    fun `a rotated token is used on the next call`() = runBlocking {
        claim()
        server.enqueue(
            MockResponse().setBody("""{"seq":1,"changes":[]}""")
                .setHeader("X-Sync-Token-Rotated", "ct_rotated")
        )
        SyncSession.sync(db.progressDao())
        server.takeRequest() // the rotating sync call

        enqueue("""{"seq":1,"changes":[]}""")
        SyncSession.sync(db.progressDao())

        assertEquals("Bearer ct_rotated", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `an unauthorized response unlinks the device`() = runBlocking {
        claim()
        enqueue("""{"error":"unauthorized"}""", code = 401)

        SyncSession.sync(db.progressDao())

        assertFalse(SyncSession.paired.value)
    }

    @Test
    fun `a gone response resets the cursor and retries once`() = runBlocking {
        claim()
        val requestsBeforeSync = server.requestCount
        enqueue("""{"error":"cursor too old"}""", code = 410)
        enqueue("""{"seq":9,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        // The 410 plus its retry: two requests beyond whatever claim() already made.
        assertEquals(2, server.requestCount - requestsBeforeSync)
        assertTrue(SyncSession.paired.value)
    }
}
