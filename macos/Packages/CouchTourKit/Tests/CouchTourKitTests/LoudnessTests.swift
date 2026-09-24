import XCTest
@testable import CouchTourKit

/// Shared test vectors for LoudnessMeter and leveling gain.
///
/// Vectors are the same on both platforms:
/// - 997 Hz sine at -20 dBFS, stereo, 48 kHz → -23.0 ±0.1 LUFS
/// - Same signal at 44.1 kHz
/// - Pure silence → no measurement (nil lufs)
/// - Gain clamps at ±12 dB
/// - Peak cap prevents clipping
/// - levelingKey correctness for phish.in and Relisten tracks
final class LoudnessTests: XCTestCase {

    // MARK: - Helpers

    /// Generate a mono 997 Hz sine at the given dBFS amplitude.
    private func sine997(sampleRate: Int, durationSec: Double, dbfs: Double) -> [Float] {
        let amplitude = pow(10.0, dbfs / 20.0)
        let count = Int(Double(sampleRate) * durationSec)
        let freq = 997.0
        return (0..<count).map { i in
            Float(amplitude * sin(2.0 * .pi * freq * Double(i) / Double(sampleRate)))
        }
    }

    /// Interleave a mono signal into stereo (identical L+R).
    private func stereo(_ mono: [Float]) -> [Float] {
        var out = [Float](repeating: 0, count: mono.count * 2)
        for i in mono.indices {
            out[i * 2]     = mono[i]
            out[i * 2 + 1] = mono[i]
        }
        return out
    }

    // MARK: - LoudnessMeter: 997 Hz sine at -20 dBFS → ≈ -23 LUFS

    func testSine997Stereo48kLufs() {
        let meter = LoudnessMeter(sampleRate: 48000, channels: 2)
        let mono = sine997(sampleRate: 48000, durationSec: 3, dbfs: -20)
        meter.push(stereo(mono))
        let r = meter.result()
        XCTAssertNotNil(r.lufs, "Expected non-nil LUFS for a loud sine")
        XCTAssertEqual(r.lufs!, -23.0, accuracy: 0.1)
    }

    func testSine997Stereo44kLufs() {
        let meter = LoudnessMeter(sampleRate: 44100, channels: 2)
        let mono = sine997(sampleRate: 44100, durationSec: 3, dbfs: -20)
        meter.push(stereo(mono))
        let r = meter.result()
        XCTAssertNotNil(r.lufs, "Expected non-nil LUFS at 44.1 kHz")
        XCTAssertEqual(r.lufs!, -23.0, accuracy: 0.1)
    }

    func testSine997PeakDbfs() {
        let meter = LoudnessMeter(sampleRate: 48000, channels: 2)
        let mono = sine997(sampleRate: 48000, durationSec: 3, dbfs: -20)
        meter.push(stereo(mono))
        let r = meter.result()
        XCTAssertEqual(r.peakDbfs, -20.0, accuracy: 0.1)
    }

    func testFullScaleSineExercisesMultipleBlockSlidesAndPinsLUFS() {
        // A full-scale (0 dBFS) 997 Hz sine fed for 2 seconds.
        // At 48 kHz, 2s = 96,000 frames. Initial block is 19,200 frames (0.4s).
        // Each slide advances by 4,800 frames (0.1s), triggering 16 slideBlock() calls.
        // Under ITU-R BS.1770-4, a full-scale stereo sine at 997 Hz integrates to -3.0 ± 0.1 LUFS
        // and 0.0 dBFS sample peak. If slideBlock's overlapping memory move corrupts samples or fails
        // to advance the buffer, subsequent blocks accumulate corrupted power and the LUFS diverges.
        let meter = LoudnessMeter(sampleRate: 48000, channels: 2)
        let mono = sine997(sampleRate: 48000, durationSec: 2, dbfs: 0)
        meter.push(stereo(mono))
        let r = meter.result()
        XCTAssertNotNil(r.lufs, "Expected non-nil LUFS for full-scale sine")
        XCTAssertEqual(r.lufs!, -3.0, accuracy: 0.1)
        XCTAssertEqual(r.peakDbfs, 0.0, accuracy: 0.1)
    }

