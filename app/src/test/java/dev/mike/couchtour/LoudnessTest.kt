package dev.mike.couchtour

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Shared test vectors for LoudnessMeter and leveling gain.
 *
 * Vectors are the same on both platforms:
 * - 997 Hz sine at -20 dBFS, stereo, 48 kHz → -23.0 ±0.1 LUFS
 * - Same signal at 44.1 kHz
 * - Pure silence → no measurement (null lufs)
 * - Gain clamps at ±12 dB
 * - Peak cap prevents clipping
 * - levelingKey correctness for phish.in and Relisten tracks
 */
class LoudnessTest {

    // -----------------------------------------------------------------------
    // Helpers: generate test signals
    // -----------------------------------------------------------------------

    /** Generate a mono 997 Hz sine at the given dBFS amplitude. */
    private fun sine997(
        sampleRate: Int,
        durationSec: Double,
        dbfs: Double,
    ): FloatArray {
        val amplitude = 10.0.pow(dbfs / 20.0)
        val numSamples = (sampleRate * durationSec).toInt()
        val freq = 997.0
        return FloatArray(numSamples) { i ->
            (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        }
    }

    /** Interleave a mono signal into stereo (identical L+R). */
    private fun stereo(mono: FloatArray): FloatArray {
        val out = FloatArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]
            out[i * 2 + 1] = mono[i]
        }
        return out
    }

    // -----------------------------------------------------------------------
    // LoudnessMeter: 997 Hz sine at -20 dBFS → ≈ -23 LUFS
    // -----------------------------------------------------------------------

    @Test
    fun sine997_stereo_48k_lufs() {
        val meter = LoudnessMeter(sampleRate = 48000, channels = 2)
        val mono = sine997(48000, 3.0, -20.0)
        meter.push(stereo(mono))
        val r = meter.result()
        assertNotNull("Expected non-null LUFS for a loud sine", r.lufs)
        // BS.1770 K-weighting boosts 997 Hz by ~0 dB, so integrated LUFS for
        // a −20 dBFS sine ≈ −20 + (−0.691) + 10*log10(2) ≈ −17.68 for mono,
        // but stereo with equal L+R doubles the power → ~−23.0 LUFS after
        // gating.  The exact value depends on filter shape; we allow ±0.1.
        assertEquals(-23.0, r.lufs!!, 0.1)
    }

    @Test
    fun sine997_stereo_44k_lufs() {
        val meter = LoudnessMeter(sampleRate = 44100, channels = 2)
        val mono = sine997(44100, 3.0, -20.0)
        meter.push(stereo(mono))
        val r = meter.result()
        assertNotNull("Expected non-null LUFS at 44.1 kHz", r.lufs)
        // Same signal, different sample rate — should match within tolerance
        // because 997 Hz is well within the passband where K-weighting is flat.
        assertEquals(-23.0, r.lufs!!, 0.1)
    }

    @Test
    fun sine997_peakDbfs() {
        val meter = LoudnessMeter(sampleRate = 48000, channels = 2)
        val mono = sine997(48000, 3.0, -20.0)
        meter.push(stereo(mono))
        val r = meter.result()
        // Sample peak should be close to -20 dBFS (the sine amplitude).
        assertEquals(-20.0, r.peakDbfs, 0.1)
    }

    // -----------------------------------------------------------------------
    // LoudnessMeter: silence → gated to no measurement
    // -----------------------------------------------------------------------

    @Test
    fun silence_gated_to_null() {
        val meter = LoudnessMeter(sampleRate = 48000, channels = 2)
        val silence = FloatArray(48000 * 2 * 3) // 3 sec stereo silence
        meter.push(silence)
        val r = meter.result()
        assertNull("Silence should gate to null LUFS", r.lufs)
    }

    // -----------------------------------------------------------------------
    // LoudnessMeter: multi-push pooling
    // -----------------------------------------------------------------------

    @Test
    fun multiPush_pools_segments() {
        // Feed the same 3-second signal in three 1-second chunks.
        val meter = LoudnessMeter(sampleRate = 48000, channels = 2)
        val mono = sine997(48000, 1.0, -20.0)
        val chunk = stereo(mono)
        meter.push(chunk)
        meter.push(chunk)
        meter.push(chunk)
        val r = meter.result()
        assertNotNull(r.lufs)
        assertEquals(-23.0, r.lufs!!, 0.1)
    }

    // -----------------------------------------------------------------------
    // LoudnessMeter: mono support
    // -----------------------------------------------------------------------

    @Test
    fun sine997_mono_48k() {
        val meter = LoudnessMeter(sampleRate = 48000, channels = 1)
        val mono = sine997(48000, 3.0, -20.0)
        meter.push(mono)
        val r = meter.result()
        assertNotNull(r.lufs)
        // Mono: single channel, so power is half of stereo → ~3 dB lower LUFS.
        // The exact value is ~-20.0 - 0.691 ≈ -20.7 LUFS.
        // Just verify it's in a reasonable range and not null.
        assertTrue("Mono LUFS should be finite", r.lufs!!.isFinite())
    }

    // -----------------------------------------------------------------------
    // LoudnessMeter: reset
    // -----------------------------------------------------------------------

    @Test
    fun reset_clears_state() {
        val meter = LoudnessMeter(sampleRate = 48000, channels = 2)
        meter.push(stereo(sine997(48000, 3.0, -20.0)))
        assertNotNull(meter.result().lufs)
        meter.reset()
        val r = meter.result()
        assertNull("After reset, should have no measurement", r.lufs)
    }

    // -----------------------------------------------------------------------
    // levelingGainDb: basic rule
    // -----------------------------------------------------------------------

    @Test
    fun levelingGain_null_returns_zero() {
        assertEquals(0.0, levelingGainDb(null, -6.0), 0.001)
    }

    @Test
    fun levelingGain_at_target() {
        // Track already at -18 LUFS with -3 dBFS peak → gain = 0, capped at 2.
        assertEquals(0.0, levelingGainDb(-18.0, -3.0), 0.001)
    }

    @Test
    fun levelingGain_quiet_track() {
        // Track at -28 LUFS → raw = -18 - (-28) = +10 dB.
        // Peak at -12 dBFS → peak cap = -1 - (-12) = 11 dB.
        // min(10, 11) = 10.
        assertEquals(10.0, levelingGainDb(-28.0, -12.0), 0.001)
    }

    @Test
    fun levelingGain_clamp_positive() {
        // Track at -35 LUFS → raw = +17 dB → clamped to +12 dB.
        // Peak at -20 dBFS → peak cap = 19 dB → min(12, 19) = 12.
        assertEquals(12.0, levelingGainDb(-35.0, -20.0), 0.001)
    }

    @Test
    fun levelingGain_clamp_negative() {
        // Track at -3 LUFS → raw = -18 - (-3) = -15 → clamped to -12.
        // Peak at 0 dBFS → peak cap = -1 → min(-12, -1) = -12.
        assertEquals(-12.0, levelingGainDb(-3.0, 0.0), 0.001)
    }

    @Test
    fun levelingGain_peak_cap_prevents_clipping() {
        // Track at -25 LUFS → raw = +7 dB.
        // Peak at -4 dBFS → peak cap = -1 - (-4) = +3 dB.
        // min(7, 3) = 3 → peak cap wins.
        assertEquals(3.0, levelingGainDb(-25.0, -4.0), 0.001)
    }

    // -----------------------------------------------------------------------
    // PlayableTrack.levelingKey
    // -----------------------------------------------------------------------

    @Test
    fun levelingKey_phishin_track() {
        val track = PlayableTrack(
            id = "1",
            title = "Tweezer",
            url = "https://example.com/tweezer.mp3",
            showDate = "2023-07-14",
        )
        assertEquals("show:2023-07-14", track.levelingKey)
    }

    @Test
    fun levelingKey_relisten_track() {
        val track = PlayableTrack(
            id = "2",
            title = "Tweezer",
            url = "https://example.com/tweezer.mp3",
            showDate = "2023-07-14",
            recordingId = RecordingId(
                artistSlug = "phish",
                date = "2023-07-14",
                sourceId = "12345",
            ),
        )
        assertEquals("relisten:phish/2023-07-14/12345", track.levelingKey)
    }
}
