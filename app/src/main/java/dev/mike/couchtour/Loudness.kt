package dev.mike.couchtour

import kotlin.math.*

/**
 * BS.1770-4 integrated loudness meter and volume-leveling gain rule.
 *
 * Pure math — no ExoPlayer, no I/O. Measurement and gain-application
 * pipelines land in #267/#268, not here.
 *
 * ## Algorithm outline (ITU-R BS.1770-4, simplified for stereo/mono)
 *
 * 1. **K-weighting pre-filter**: two cascaded biquads (high-shelf + high-pass)
 *    whose coefficients depend on sample rate.
 * 2. **Mean-square accumulation** over 400 ms blocks with 75 % overlap
 *    (i.e. step = 100 ms).
 * 3. **Absolute gate** at -70 LUFS: blocks below this are discarded.
 * 4. **Relative gate** at -10 LU below the ungated mean: blocks below
 *    this are discarded; the mean of the survivors is the integrated loudness.
 */

// ---------------------------------------------------------------------------
// Biquad filter (transposed direct-form II)
// ---------------------------------------------------------------------------

/**
 * Second-order IIR (biquad) filter, transposed direct-form II.
 * Holds per-channel state so one instance handles one channel.
 */
internal class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double, // negated (sign already flipped)
    private val a2: Double, // negated
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

// ---------------------------------------------------------------------------
// K-weighting coefficient factory
// ---------------------------------------------------------------------------

/**
 * Pre-computed biquad coefficient sets for the two BS.1770 K-weighting stages.
 * Stage 1: high-shelf boost (≈ +4 dB above 1.5 kHz).
 * Stage 2: high-pass (RLB weighting, −3 dB ≈ 38 Hz).
 *
 * Coefficients are published for 48 kHz in the standard; for other rates we
 * use the bilinear-transform cookbook formulas so the filter shape tracks
 * correctly.
 */
internal object KWeighting {

    /** Returns (stage1, stage2) biquad pairs for the given sample rate. */
    fun coefficients(sampleRate: Int): Pair<DoubleArray, DoubleArray> {
        val fs = sampleRate.toDouble()

        // --- Stage 1: high shelf ---
        // Peaking high-shelf design matching the ITU reference at 48 kHz,
        // bilinear-transformed for arbitrary rates.
        val stage1 = highShelfCoeffs(fs)

        // --- Stage 2: high-pass (RLB) ---
        val stage2 = highPassCoeffs(fs)

        return stage1 to stage2
    }

    private fun highShelfCoeffs(fs: Double): DoubleArray {
        // Design parameters derived from the ITU 48 kHz reference implementation.
        val db = 3.999843853973347
        val f0 = 1681.974450955533
        val q = 0.7071752369554196
        val k = tan(PI * f0 / fs)
        val vh = 10.0.pow(db / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        val b0 = (vh + vb * k / q + k * k) / a0
        val b1 = 2.0 * (k * k - vh) / a0
        val b2 = (vh - vb * k / q + k * k) / a0
        val a1 = 2.0 * (k * k - 1.0) / a0
        val a2 = (1.0 - k / q + k * k) / a0
        return doubleArrayOf(b0, b1, b2, a1, a2)
    }

    private fun highPassCoeffs(fs: Double): DoubleArray {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / fs)
        val a0 = 1.0 + k / q + k * k
        val b0 = 1.0 / a0
        val b1 = -2.0 / a0
        val b2 = 1.0 / a0
        val a1 = 2.0 * (k * k - 1.0) / a0
        val a2 = (1.0 - k / q + k * k) / a0
        return doubleArrayOf(b0, b1, b2, a1, a2)
    }
}

// ---------------------------------------------------------------------------
// LoudnessMeter
// ---------------------------------------------------------------------------

/**
 * BS.1770-4 integrated loudness meter.
 *
 * Accepts interleaved float PCM via [push] (mono or stereo, any sample rate
 * 22 050 – 96 000 Hz). Multiple discontinuous segments can be fed; call
 * [push] as many times as needed — blocks pool into one gated measurement.
 *
 * After all audio has been fed, call [result] to obtain the integrated LUFS
 * and sample-peak dBFS.
 *
 * Thread-safety: **not thread-safe** — call from one thread at a time.
 */