    // MARK: - Silence → gated to no measurement

    func testSilenceGatedToNil() {
        let meter = LoudnessMeter(sampleRate: 48000, channels: 2)
        let silence = [Float](repeating: 0, count: 48000 * 2 * 3)
        meter.push(silence)
        let r = meter.result()
        XCTAssertNil(r.lufs, "Silence should gate to nil LUFS")
    }

    // MARK: - Multi-push pooling

    func testMultiPushPoolsSegments() {
        let meter = LoudnessMeter(sampleRate: 48000, channels: 2)
        let mono = sine997(sampleRate: 48000, durationSec: 1, dbfs: -20)
        let chunk = stereo(mono)
        meter.push(chunk)
        meter.push(chunk)
        meter.push(chunk)
        let r = meter.result()
        XCTAssertNotNil(r.lufs)
        XCTAssertEqual(r.lufs!, -23.0, accuracy: 0.1)
    }

    // MARK: - Mono support

    func testSine997Mono48k() {
        let meter = LoudnessMeter(sampleRate: 48000, channels: 1)
        let mono = sine997(sampleRate: 48000, durationSec: 3, dbfs: -20)
        meter.push(mono)
        let r = meter.result()
        XCTAssertNotNil(r.lufs)
        XCTAssertTrue(r.lufs!.isFinite, "Mono LUFS should be finite")
    }

    // MARK: - Reset

    func testResetClearsState() {
        let meter = LoudnessMeter(sampleRate: 48000, channels: 2)
        meter.push(stereo(sine997(sampleRate: 48000, durationSec: 3, dbfs: -20)))
        XCTAssertNotNil(meter.result().lufs)
        meter.reset()
        XCTAssertNil(meter.result().lufs, "After reset, should have no measurement")
    }

    // MARK: - levelingGainDb

    func testLevelingGainNilReturnsZero() {
        XCTAssertEqual(levelingGainDb(lufs: nil, peakDbfs: -6), 0, accuracy: 0.001)
    }

    func testLevelingGainAtTarget() {
        XCTAssertEqual(levelingGainDb(lufs: -18, peakDbfs: -3), 0, accuracy: 0.001)
    }

    func testLevelingGainQuietTrack() {
        // -28 LUFS → raw = +10, peak cap = 11 → 10
        XCTAssertEqual(levelingGainDb(lufs: -28, peakDbfs: -12), 10, accuracy: 0.001)
    }

    func testLevelingGainClampPositive() {
        // -35 LUFS → raw = +17 → clamped to +12, peak cap = 19 → 12
        XCTAssertEqual(levelingGainDb(lufs: -35, peakDbfs: -20), 12, accuracy: 0.001)
    }

    func testLevelingGainClampNegative() {
        // -3 LUFS → raw = -15 → clamped to -12, peak cap = -1 → -12
        XCTAssertEqual(levelingGainDb(lufs: -3, peakDbfs: 0), -12, accuracy: 0.001)
    }

    func testLevelingGainPeakCapPreventsClipping() {
        // -25 LUFS → raw = +7, peak at -4 → cap = +3 → 3
        XCTAssertEqual(levelingGainDb(lufs: -25, peakDbfs: -4), 3, accuracy: 0.001)
    }

    // MARK: - PlayableTrack.levelingKey

    func testLevelingKeyPhishinTrack() {
        let track = PlayableTrack(
            id: "1", title: "Tweezer",
            url: "https://example.com/tweezer.mp3",
            showDate: "2023-07-14"
        )
        XCTAssertEqual(track.levelingKey, "show:2023-07-14")
    }

    func testLevelingKeyRelistenTrack() {
        let track = PlayableTrack(
            id: "2", title: "Tweezer",
            url: "https://example.com/tweezer.mp3",
            showDate: "2023-07-14",
            recordingId: RecordingId(
                artistSlug: "phish", date: "2023-07-14",
                sourceId: "12345"
            )
        )
        XCTAssertEqual(track.levelingKey, "relisten:phish/2023-07-14/12345")
    }
}
