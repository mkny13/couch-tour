package dev.mike.couchtour

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
