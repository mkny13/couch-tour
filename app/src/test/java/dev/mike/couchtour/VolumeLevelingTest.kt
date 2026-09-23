package dev.mike.couchtour

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Android volume-leveling half (#267): the playback gain processor, the decode-ahead
 * measurer (HTTP + pooling + cache interplay), and [VolumeLeveler]'s one-at-a-time rule.
 *
 * The `MediaCodecSegmentDecoder` is deliberately absent — Robolectric can't run a real
 * codec, so tests inject fixture PCM via [SegmentDecoder] and the real decoder is verified
 * on device (UAT). Same seam the macOS measurer tests use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeLevelingTest {

    // -----------------------------------------------------------------------
    // PCM helpers — mirror LoudnessTest's generator shape
    // -----------------------------------------------------------------------

    private fun interleavedStereoSine(
        sampleRate: Int,
        seconds: Double,
        dbfs: Double,
        freq: Double = 997.0,
    ): FloatArray {
        val amp = 10.0.pow(dbfs / 20.0)
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n * 2) { i ->
            val frame = i / 2
            (amp * kotlin.math.sin(2.0 * Math.PI * freq * frame / sampleRate)).toFloat()
        }
    }

    private fun floatsToPcm16(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) {
            buf.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        return buf.array()
    }

    private fun floatsToBytes(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        for (s in samples) {
            buf.putFloat(s)
        }
        return buf.array()
    }

    private fun configuredProcessor(sampleRate: Int, channels: Int, floatPcm: Boolean = false): LevelingAudioProcessor {
        val p = LevelingAudioProcessor()
        p.configure(
            AudioProcessor.AudioFormat(
                sampleRate, channels,
                if (floatPcm) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
            )
        )
        return p
    }

    /** Queue raw interleaved PCM through the configured processor and drain the output. */
    private fun push(p: LevelingAudioProcessor, samples: FloatArray, floatPcm: Boolean = false): FloatArray {
        val bytes = if (floatPcm) floatsToBytes(samples) else floatsToPcm16(samples)
        val inBuf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        p.queueInput(inBuf)
        p.queueEndOfStream()
        val chunk = p.output.order(ByteOrder.nativeOrder())
        val result = FloatArray(samples.size)
        if (floatPcm) {
            val fb = chunk.asFloatBuffer()
            for (i in result.indices) result[i] = fb.get()
        } else {
            val sb = chunk.asShortBuffer()
            for (i in result.indices) result[i] = sb.get() / 32767f
        }
        return result
    }

    // -----------------------------------------------------------------------
    // LevelingAudioProcessor
    // -----------------------------------------------------------------------

    @Test
    fun `processor boosts a quiet signal by +6 dB`() {
        val p = configuredProcessor(48_000, 2)
        p.setGainDb(6.0)
        val input = interleavedStereoSine(48_000, 1.0, -30.0)
        val out = push(p, input)
        // After the 50 ms ramp (2400 frames at 48 kHz) the gain is settled at
        // 10^(6/20) ≈ 1.9953; compare the settled tail against the input scaled
        // by the same factor.
        val skip = 3000 * 2 // 3000 frames, stereo
        for (i in skip until out.size) {
            assertEquals(input[i] * 1.9953f, out[i], 1e-3f)
        }
    }

    @Test
    fun `processor clamps int16 output instead of wrapping`() {
        val p = configuredProcessor(48_000, 2)
        p.setGainDb(12.0)
        // Full-scale sine × 4× gain = way past ±1.0 — every sample must pin, not wrap.
        val out = push(p, interleavedStereoSine(48_000, 0.5, -0.5))
        assertTrue(out.all { it <= 1.0001f && it >= -1.0001f })
    }

    @Test
    fun `processor passes float pcm through with unity gain`() {
        val p = configuredProcessor(48_000, 2, floatPcm = true)
        p.setGainDb(0.0) // unity
        val input = interleavedStereoSine(48_000, 0.5, -20.0)
        val out = push(p, input, floatPcm = true)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-6f)
    }

    @Test
    fun `processor applies gain to float pcm`() {
        val p = configuredProcessor(48_000, 2, floatPcm = true)
        p.setGainDb(6.0)
        val input = interleavedStereoSine(48_000, 1.0, -30.0)
        val out = push(p, input, floatPcm = true)
        val skip = 3000 * 2
        for (i in skip until out.size) {
            assertEquals(input[i] * 1.9953f, out[i], 1e-3f)
        }
    }

    @Test
    fun `processor clamps requested gain to plus or minus 12 dB`() {
        val p = configuredProcessor(48_000, 2)
        p.setGainDb(20.0) // clamped to +12 dB ≈ 3.9811
        val input = interleavedStereoSine(48_000, 1.0, -30.0)
        val out = push(p, input)
        val skip = 3000 * 2
        for (i in skip until out.size) {
            assertEquals(input[i] * 3.9811f, out[i], 1e-2f)
        }
    }

    @Test
    fun `processor rejects unsupported encodings`() {
        val p = LevelingAudioProcessor()
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test
    fun `resetGain returns to unity with a ramp`() {
        val p = configuredProcessor(48_000, 2)
        p.setGainDb(-12.0)
        push(p, FloatArray(4800 * 2)) // 4800 frames of silence — ramp fully settled
        p.resetGain()
        val input = interleavedStereoSine(48_000, 1.0, -20.0)
        val out = push(p, input)
        assertEquals(input[3000 * 2], out[3000 * 2], 1e-3f) // settled back to 0 dB
    }

    // -----------------------------------------------------------------------
    // levelingSampleIndices
    // -----------------------------------------------------------------------

    @Test
    fun `sample indices spread across the track list`() {
        assertEquals(listOf(0), levelingSampleIndices(total = 1))
        assertEquals(listOf(0, 1), levelingSampleIndices(total = 2))
        // Canonical macOS vectors: up to 3 samples, spread, never the tail alone.
        assertEquals(listOf(1, 3, 5), levelingSampleIndices(total = 7))
        assertEquals(listOf(5, 10, 15), levelingSampleIndices(total = 20))
        assertEquals(listOf(3, 6, 9), levelingSampleIndices(total = 12))
        // More tracks than max caps at max.
        assertEquals(3, levelingSampleIndices(total = 100).size)
    }

    @Test
    fun `sample indices on degenerate inputs are empty`() {
        assertTrue(levelingSampleIndices(total = 0).isEmpty())
        assertTrue(levelingSampleIndices(total = 5, max = 0).isEmpty())
    }

    // -----------------------------------------------------------------------
    // LoudnessMeasurer: HTTP + pooling
    // -----------------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUpMeasurer() {
        server = MockWebServer()
        server.start()
        tempDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "leveling-test")
        tempDir.mkdirs()
    }

    @After
    fun tearDownMeasurer() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** A fake decoder that ignores the file and hands the meter a fixed 30 s segment. */
    private class FixedPcmDecoder(
        private val sampleRate: Int = 48_000,
        private val channels: Int = 2,
        private val seconds: Double = 30.0,
    ) : SegmentDecoder {
        var decoded = 0
        override fun decode(file: File): DecodedPcm {
            decoded++
            // The measurer must have written the segment to a real file for us.
            check(file.exists()) { "expected the segment file on disk" }
            return DecodedPcm(
                sampleRate, channels,
                FloatArray((sampleRate * seconds).toInt() * channels) {
                    (0.5 * kotlin.math.sin(2.0 * Math.PI * 997.0 * (it / channels) / sampleRate)).toFloat()
                },
            )
        }
    }

    private fun head(length: Long = 1_600_000L) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", length.toString())

    private fun sample(url: String, durationMs: Long = 300_000L) = LevelingSample(url, durationMs)

    /** Bytes don't matter (the decoder is fake); size does — HEAD reports it, Range slices it. */
    private fun enqueuedMp3Bytes(): Buffer = Buffer().write(ByteArray(1_600_000) { (it % 251).toByte() })

    private fun mp3Response(body: Buffer) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", body.size.toString())
        .setBody(body)

    private fun measurer(decoder: SegmentDecoder) = LoudnessMeasurer(
        client = okhttp3.OkHttpClient(),
        decoder = decoder,
        tempDir = tempDir,
    )

    @Test
    fun `measurer pools segments and skips failed ones`() = runBlocking {
        val body = enqueuedMp3Bytes()
        server.enqueue(head(body.size))
        server.enqueue(mp3Response(body.clone()))
        server.enqueue(head(body.size))
        server.enqueue(MockResponse().setResponseCode(404)) // segment 2 fails — skipped
        server.enqueue(head(body.size))
        server.enqueue(mp3Response(body.clone()))

        val decoder = FixedPcmDecoder()
        val result = measurer(decoder).measure((1..3).map { sample(server.url("/t$it.mp3").toString()) })

        assertNotNull(result)
        assertEquals(2, result!!.sampledTracks)
        assertEquals(2, decoder.decoded)
        // A −6 dBFS stereo sine measures ≈ −9 LUFS (same vector as LoudnessTest).
        assertEquals(-9.0, result.lufs, 0.5)
    }

    @Test
    fun `measurer returns null when every segment fails`() = runBlocking {
        repeat(3) {
            server.enqueue(head())
            server.enqueue(MockResponse().setResponseCode(404))
        }
        val result = measurer(FixedPcmDecoder()).measure((1..3).map { sample(server.url("/t$it.mp3").toString()) })
        assertNull(result)
    }

    @Test
    fun `measurer returns null for an empty track list`() = runBlocking {
        assertNull(measurer(FixedPcmDecoder()).measure(emptyList()))
    }

    @Test
    fun `measurer skips mismatched formats rather than corrupting the meter`() = runBlocking {
        val body = enqueuedMp3Bytes()
        repeat(3) {
            server.enqueue(head(body.size))
            server.enqueue(mp3Response(body.clone()))
        }
        // Segment 2 decodes at a different rate — a real archive risk when MP3s from
        // different masters get pooled. It must be skipped, not pooled.
        var call = 0
        val switching = SegmentDecoder { _ ->
            call++
            if (call == 2) DecodedPcm(44_100, 2, FloatArray(44_100 * 30 * 2) { 0.3f })
            else DecodedPcm(48_000, 2, FloatArray(48_000 * 30 * 2) { 0.3f })
        }
        val result = measurer(switching).measure((1..3).map { sample(server.url("/t$it.mp3").toString()) })
        assertNotNull(result)
        assertEquals(2, result!!.sampledTracks) // first + third; the 44.1 k one skipped
    }

    @Test
    fun `measurer deletes temp segment files`() = runBlocking {
        val body = enqueuedMp3Bytes()
        server.enqueue(head(body.size))
        server.enqueue(mp3Response(body))
        measurer(FixedPcmDecoder()).measure(listOf(sample(server.url("/t.mp3").toString())))
        assertTrue(tempDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `measurer falls back to assumed byte rate without content-length`() = runBlocking {
        // HEAD with no Content-Length, then the Range GET.
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(mp3Response(enqueuedMp3Bytes()))
        val result = measurer(FixedPcmDecoder()).measure(listOf(sample(server.url("/t.mp3").toString())))
        assertNotNull(result)
    }

    // -----------------------------------------------------------------------
    // Settings toggle — persisted (PlaybackSettings)
    // -----------------------------------------------------------------------

    @Test
    fun `level volume defaults to off`() {
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertFalse(PlaybackSettings.levelVolume.value)
    }

    @Test
    fun `level volume persists across init`() {
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        PlaybackSettings.setLevelVolume(true)
        assertTrue(PlaybackSettings.levelVolume.value)

        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertTrue("should persist", PlaybackSettings.levelVolume.value)

        PlaybackSettings.setLevelVolume(false)
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertFalse(PlaybackSettings.levelVolume.value)
    }
}

// ---------------------------------------------------------------------------
// VolumeLeveler — cache interplay and the one-at-a-time rule
// ---------------------------------------------------------------------------

/**
 * The cache/seams half of #267, against an in-memory Room instance — the same shape the
 * macOS measurer tests use for the `source_loudness` cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeLevelerTest {

    private lateinit var db: PhishInDb
    private lateinit var scope: CoroutineScope
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhishInDb::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        tempDir = File(context.cacheDir, "leveler-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        db.close()
        tempDir.deleteRecursively()
    }

    private fun entity(key: String, lufs: Double, peakDb: Double = -1.0, version: Int = LEVELING_ALGORITHM_VERSION) =
        SourceLoudnessEntity(
            key = key,
            lufs = lufs,
            peakDb = peakDb,
            sampledTracks = 2,
            measuredAt = System.currentTimeMillis(),
            algorithmVersion = version,
        )

    private fun sample(url: String = "https://example.com/1.mp3", durationMs: Long = 300_000L) =
        LevelingSample(url, durationMs)

    private open class TestMeasurer(
        private val result: LoudnessMeasurement?,
        var measureCallCount: Int = 0,
    ) : LoudnessMeasurer(
        decoder = SegmentDecoder { DecodedPcm(48000, 2, FloatArray(0)) },
        tempDir = File("/tmp"),
    ) {
        override suspend fun measure(tracks: List<LevelingSample>): LoudnessMeasurement? {
            measureCallCount++
            return result
        }
    }

    @Test
    fun `cached row short-circuits measurement`() = runBlocking {
        db.sourceLoudnessDao().upsert(entity("show:1997-11-22", lufs = -14.0, peakDb = -2.0))
        val measurer = TestMeasurer(LoudnessMeasurement(lufs = -20.0, peakDb = -1.0, sampledTracks = 3))
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onQueueChanged("show:1997-11-22", listOf(sample())) { gains.add(it) }?.join()

        // Initial 0 dB, then cached gain:
        // -18 - (-14) = -4 dB
        assertEquals(0, measurer.measureCallCount)
        assertEquals(listOf(0.0, -4.0), gains)
    }

    @Test
    fun `stale algorithm version reads as a cache miss and triggers measurement`() = runBlocking {
        db.sourceLoudnessDao().upsert(entity("show:1997-11-22", lufs = -14.0, version = LEVELING_ALGORITHM_VERSION + 1))
        val measurer = TestMeasurer(LoudnessMeasurement(lufs = -22.0, peakDb = -6.0, sampledTracks = 2))
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onQueueChanged("show:1997-11-22", listOf(sample())) { gains.add(it) }?.join()

        assertEquals(1, measurer.measureCallCount)
        // -18 - (-22) = +4 dB; peak cap -1 - (-6) = +5 dB
        assertEquals(listOf(0.0, 4.0), gains)
        val cached = db.sourceLoudnessDao().getCurrent("show:1997-11-22", LEVELING_ALGORITHM_VERSION)
        assertNotNull(cached)
        assertEquals(-22.0, cached!!.lufs, 0.001)
    }

    @Test
    fun `cache miss triggers measurement, writes to cache, and updates gain mid-playback`() = runBlocking {
        val measurer = TestMeasurer(LoudnessMeasurement(lufs = -24.0, peakDb = -8.0, sampledTracks = 3))
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onQueueChanged("show:1998-07-15", listOf(sample())) { gains.add(it) }?.join()

        assertEquals(1, measurer.measureCallCount)
        // -18 - (-24) = +6 dB; peak cap -1 - (-8) = +7 dB
        assertEquals(listOf(0.0, 6.0), gains)

        val cached = db.sourceLoudnessDao().getCurrent("show:1998-07-15", LEVELING_ALGORITHM_VERSION)
        assertNotNull(cached)
        assertEquals(-24.0, cached!!.lufs, 0.001)
        assertEquals(3, cached.sampledTracks)
    }

    @Test
    fun `failed measurement leaves no row in cache and stays at unity`() = runBlocking {
        val measurer = TestMeasurer(result = null)
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onQueueChanged("show:1999-12-31", listOf(sample())) { gains.add(it) }?.join()

        assertEquals(1, measurer.measureCallCount)
        assertEquals(listOf(0.0), gains)
        assertNull(db.sourceLoudnessDao().getCurrent("show:1999-12-31", LEVELING_ALGORITHM_VERSION))
    }

    @Test
    fun `disabled leveler resets gain to 0 and cancels measurement`() = runBlocking {
        val measurer = TestMeasurer(LoudnessMeasurement(lufs = -24.0, peakDb = -1.0, sampledTracks = 1))
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onDisabled { gains.add(it) }

        assertEquals(listOf(0.0), gains)
    }

    @Test
    fun `unkeyed queue resets gain to 0 and stops measurement`() = runBlocking {
        val measurer = TestMeasurer(LoudnessMeasurement(lufs = -24.0, peakDb = -1.0, sampledTracks = 1))
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onQueueChanged(null, listOf(sample())) { gains.add(it) }

        assertEquals(0, measurer.measureCallCount)
        assertEquals(listOf(0.0), gains)
    }

    @Test
    fun `same key in repeated transitions does not re-measure`() = runBlocking {
        val measurer = TestMeasurer(LoudnessMeasurement(lufs = -20.0, peakDb = -1.0, sampledTracks = 1))
        val leveler = VolumeLeveler(scope, db, measurer)

        val gains = mutableListOf<Double>()
        leveler.onQueueChanged("show:2000-01-01", listOf(sample())) { gains.add(it) }?.join()
        leveler.onQueueChanged("show:2000-01-01", listOf(sample())) { gains.add(it) }?.join()

        assertEquals(1, measurer.measureCallCount)
    }

    @Test
    fun `upsert overwrites rather than duplicating a key`() = runBlocking {
        val dao = db.sourceLoudnessDao()
        dao.upsert(entity("show:1997-11-22", lufs = -14.0))
        dao.upsert(entity("show:1997-11-22", lufs = -12.5))
        val row = dao.getCurrent("show:1997-11-22", LEVELING_ALGORITHM_VERSION)
        assertNotNull(row)
        assertEquals(-12.5, row!!.lufs, 0.001)
    }
}
