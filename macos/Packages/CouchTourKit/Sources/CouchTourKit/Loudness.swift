import Foundation

// MARK: - BS.1770-4 Integrated Loudness Meter
//
// Pure math — no AVFoundation, no I/O. Measurement and gain-application
// pipelines land in #267/#268, not here.
//
// Algorithm outline (ITU-R BS.1770-4, simplified for stereo/mono):
//
// 1. K-weighting pre-filter: two cascaded biquads (high-shelf + high-pass)
//    whose coefficients depend on sample rate.
// 2. Mean-square accumulation over 400 ms blocks with 75 % overlap
//    (step = 100 ms).
// 3. Absolute gate at −70 LUFS: blocks below this are discarded.
// 4. Relative gate at −10 LU below the ungated mean: blocks below
//    this are discarded; the mean of the survivors is the integrated loudness.

// MARK: - Biquad filter (transposed direct-form II)

/// Second-order IIR (biquad) filter, transposed direct-form II.
/// Holds per-channel state so one instance handles one channel.
final class Biquad {
    private let b0, b1, b2, a1, a2: Double  // a1/a2 are negated
    private var z1 = 0.0
    private var z2 = 0.0

    init(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        self.b0 = b0; self.b1 = b1; self.b2 = b2
        self.a1 = a1; self.a2 = a2
    }

    func process(_ x: Double) -> Double {
        let y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    func reset() { z1 = 0; z2 = 0 }
}

// MARK: - K-weighting coefficient factory

/// Pre-computed biquad coefficient sets for the two BS.1770 K-weighting stages.
/// Stage 1: high-shelf boost (≈ +4 dB above 1.5 kHz).
/// Stage 2: high-pass (RLB weighting, −3 dB ≈ 38 Hz).
///
/// Coefficients are published for 48 kHz in the standard; for other rates we
/// use the bilinear-transform cookbook formulas so the filter shape tracks
/// correctly.
enum KWeighting {
    /// Returns (stage1, stage2) coefficient arrays `[b0, b1, b2, a1, a2]`.
    static func coefficients(sampleRate: Int) -> ([Double], [Double]) {
        let fs = Double(sampleRate)
        return (highShelfCoeffs(fs: fs), highPassCoeffs(fs: fs))
    }

    private static func highShelfCoeffs(fs: Double) -> [Double] {
        let db = 3.999843853973347
        let f0 = 1681.974450955533
        let q  = 0.7071752369554196
        let k  = tan(.pi * f0 / fs)
        let vh = pow(10.0, db / 20.0)
        let vb = pow(vh, 0.4996667741545416)
        let a0 = 1.0 + k / q + k * k
        let b0 = (vh + vb * k / q + k * k) / a0
        let b1 = 2.0 * (k * k - vh) / a0
        let b2 = (vh - vb * k / q + k * k) / a0
        let a1 = 2.0 * (k * k - 1.0) / a0
        let a2 = (1.0 - k / q + k * k) / a0
        return [b0, b1, b2, a1, a2]
    }

    private static func highPassCoeffs(fs: Double) -> [Double] {
        let f0 = 38.13547087602444
        let q  = 0.5003270373238773
        let k  = tan(.pi * f0 / fs)
        let a0 = 1.0 + k / q + k * k
        let b0 = 1.0 / a0
        let b1 = -2.0 / a0
        let b2 = 1.0 / a0
        let a1 = 2.0 * (k * k - 1.0) / a0
        let a2 = (1.0 - k / q + k * k) / a0
        return [b0, b1, b2, a1, a2]
    }
}

// MARK: - LoudnessMeter

/// BS.1770-4 integrated loudness meter.
///
/// Accepts interleaved float PCM via ``push(_:offset:frames:)``
/// (mono or stereo, any sample rate 22 050 – 96 000 Hz).
/// Multiple discontinuous segments can be fed; call ``push`` as many
/// times as needed — blocks pool into one gated measurement.
///
/// After all audio has been fed, call ``result()`` to obtain the
/// integrated LUFS and sample-peak dBFS.
///
/// Thread-safety: **not thread-safe** — call from one thread at a time.
public final class LoudnessMeter {
    public let sampleRate: Int
    public let channels: Int

    // K-weighting filters: [channel][stage]
    private let filters: [[Biquad]]

    // 400 ms block, 100 ms step (75 % overlap).
    private let blockSize: Int
    private let stepSize: Int

    private var channelBlockBuf: [[Double]]
    private var blockPos = 0

    private var blockPowers: [Double] = []
    private var peakLinear: Double = 0

    public init(sampleRate: Int, channels: Int) {
        precondition((22050...96000).contains(sampleRate), "Sample rate \(sampleRate) out of range 22050..96000")
        precondition(channels == 1 || channels == 2, "Channel count \(channels) not supported (1 or 2)")
        self.sampleRate = sampleRate
        self.channels = channels

        let (s1, s2) = KWeighting.coefficients(sampleRate: sampleRate)
        filters = (0..<channels).map { _ in
            [Biquad(b0: s1[0], b1: s1[1], b2: s1[2], a1: s1[3], a2: s1[4]),
             Biquad(b0: s2[0], b1: s2[1], b2: s2[2], a1: s2[3], a2: s2[4])]
        }

        let bs = Int(0.4 * Double(sampleRate))
        blockSize = bs
        stepSize  = Int(0.1 * Double(sampleRate))
        channelBlockBuf = (0..<channels).map { _ in [Double](repeating: 0, count: bs) }
    }

