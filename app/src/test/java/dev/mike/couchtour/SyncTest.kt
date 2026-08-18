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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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
        assertEquals(0L, store().lastSyncedAt)
    }

    @Test
    fun `round-trips the cursors`() {
        val store = store()
        store.lastSeq = 42
        store.lastPushWatermark = 7
        store.lastSyncedAt = 1_700_000_000_000L
        assertEquals(42L, store.lastSeq)
        assertEquals(7L, store.lastPushWatermark)
        assertEquals(1_700_000_000_000L, store.lastSyncedAt)
    }

    @Test
    fun `clear wipes everything`() {
        val store = store()
        store.deviceToken = "ct_abc"
        store.deviceId = "device-1"
        store.lastSeq = 42
        store.lastPushWatermark = 7
        store.lastSyncedAt = 1_700_000_000_000L

        store.clear()

        assertNull(store.deviceToken)
        assertNull(store.deviceId)
        assertEquals(0L, store.lastSeq)
        assertEquals(0L, store.lastPushWatermark)
        assertEquals(0L, store.lastSyncedAt)
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
    fun `a null artUrl and deletedAt are sent as explicit nulls, not omitted`() = runBlocking {
        // The bug that broke sync entirely: kotlinx.serialization omits any property still
        // equal to its default unless encodeDefaults is set, so a row with no artwork sent
        // neither key at all. The server's D1 bind() rejects `undefined`, so every push
        // containing such a row 500'd — and since artUrl is null for every Relisten row,
        // that was every push.
        enqueue("""{"seq":1,"changes":[]}""")
        val change = SyncProgressWire(
            queueKey = "show:1997-11-17", title = "t", subtitle = "s", artUrl = null,
            trackIndex = 0, positionMs = 100, trackTitle = "Track", updatedAt = 1000,
            finished = false, dismissed = false, artist = "Phish", deletedAt = null,
        )

        SyncApi.sync("ct_token", since = 0, changes = listOf(change))

        val body = take().body.readUtf8()
        assertTrue("artUrl missing from $body", body.contains(""""artUrl":null"""))
        assertTrue("deletedAt missing from $body", body.contains(""""deletedAt":null"""))
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
    fun `sync sets lastSyncedAt on success`() = runBlocking {
        claim()
        assertEquals(0L, SyncSession.lastSyncedAt.value)
        enqueue("""{"seq":1,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        assertTrue(SyncSession.lastSyncedAt.value > 0L)
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

    @Test
    fun `requestDebouncedPush coalesces a burst of calls into a single push`() = runBlocking {
        claim()
        db.progressDao().put(
            Progress(
                queueKey = "show:1997-11-17", title = "t", subtitle = "s", artUrl = null,
                trackIndex = 0, positionMs = 100, trackTitle = "Track", updatedAt = 5_000,
                artist = "Phish",
            )
        )
        enqueue("""{"seq":1,"changes":[]}""")

        // Mirrors onIsPlayingChanged/onMediaItemTransition/onPlaybackStateChanged all firing
        // for the same real event — only the last-scheduled delay should actually land.
        SyncSession.requestDebouncedPush(db.progressDao(), delayMs = 50)
        SyncSession.requestDebouncedPush(db.progressDao(), delayMs = 50)
        SyncSession.requestDebouncedPush(db.progressDao(), delayMs = 50)

        val pushed = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(pushed)
        assertTrue(pushed!!.body.readUtf8().contains(""""queueKey":"show:1997-11-17""""))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    // ------------------------------------------------------------------ push chunking
    //
    // The server caps a push at 500 entries because D1 allows only 100 bound parameters per
    // query; before either limit existed, a first pair with 100+ rows of history 500'd the
    // sync endpoint outright. These cover the client half of that fix.

    private suspend fun seedProgress(count: Int, updatedAtFor: (Int) -> Long) {
        repeat(count) { i ->
            db.progressDao().put(
                Progress(
                    queueKey = "show:seed-$i", title = "t", subtitle = "s", artUrl = null,
                    trackIndex = 0, positionMs = 0, trackTitle = "Track",
                    updatedAt = updatedAtFor(i), artist = "Phish",
                )
            )
        }
    }

    private fun pushedKeyCount(request: RecordedRequest): Int =
        Regex(""""queueKey"""").findAll(request.body.readUtf8()).count()

    @Test
    fun `a backlog over the batch size is pushed across several requests`() = runBlocking {
        claim()
        val requestsBeforeSync = server.requestCount
        seedProgress(950) { i -> 1_000L + i }
        enqueue("""{"seq":1,"changes":[]}""")
        enqueue("""{"seq":2,"changes":[]}""")
        enqueue("""{"seq":3,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        // 400 + 400 + 150, then the loop stops because the last batch was short.
        assertEquals(3, server.requestCount - requestsBeforeSync)
        assertEquals(400, pushedKeyCount(server.takeRequest()))
        assertEquals(400, pushedKeyCount(server.takeRequest()))
        assertEquals(150, pushedKeyCount(server.takeRequest()))
    }

    @Test
    fun `no request exceeds the server's own cap`() = runBlocking {
        claim()
        seedProgress(600) { i -> 1_000L + i }
        enqueue("""{"seq":1,"changes":[]}""")
        enqueue("""{"seq":2,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        assertTrue(pushedKeyCount(server.takeRequest()) <= 500)
        assertTrue(pushedKeyCount(server.takeRequest()) <= 500)
    }

    @Test
    fun `a batch boundary never splits rows sharing one updatedAt`() = runBlocking {
        claim()
        // Sorted ascending this lays out as 1_000..1_379, then forty rows all on 1_380, then
        // 1_381..1_460 — so the shared-timestamp run straddles the 400-row boundary. Splitting
        // it would advance the watermark past 1_380 and `changedSince` is strictly `>`, so the
        // leftovers would never be offered again: a silent lost write.
        seedProgress(500) { i ->
            when {
                i < 380 -> 1_000L + i
                i < 420 -> 1_380L
                else -> 1_381L + (i - 420)
            }
        }
        enqueue("""{"seq":1,"changes":[]}""")
        enqueue("""{"seq":2,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        val first = server.takeRequest()
        val second = server.takeRequest()
        // Trimmed back off the shared-timestamp run rather than cutting through it.
        assertEquals(380, pushedKeyCount(first))
        // Everything still reaches the server across the two pushes: nothing was dropped.
        assertEquals(500, 380 + pushedKeyCount(second))
    }

    @Test
    fun `a single updatedAt bigger than the batch is sent whole rather than stalling`() = runBlocking {
        claim()
        val requestsBeforeSync = server.requestCount
        // 450 rows on one millisecond: trimming to the run boundary would empty the batch, so
        // the run goes out whole — still under the server's 500 cap.
        seedProgress(450) { 3_000L }
        enqueue("""{"seq":1,"changes":[]}""")

        SyncSession.sync(db.progressDao())

        assertEquals(1, server.requestCount - requestsBeforeSync)
        assertEquals(450, pushedKeyCount(server.takeRequest()))
    }
}
