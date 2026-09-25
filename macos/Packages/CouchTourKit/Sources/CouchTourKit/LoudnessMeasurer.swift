import AVFoundation
import Foundation

// MARK: - Decode-ahead loudness measurement (#268)
//
// The macOS half of the volume-leveling pipeline (#18 strategy (a)): measure a *source*
// (one phish.in show mix, or one Relisten tape) in the background by pulling a 30 s slice
// from the middle of up to three of its MP3 tracks over HTTP Range requests — never FLAC:
// same master, much bigger — decoding it with `AVAudioFile` into float PCM, and feeding the
// pooled segments through the BS.1770-4 meter (#265). The result lands in the
// `source_loudness` cache (#266) under the track's `levelingKey`, and the temp files are
// deleted before this actor hands control back.
//
// The gain itself is applied by the app target's `MTAudioProcessingTap` in `Player.swift`;
// this file deliberately knows nothing about AVPlayer.

// MARK: - Decoded PCM

/// One decoded audio segment, handed to the meter: interleaved float PCM.
public struct DecodedPCM: Equatable, Sendable {
    public let sampleRate: Int
    /// 1 (mono) or 2 (stereo) — the meter's supported channel counts.
    public let channels: Int
    /// Interleaved samples: `[L0, R0, L1, R1, …]` for stereo, `[S0, S1, …]` for mono.
    public let samples: [Float]

    public init(sampleRate: Int, channels: Int, samples: [Float]) {
        self.sampleRate = sampleRate
        self.channels = channels
        self.samples = samples
    }
}

// MARK: - Segment decoder seam

/// Turns a written-to-disk audio segment into float PCM for the meter.
///
/// Injectable so tests can measure against fixture PCM without an MP3 on disk or
/// `AVAudioFile` in the loop at all — the measurer still exercises its own temp-file
/// write/cleanup around the injected decoder.
public protocol SegmentDecoder: Sendable {
    func decode(fileURL: URL) throws -> DecodedPCM
}

/// The real decoder: reads whatever the temp file turned out to be (an MP3 slice) via
/// `AVAudioFile` and deinterleaves it into the meter's interleaved layout.
public struct AVAudioFileSegmentDecoder: SegmentDecoder {
    public init() {}

    public func decode(fileURL: URL) throws -> DecodedPCM {
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: fileURL)
        } catch {
            throw LoudnessMeasurerError.undecodable(fileURL)
        }
        let format = file.processingFormat
        let frameCount = Int(file.length)
        guard frameCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)),
              let channelData = buffer.floatChannelData else {
            throw LoudnessMeasurerError.undecodable(fileURL)
        }
        try file.read(into: buffer, frameCount: AVAudioFrameCount(frameCount))
        guard buffer.frameLength > 0 else {
            throw LoudnessMeasurerError.undecodable(fileURL)
        }

        let channels = Int(format.channelCount)
        let frames = Int(buffer.frameLength)
        var interleaved = [Float](repeating: 0, count: frames * channels)
        for frame in 0..<frames {
            for channel in 0..<channels {
                interleaved[frame * channels + channel] = channelData[channel][frame]
            }
        }
        return DecodedPCM(sampleRate: Int(format.sampleRate), channels: channels, samples: interleaved)
    }
}

// MARK: - Errors

public enum LoudnessMeasurerError: Error, Equatable {
    /// The fetched segment was not audio the decoder could open.
    case undecodable(URL)
    /// The response was not a usable 2xx / 206 body.
    case badResponse(URL)
    /// No usable MP3 URL to fetch from.
    case unplayableTrack
}

// MARK: - Cache seam

/// The `source_loudness` cache (#266), behind a protocol so tests can use an in-memory
/// stand-in. `ProgressStore` conforms below; `current` is the version-gated read.
public protocol SourceLoudnessCache: AnyObject {
    func current(key: String) throws -> SourceLoudness?
    func save(_ loudness: SourceLoudness) throws
    func clearAll() throws
}

extension ProgressStore: SourceLoudnessCache {
    public func current(key: String) throws -> SourceLoudness? {
        try currentSourceLoudness(key: key)
    }

    public func save(_ loudness: SourceLoudness) throws {
        try saveSourceLoudness(loudness)
    }

