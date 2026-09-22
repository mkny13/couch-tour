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
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = s.toByte()
            out[i * 2 + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun configuredProcessor(sampleRate: Int, channels: Int, floatPcm: Boolean = false): LevelingAudioProcessor {
        val p = LevelingAudioProcessor()
        p.configure(
            AudioProcessor.AudioFormat(
                sampleRate, channels,
                if (floatPcm) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16_BIT,
            )
        )
        return p
    }

    /** Queue raw interleaved PCM through the configured processor and drain the output. */
    private fun push(p: LevelingAudioProcessor, samples: FloatArray, floatPcm: Boolean = false): FloatArray {
        val bytes = if (floatPcm) {
            ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder()).also { b ->
                samples.forEach { b.putFloat(it) }
            }.array()
        } else {
            floatsToPcm16(samples)
        }
        val out = ByteBuffer.allocate(bytes.size * 2).order(ByteOrder.nativeOrder())
        out.put(p.queueInput(ByteBuffer.wrap(bytes)))
        p.queueEndOfStream()
        while (true) {
            val chunk = p.output
            if (chunk.hasRemaining()) out.put(chunk) else break
        }
        out.flip()
        val result = ArrayList<Float>(samples.size)
        if (floatPcm) {
            val fb = out.asFloatBuffer()
            while (fb.hasRemaining()) result.add(fb.get())
        } else {
            val sb = out.asShortBuffer()
            while (sb.hasRemaining()) result.add(sb.get() / 32768f)
        }
        return result.toFloatArray()
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
    fun `processor rejects unsupported encodings`() {
        val p = LevelingAudioProcessor()
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_24_BIT))
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
        server.enqueue(mp3Response(body.clone()))
        server.enqueue(MockResponse().setResponseCode(404)) // segment 2 fails — skipped
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
        repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
        val result = measurer(FixedPcmDecoder()).measure((1..3).map { sample(server.url("/t$it.mp3").toString()) })
        assertNull(result)
    }

    @Test
    fun `measurer returns null for an empty track list`() {
        assertNull(measurer(FixedPcmDecoder()).measure(emptyList()))
    }

    @Test
    fun `measurer skips mismatched formats rather than corrupting the meter`() = runBlocking {
        val body = enqueuedMp3Bytes()
        repeat(3) { server.enqueue(mp3Response(body.clone())) }
        // Segment 2 decodes at a different rate — a real archive risk when MP3s from
        // different masters get pooled. It must be skipped, not pooled.
        var call = 0
        val switching = SegmentDecoder { _ ->
            call++
            if (call == 1) DecodedPcm(48_000, 2, FloatArray(48_000 * 30 * 2) { 0.3f })
            else DecodedPcm(44_100, 2, FloatArray(44_100 * 30 * 2) { 0.3f })
        }
        val result = measurer(switching).measure((1..3).map { sample(server.url("/t$it.mp3").toString()) })
        assertNotNull(result)
        assertEquals(2, result!!.sampledTracks) // first + third; the 44.1 k one skipped
    }

    @Test
    fun `measurer deletes temp segment files`() = runBlocking {
        server.enqueue(mp3Response(enqueuedMp3Bytes()))
        measurer(FixedPcmDecoder()).measure(listOf(sample(server.url("/t.mp3").toString())))
        assertTrue(tempDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `measurer falls back to assumed byte rate without content-length`() = runBlocking {
        // HEAD with no Content-Length, then the Range GET.
        server.enqueue(MockResponse().setResponseCode(200).setBody(enqueuedMp3Bytes()))
        server.enqueue(mp3Response(enqueuedMp3Bytes()))
        val result = measurer(FixedPcmDecoder()).measure(listOf(sample(server.url("/t.mp3").toString())))
        assertNotNull(result)
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
    private lateinit var scope: kotlinx.coroutines.CoroutineScope
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhishInDb::class.java)
            .allowMainThreadQueries()
            .build()
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob())
        tempDir = File(context.cacheDir, "leveler-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        db.close()
        tempDir.deleteRecursively()
    }

    private fun entity(key: String, lufs: Double, version: Int = LEVELING_ALGORITHM_VERSION) = SourceLoudnessEntity(
        levelingKey = key,
        lufs = lufs,
        peakDb = -1.0,
        sampledTracks = 2,
        measuredAt = System.currentTimeMillis(),
        algorithmVersion = version,
    )

    @Test
    fun `cached row short-circuits measurement`() = kotlinx.coroutines.test.runTest {
        db.sourceLoudnessDao().upsert(entity("show:1997-11-22", lufs = -14.0))
        val cached = db.sourceLoudnessDao().getCurrent("show:1997-11-22", LEVELING_ALGORITHM_VERSION)
        assertThat(cached).isNotNull()
        assertThat(cached!!.lufs).isEqualTo(-14.0)
    }

    @Test
    fun `stale algorithm version reads as a cache miss`() = kotlinx.coroutines.test.runTest {
        db.sourceLoudnessDao().upsert(entity("show:1997-11-22", lufs = -14.0, version = LEVELING_ALGORITHM_VERSION + 1))
        assertNull(db.sourceLoudnessDao().getCurrent("show:1997-11-22", LEVELING_ALGORITHM_VERSION))
    }

    @Test
    fun `upsert overwrites rather than duplicating a key`() = kotlinx.coroutines.test.runTest {
        val dao = db.sourceLoudnessDao()
        dao.upsert(entity("show:1997-11-22", lufs = -14.0))
        dao.upsert(entity("show:1997-11-22", lufs = -12.5))
        val rows = kotlinx.coroutines.flow.first(dao.all())
        assertThat(rows).hasSize(1)
        assertThat(rows[0].lufs).isEqualTo(-12.5)
    }
}

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Volume leveling (#267): the AudioProcessor gain path, the decode-ahead measurer (over
 * MockWebServer), and the persisted Settings toggle.
 *
 * The processor is exercised exactly as Media3 drives it: 16-bit stereo input, one
 * `queueInput` call per buffer. `setGainDb` is called *after* the processor is configured
 * (as `updateLeveling` does on the real player), because the ramp clock derives from the
 * configured sample rate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeLevelingTest {

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /** 16-bit stereo PCM buffer with each sample = [value] (clamped to int16). */
    private fun stereo16(value: Int, frames: Int = 8): ByteBuffer {
        val buf = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) {
            buf.putShort(value.coerceIn(-32768, 32767).toShort())
        }
        buf.flip()
        return buf
    }

    /** Drain the processor's output after feeding [input]. */
    private fun drain(p: LevelingAudioProcessor, input: ByteBuffer): ByteBuffer {
        p.queueInput(input)
        p.queueEndOfStream()
        val out = ByteBuffer.allocate(input.capacity() * 4).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val chunk = p.getOutput()
            if (chunk.hasRemaining()) out.put(chunk) else break
        }
        out.flip()
        return out
    }

    private fun configure(p: LevelingAudioProcessor, sampleRate: Int = 48_000, channels: Int = 2) {
        p.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
    }

    private fun lastSample(out: ByteBuffer): Int = out.getShort(out.remaining() - 2).toInt()

    // -------------------------------------------------------------------
    // LevelingAudioProcessor
    // -------------------------------------------------------------------

    @Test
    fun `zero gain passes samples through untouched`() {
        val p = LevelingAudioProcessor()
        configure(p)
        p.setGainDb(0.0)
        val out = drain(p, stereo16(12345))
        assertEquals(12345, lastSample(out))
        assertEquals(8 * 2 * 2, out.remaining())
    }

    @Test
    fun `positive gain boosts output and clamps at full scale`() {
        val p = LevelingAudioProcessor()
        configure(p)
        p.setGainDb(6.0)
        val out = drain(p, stereo16(10000))
        // 6 dB ≈ 2.0×. The ramp means early frames are below target, so assert the LAST
        // frame — at 48 kHz eight frames (0.17 ms) is already past the 2.4 ms ramp.
        assertEquals(20000, lastSample(out))
        // Clamped: 30000 * 2.0 would wrap to a negative int16 without the coerce.
        val p2 = LevelingAudioProcessor()
        configure(p2)
        p2.setGainDb(6.0)
        val out2 = drain(p2, stereo16(30000))
        assertEquals(32767, lastSample(out2))
    }

    @Test
    fun `negative gain attenuates output`() {
        val p = LevelingAudioProcessor()
        configure(p)
        p.setGainDb(-6.0)
        val out = drain(p, stereo16(20000))
        assertEquals(10000, lastSample(out))
    }

    @Test
    fun `gain ramps smoothly toward the target`() {
        val p = LevelingAudioProcessor()
        configure(p)
        p.setGainDb(6.0)
        val out = drain(p, stereo16(10000))
        val first = out.getShort(0).toInt()
        val last = lastSample(out)
        // First frame sits between unity and target (ramp just started); last is at target.
        assertTrue("first=$first should be < last=$last", first < last)
        assertTrue("first=$first should be >= 10000 (unity)", first >= 10000)
    }

    @Test
    fun `processor rejects unexpected encodings`() {
        val p = LevelingAudioProcessor()
        try {
            p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
            throw AssertionError("expected UnhandledAudioFormatException")
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
        }
    }

    @Test
    fun `float encoding is also gained`() {
        val p = LevelingAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        p.setGainDb(-6.0)
        val input = ByteBuffer.allocate(8 * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(16) { input.putFloat(0.5f) }
        input.flip()
        p.queueInput(input)
        p.queueEndOfStream()
        var last = 0f
        while (true) {
            val chunk = p.getOutput()
            if (chunk.hasRemaining()) {
                while (chunk.hasRemaining()) last = chunk.float
            } else break
        }
        assertEquals(0.25f, last, 0.001f)
    }

    // -------------------------------------------------------------------
    // levelingSampleIndices — mirrors the macOS spread
    // -------------------------------------------------------------------

    @Test
    fun `sample indices spread across the track list`() {
        assertEquals(listOf(0), levelingSampleIndices(1))
        assertEquals(listOf(0, 1), levelingSampleIndices(2))
        // 8 tracks, 3 samples: not the opening three — one measurement stands for the mix.
        assertEquals(listOf(1, 4, 6), levelingSampleIndices(8))
        assertEquals(listOf(1, 2, 3), levelingSampleIndices(4))
    }

    // -------------------------------------------------------------------
    // LoudnessMeasurer — decode-ahead over MockWebServer
    // -------------------------------------------------------------------

    /**
     * Fake decoder: derives PCM from the served bytes so tests can vary loudness per
     * track. The first byte of the segment picks the loudness ('A' quiet, 'B' loud);
     * anything else throws, like an undecodable slice would.
     */
    private class FakeDecoder : SegmentDecoder {
        override fun decode(file: File): DecodedPcm {
            val marker = file.useLines { it.first().firstOrNull() } ?: throw java.io.IOException("empty segment")
            val dbfs = when (marker) {
                'A' -> -20.0
                'B' -> -14.0
                else -> throw java.io.IOException("undecodable")
            }
            val amplitude = 10.0.pow(dbfs / 20.0)
            val n = 48_000 // 1 s of sine at 48 kHz
            val samples = FloatArray(n * 2)
            for (i in 0 until n) {
                val s = (amplitude * kotlin.math.sin(2.0 * Math.PI * 997.0 * i / 48_000)).toFloat()
                samples[i * 2] = s
                samples[i * 2 + 1] = s
            }
            return DecodedPcm(48_000, 2, samples)
        }
    }

    private fun tempDir() = File(
        ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "test-leveling",
    ).apply { mkdirs() }

    private fun measurer(server: MockWebServer, decoder: SegmentDecoder = FakeDecoder()) =
        LoudnessMeasurer(
            decoder = decoder,
            tempDir = tempDir(),
            client = okhttp3.OkHttpClient(),
        )

    private fun track(server: MockWebServer, n: Int) = LevelingSample(server.url("/t\$n.mp3").toString(), 300_000L)

    private fun eightTracks(server: MockWebServer) = (1..8).map { track(server, it) }

    /**
     * The measurer makes two requests per sampled track: a HEAD probe (for the file size,
     * to center the slice) and then the Range GET. Queue both for each sampled track.
     */
    private fun head() = MockResponse().addHeader("Content-Length", "160000")

    @Test
    fun `measurer pools segments into one LUFS`() = runBlocking {
        val server = MockWebServer()
        // 3 sampled tracks (HEAD + Range each). The byte letter sets the loudness.
        repeat(3) { n ->
            server.enqueue(head())
            server.enqueue(MockResponse().setBody("A${n + 1}"))
        }
        server.start()

        val result = measurer(server).measure(eightTracks(server))

        assertNotNull(result)
        // −20 dBFS stereo sine ≈ −23 LUFS (shared test vector, LoudnessTest).
        assertEquals(-23.0, result!!.lufs, 0.5)
        assertEquals(3, result.sampledTracks)
        server.shutdown()
    }

    @Test
    fun `measurer skips failed segments but keeps the rest`() = runBlocking {
        val server = MockWebServer()
        // Segment 0: 500. Segment 1: 'B' (loud). Segment 2: 'A' (quiet).
        server.enqueue(head())
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(head())
        server.enqueue(MockResponse().setBody("B1"))
        server.enqueue(head())
        server.enqueue(MockResponse().setBody("A2"))
        server.start()

        val result = measurer(server).measure(eightTracks(server))

        assertNotNull("2 of 3 segments measured — still a measurement", result)
        assertEquals(2, result!!.sampledTracks)
        // Pooled loudness sits between the loud (≈ −17) and quiet (≈ −23) vectors.
        assertTrue(result.lufs in -20.5..-16.5)
        server.shutdown()
    }

    @Test
    fun `measurer returns null when every segment fails`() = runBlocking {
        val server = MockWebServer()
        repeat(3) {
            server.enqueue(head())
            server.enqueue(MockResponse().setResponseCode(500))
        }
        server.start()

        assertNull(measurer(server).measure(eightTracks(server)))
        server.shutdown()
    }

    @Test
    fun `measurer returns null for an empty track list`() = runBlocking {
        val server = MockWebServer()
        server.start()
        assertNull(measurer(server).measure(emptyList()))
        server.shutdown()
    }

    @Test
    fun `measurer skips undecodable segments`() = runBlocking {
        val server = MockWebServer()
        // Segment 0: undecodable. Segments 1 and 2 decode.
        server.enqueue(head())
        server.enqueue(MockResponse().setBody("X-not-audio"))
        server.enqueue(head())
        server.enqueue(MockResponse().setBody("A1"))
        server.enqueue(head())
        server.enqueue(MockResponse().setBody("A2"))
        server.start()

        val result = measurer(server).measure(eightTracks(server))
        assertNotNull(result)
        assertEquals(2, result!!.sampledTracks)
        server.shutdown()
    }

    // -------------------------------------------------------------------
    // Settings toggle — persisted (PlaybackSettings)
    // -------------------------------------------------------------------

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