class LoudnessMeter(
    private val sampleRate: Int,
    private val channels: Int,
) {
    init {
        require(sampleRate in 22050..96000) { "Sample rate $sampleRate out of range 22050..96000" }
        require(channels in 1..2) { "Channel count $channels not supported (1 or 2)" }
    }

    // K-weighting filters, one per channel, two stages each.
    private val filters: Array<Array<Biquad>>

    init {
        val (s1, s2) = KWeighting.coefficients(sampleRate)
        filters = Array(channels) { arrayOf(
            Biquad(s1[0], s1[1], s1[2], s1[3], s1[4]),
            Biquad(s2[0], s2[1], s2[2], s2[3], s2[4]),
        ) }
    }

    // 400 ms block size and 100 ms step (75 % overlap).
    private val blockSize = (0.4 * sampleRate).toInt()
    private val stepSize = (0.1 * sampleRate).toInt()

    // Per-channel ring buffer for the current 400 ms window's mean-square sum.
    // We accumulate squared K-weighted samples and track the count so we can
    // slide the window in 100 ms steps.
    private val channelSquaredSums = DoubleArray(channels)
    private val channelBlockBuf = Array(channels) { DoubleArray(blockSize) }
    private var blockPos = 0 // how many samples written into the current block

    // Accumulated per-block loudness values (linear power).
    private val blockPowers = mutableListOf<Double>()

    // Sample peak (absolute, linear).
    private var peakLinear = 0.0

    /**
     * Feed interleaved float PCM samples.
     * @param samples interleaved array: [L0, R0, L1, R1, …] for stereo,
     *                or [S0, S1, …] for mono.
     * @param offset  starting index in [samples].
     * @param frames  number of frames (not samples) to consume.
     */
    fun push(samples: FloatArray, offset: Int = 0, frames: Int = (samples.size - offset) / channels) {
        var idx = offset
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val raw = samples[idx++].toDouble()

                // Track sample peak (pre-filter, absolute).
                val absVal = abs(raw)
                if (absVal > peakLinear) peakLinear = absVal

                // K-weight.
                var s = raw
                for (stage in filters[ch]) s = stage.process(s)

                // Store in block buffer.
                channelBlockBuf[ch][blockPos] = s
            }
            blockPos++

            // When we have a full 400 ms block, compute its mean-square per channel,
            // then slide forward by 100 ms.
            if (blockPos == blockSize) {
                emitBlock()
                slideBlock()
            }
        }
    }

    private fun emitBlock() {
        // Mean-square per channel, averaged across channels (G_ch = 1.0 for
        // L/R; dividing by channels keeps mono/stereo readings consistent for
        // the same source material).
        var power = 0.0
        for (ch in 0 until channels) {
            var sum = 0.0
            for (i in 0 until blockSize) {
                val v = channelBlockBuf[ch][i]
                sum += v * v
            }
            power += sum / blockSize
        }
        blockPowers.add(power / channels)
    }

    private fun slideBlock() {
        // Shift each channel buffer left by stepSize, keeping the last (blockSize - stepSize) samples.
        val keep = blockSize - stepSize
        for (ch in 0 until channels) {
            System.arraycopy(channelBlockBuf[ch], stepSize, channelBlockBuf[ch], 0, keep)
        }
        blockPos = keep
    }

    /**
     * Measurement result: integrated LUFS and sample-peak dBFS.
     * [lufs] is null when no block exceeds the absolute gate (e.g. silence).
     */
    data class Result(val lufs: Double?, val peakDbfs: Double)

    /**
     * Compute the BS.1770-4 gated, integrated loudness.
     * May be called multiple times (idempotent on the same data).
     */
    fun result(): Result {
        val peakDb = if (peakLinear > 0.0) 20.0 * log10(peakLinear) else -200.0

        if (blockPowers.isEmpty()) return Result(lufs = null, peakDbfs = peakDb)

        // --- Absolute gate: -70 LUFS ---
        val absGateThreshold = 10.0.pow(-70.0 / 10.0) // linear power
        val aboveAbsGate = blockPowers.filter { it > absGateThreshold }
        if (aboveAbsGate.isEmpty()) return Result(lufs = null, peakDbfs = peakDb)

        // Ungated mean (of blocks above absolute gate).
        val ungatedMean = aboveAbsGate.average()

        // --- Relative gate: -10 LU below ungated mean ---
        val relGateThreshold = ungatedMean * 10.0.pow(-10.0 / 10.0) // = ungatedMean / 10
        val aboveRelGate = blockPowers.filter { it > relGateThreshold }
        if (aboveRelGate.isEmpty()) return Result(lufs = null, peakDbfs = peakDb)

        val gatedMean = aboveRelGate.average()
        val lufs = -0.691 + 10.0 * log10(gatedMean)

        return Result(lufs = lufs, peakDbfs = peakDb)
    }

    /**
     * Reset all state, allowing re-use for a different track without
     * allocating a new meter.
     */
    fun reset() {
        blockPowers.clear()
        blockPos = 0
        peakLinear = 0.0
        for (ch in 0 until channels) {
            channelSquaredSums[ch] = 0.0
            channelBlockBuf[ch].fill(0.0)
            for (stage in filters[ch]) stage.reset()
        }
    }
}

// ---------------------------------------------------------------------------
// Leveling gain rule
// ---------------------------------------------------------------------------

/**
 * Compute the playback gain adjustment to bring a track to -18 LUFS,
 * clamped to ±12 dB and peak-capped so the output won't clip.
 *
 * @param lufs     measured integrated LUFS, or null if unmeasured.
 * @param peakDbfs sample-peak in dBFS.
 * @return gain in dB to apply to playback (0.0 if [lufs] is null).
 */
fun levelingGainDb(lufs: Double?, peakDbfs: Double): Double {
    if (lufs == null) return 0.0
    // Target -18 LUFS; clamp swing to ±12 dB.
    val raw = (-18.0 - lufs).coerceIn(-12.0, 12.0)
    // Cap so peak + gain ≤ -1 dBFS (1 dB headroom).
    val peakCap = -1.0 - peakDbfs
    return minOf(raw, peakCap)
}

// ---------------------------------------------------------------------------
// PlayableTrack.levelingKey
// ---------------------------------------------------------------------------

/**
 * Leveling key: identifies the recording for loudness-cache lookup.
 *
 * - phish.in tracks:   `"show:<showDate>"`
 * - Relisten tracks:   `"relisten:<artistSlug>/<showDate>/<sourceId>"`
 *
 * Byte-identical to the existing queue-key grammar in [Queue.kt].
 */
val Catalog.PlayableTrack.levelingKey: String
    get() = recordingId?.let { recordingQueueKey(it) } ?: showQueueKey(showDate)