    public func clearAll() throws {
        try clearAllSourceLoudness()
    }
}

// MARK: - Track sampling

/// Which track indices get measured for one source: up to `max`, spread evenly across the
/// track list so one measurement stands for the whole mix rather than its opening three
/// tracks (set 1 of a show is typically tighter than set 2).
public func levelingSampleIndices(total: Int, max: Int = 3) -> [Int] {
    guard total > 0, max > 0 else { return [] }
    let count = Swift.min(max, total)
    if count == total { return Array(0..<total) }
    return (0..<count).map { Swift.min(total - 1, (($0 + 1) * total) / (count + 1)) }
}

// MARK: - LoudnessMeasurer

/// Background measurement of one source for volume leveling (#268).
///
/// Usage: `await measurer.measure(key: track.levelingKey!, tracks: queueTracks)` —
/// returns the cached row when there is one, kicks off measurement when there isn't, and
/// coalesces concurrent callers onto one in-flight measurement per key. A key that fails
/// every segment returns `nil` (playback stays at 0 dB — unknown loudness means no gain,
/// per the #18 gain rule) and nothing is written to the cache.
///
/// Failure and cancellation mirror the Android design (#267): a segment that fails to
/// fetch or decode is skipped, and only the segments that measured feed the pooled result;
/// `Task` cancellation between segments stops the whole measurement without caching.
public actor LoudnessMeasurer {
    /// Seconds of audio pulled from the middle of each sampled track.
    public static let segmentSeconds: Double = 30
    /// Most tracks sampled into one source measurement.
    public static let maxSampledTracks = 3
    /// Byte-rate assumption when a HEAD probe yields no Content-Length — roughly a
    /// 128 kbps MP3. Only affects where the Range starts, and only when the server
    /// refuses to tell us the file size; the meter is rate-agnostic.
    static let assumedBytesPerSecond = 16_000

    private let session: URLSession
    private let decoder: SegmentDecoder
    private let cache: SourceLoudnessCache
    /// Temp segments are written here — under Caches, never `~/Documents` — and deleted
    /// after each decode, even when the decode throws.
    private let tempDirectory: URL
    private var inFlight: [String: Task<SourceLoudness?, Never>] = [:]

    public init(
        session: URLSession = .shared,
        decoder: SegmentDecoder = AVAudioFileSegmentDecoder(),
        cache: SourceLoudnessCache
    ) {
        self.session = session
        self.decoder = decoder
        self.cache = cache
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("CouchTourKit-leveling", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.tempDirectory = dir
    }

    /// The cached measurement for `key`, if a current one exists. Read-only seam for the
    /// player, which checks the cache before deciding whether to schedule measurement.
    public func cachedResult(key: String) -> SourceLoudness? {
        (try? cache.current(key: key)) ?? nil
    }

    /// The temp files still on disk, for diagnostics and tests.
    public func tempFiles() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: tempDirectory, includingPropertiesForKeys: nil)) ?? []
    }

    /// Clears all cached loudness measurements from the database and cancels any
    /// in-flight background measurement (#269).
    public func clearAll() throws {
        for (_, task) in inFlight {
            task.cancel()
        }
        inFlight.removeAll()
        try cache.clearAll()
    }

    /// Measure one source (or hand back its cached measurement).
    ///
    /// Concurrent calls for the same key coalesce: the first caller creates the in-flight
    /// task, later callers await the same task, and one fetch pass serves everyone.
    public func measure(key: String, tracks: [PlayableTrack]) async -> SourceLoudness? {
        // A caller that was already cancelled (queue torn down, scene dismissed) measures
        // nothing — measurement only runs for a live listener.
        if Task.isCancelled { return nil }
        if let cached = cachedResult(key: key) { return cached }
        if let running = inFlight[key] { return await running.value }
        // Between the check above and this store there is no await, so only one caller
        // can ever be the one that starts the task.
        let task = Task { await self.run(key: key, tracks: tracks) }
        inFlight[key] = task
        let result = await task.value
        inFlight.removeValue(forKey: key)
        return result
    }

    private func run(key: String, tracks: [PlayableTrack]) async -> SourceLoudness? {
        let candidates = tracks.filter { track in
            guard !isFillerTrack(track.title) else { return false }
            guard let url = URL(string: track.url), url.scheme == "https" else { return false }
            return true
        }
        let indices = levelingSampleIndices(total: candidates.count)
        guard !indices.isEmpty else { return nil }

        do {
            var meter: LoudnessMeter?
            var measuredSegments = 0

            for index in indices {
                try Task.checkCancellation()
                let track = candidates[index]
                guard let url = URL(string: track.url) else { continue }
                do {
                    let data = try await fetchSegment(from: url, durationMs: track.durationMs)
                    try Task.checkCancellation()
                    let pcm = try decodeSegment(data)
                    // Segments must pool into one gated measurement, so the meter is
                    // created from the first decoded segment's format and any later
                    // segment in a different format is skipped — a rate mismatch across
                    // pooled blocks would silently skew the K-weighting, and a mixed
                    // rate within one source is an anomaly, not something to average.
                    if let existing = meter {
                        guard existing.sampleRate == pcm.sampleRate,
                              existing.channels == pcm.channels else { continue }
                    } else {
                        meter = LoudnessMeter(sampleRate: pcm.sampleRate, channels: pcm.channels)
                    }
                    meter?.push(pcm.samples)
                    measuredSegments += 1
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    // One bad segment out of three shouldn't cost the source its
                    // measurement — skip it and keep going.
                    continue
                }
            }

            guard let meter, measuredSegments > 0 else { return nil }
            let result = meter.result()
            guard let lufs = result.lufs else { return nil }  // every block gated out (silence)

            let row = SourceLoudness(
                key: key,
                lufs: lufs,
                peakDb: result.peakDbfs,
                sampledTracks: measuredSegments
            )
            guard !Task.isCancelled else { return nil }
            try? cache.save(row)
            return row
        } catch {
            // Cancellation (and anything else fatal) leaves the cache untouched: a
            // half-measured source must never read as measured.
            return nil
        }
    }

    // MARK: - Segment fetch

    /// Fetch the middle `segmentSeconds` of `url` with one Range request. A HEAD probe
    /// first asks the server for the file size so the slice can be centered on the track's
    /// midpoint by byte; when the server won't say, fall back to the assumed byte rate.
    private func fetchSegment(from url: URL, durationMs: Int64) async throws -> Data {
        let (start, end) = try await segmentByteRange(url: url, durationMs: durationMs)
        var request = URLRequest(url: url)
        request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200...206).contains(http.statusCode),
              !data.isEmpty else {
            throw LoudnessMeasurerError.badResponse(url)
        }
        return data
    }

    private func segmentByteRange(url: URL, durationMs: Int64) async throws -> (Int, Int) {
        var contentLength: Int?
        var probe = URLRequest(url: url)
        probe.httpMethod = "HEAD"
        // URLSession.data returns (Data, URLResponse); a HEAD has no body, so only the
        // response half matters here.
        if let (_, response) = try? await session.data(for: probe),
           let http = response as? HTTPURLResponse,
           http.expectedContentLength > 0 {
            contentLength = Int(http.expectedContentLength)
        }

        let durationSec = max(1.0, Double(durationMs) / 1000.0)
        let totalBytes: Double
        let bytesPerSecond: Double
        if let contentLength {
            totalBytes = Double(contentLength)
            bytesPerSecond = totalBytes / durationSec
        } else {
            bytesPerSecond = Double(Self.assumedBytesPerSecond)
            totalBytes = bytesPerSecond * durationSec
        }

        let segmentBytes = min(totalBytes, bytesPerSecond * Self.segmentSeconds)
        let start = max(0, Int((totalBytes - segmentBytes) / 2))
        let end = min(max(0, Int(totalBytes) - 1), start + Int(segmentBytes))
        return (start, end)
    }

    // MARK: - Segment decode

    /// Writes `data` to a temp file under Caches, decodes it, and deletes the file —
    /// the deletion runs on the way out even when the decode throws.
    private func decodeSegment(_ data: Data) throws -> DecodedPCM {
        let tempURL = tempDirectory.appendingPathComponent("segment-\(UUID().uuidString).bin")
        try data.write(to: tempURL, options: .atomic)
        defer { try? FileManager.default.removeItem(at: tempURL) }
        return try decoder.decode(fileURL: tempURL)
    }
}