    /// Feed interleaved float PCM samples.
    /// - Parameters:
    ///   - samples: interleaved array `[L0, R0, L1, R1, …]` for stereo,
    ///              or `[S0, S1, …]` for mono.
    ///   - offset:  starting index in `samples`.
    ///   - frames:  number of frames (not samples) to consume.
    public func push(_ samples: [Float], offset: Int = 0, frames: Int? = nil) {
        let frameCount = frames ?? (samples.count - offset) / channels
        var idx = offset
        for _ in 0..<frameCount {
            for ch in 0..<channels {
                let raw = Double(samples[idx])
                idx += 1

                // Track sample peak (pre-filter, absolute).
                let absVal = abs(raw)
                if absVal > peakLinear { peakLinear = absVal }

                // K-weight.
                var s = raw
                for stage in filters[ch] { s = stage.process(s) }

                channelBlockBuf[ch][blockPos] = s
            }
            blockPos += 1

            if blockPos == blockSize {
                emitBlock()
                slideBlock()
            }
        }
    }

    private func emitBlock() {
        // Mean-square per channel, averaged across channels (dividing by
        // channels keeps mono/stereo readings consistent for the same source).
        var power = 0.0
        for ch in 0..<channels {
            var sum = 0.0
            for i in 0..<blockSize {
                let v = channelBlockBuf[ch][i]
                sum += v * v
            }
            power += sum / Double(blockSize)
        }
        blockPowers.append(power / Double(channels))
    }

    private func slideBlock() {
        let keep = blockSize - stepSize
        for ch in 0..<channels {
            // Shift left by stepSize
            channelBlockBuf[ch].withUnsafeMutableBufferPointer { buf in
                buf.baseAddress!.advanced(by: 0)
                    .update(from: buf.baseAddress!.advanced(by: stepSize), count: keep)
            }
        }
        blockPos = keep
    }

    /// Measurement result.
    public struct Result {
        /// Integrated loudness in LUFS, or `nil` if no block exceeded
        /// the absolute gate (e.g. silence).
        public let lufs: Double?
        /// Sample peak in dBFS.
        public let peakDbfs: Double
    }

    /// Compute the BS.1770-4 gated, integrated loudness.
    public func result() -> Result {
        let peakDb = peakLinear > 0 ? 20 * log10(peakLinear) : -200.0

        guard !blockPowers.isEmpty else { return Result(lufs: nil, peakDbfs: peakDb) }

        // Absolute gate: −70 LUFS
        let absGateThreshold = pow(10.0, -70.0 / 10.0)
        let aboveAbsGate = blockPowers.filter { $0 > absGateThreshold }
        guard !aboveAbsGate.isEmpty else { return Result(lufs: nil, peakDbfs: peakDb) }

        let ungatedMean = aboveAbsGate.reduce(0, +) / Double(aboveAbsGate.count)

        // Relative gate: −10 LU below ungated mean
        let relGateThreshold = ungatedMean * pow(10.0, -10.0 / 10.0)
        let aboveRelGate = blockPowers.filter { $0 > relGateThreshold }
        guard !aboveRelGate.isEmpty else { return Result(lufs: nil, peakDbfs: peakDb) }

        let gatedMean = aboveRelGate.reduce(0, +) / Double(aboveRelGate.count)
        let lufs = -0.691 + 10.0 * log10(gatedMean)

        return Result(lufs: lufs, peakDbfs: peakDb)
    }

    /// Reset all state for reuse with a different track.
    public func reset() {
        blockPowers.removeAll()
        blockPos = 0
        peakLinear = 0
        for ch in 0..<channels {
            channelBlockBuf[ch] = [Double](repeating: 0, count: blockSize)
            for stage in filters[ch] { stage.reset() }
        }
    }
}

// MARK: - Leveling gain rule

/// Compute the playback gain adjustment to bring a track to −18 LUFS,
/// clamped to ±12 dB and peak-capped so the output won't clip.
///
/// - Parameters:
///   - lufs:     measured integrated LUFS, or `nil` if unmeasured.
///   - peakDbfs: sample-peak in dBFS.
/// - Returns: gain in dB to apply to playback (`0.0` if `lufs` is `nil`).
public func levelingGainDb(lufs: Double?, peakDbfs: Double) -> Double {
    guard let lufs = lufs else { return 0 }
    // Target −18 LUFS; clamp swing to ±12 dB.
    let raw = min(max(-18.0 - lufs, -12), 12)
    // Cap so peak + gain ≤ −1 dBFS (1 dB headroom).
    let peakCap = -1.0 - peakDbfs
    return min(raw, peakCap)
}

// MARK: - PlayableTrack.levelingKey

extension PlayableTrack {
    /// Leveling key: identifies the recording for loudness-cache lookup.
    ///
    /// - phish.in tracks:  `"show:<showDate>"`
    /// - Relisten tracks:  `"relisten:<artistSlug>/<date>/<sourceId>"`
    ///
    /// Byte-identical to the existing queue-key grammar in ``QueueKey``.
    /// Returns `nil` when there isn't enough identity to form a key (e.g. a
    /// track with no show date and no recording id).
    public var levelingKey: String? {
        if let rec = recordingId {
            return recordingQueueKey(rec.artistSlug, rec.date, rec.sourceId)
        }
        return showDate.map { showQueueKey($0) }
    }
}
