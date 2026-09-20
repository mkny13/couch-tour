import XCTest
@testable import CouchTourKit

/// Decode-ahead measurement (#268): fixture PCM in, stub URLProtocol network, in-memory
/// cache — everything the measurer does around them (track sampling, Range targeting,
/// temp-file lifecycle, coalescing, cancellation) is what's under test.
@MainActor
final class LoudnessMeasurerTests: XCTestCase {

    // MARK: - Stub URLProtocol

    /// Serves HEAD probes and Range GETs from a static route table keyed by absolute URL.
    /// Records every Range header and request so tests can assert what the measurer asked for.
    private final class RangeStubURLProtocol: URLProtocol {
        struct Route {
            var statusCode: Int = 200
            var contentLength: Int64?
            var body: Data = Data()
        }

        nonisolated(unsafe) static var lock = NSLock()
        nonisolated(unsafe) static var routes: [String: Route] = [:]
        nonisolated(unsafe) static var rangeHeaders: [String] = []
        nonisolated(unsafe) static var requestCount = 0

        static func reset() {
            lock.lock(); defer { lock.unlock() }
            routes = [:]
            rangeHeaders = []
            requestCount = 0
        }

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            Self.lock.lock(); defer { Self.lock.unlock() }
            Self.requestCount += 1
            let url = request.url!
            let route = Self.routes[url.absoluteString] ?? Route()

            let headers = route.contentLength.map { ["Content-Length": String($0)] }
            let response = HTTPURLResponse(url: url, statusCode: route.statusCode, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)

            if request.httpMethod == "HEAD" {
                client?.urlProtocolDidFinishLoading(self)
                return
            }
            if let range = request.value(forHTTPHeaderField: "Range") {
                Self.rangeHeaders.append(range)
            }
            if !route.body.isEmpty {
                client?.urlProtocol(self, didLoad: route.body)
            }
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {}
    }

    // MARK: - Seams

    /// Returns fixed PCM regardless of the temp file's contents — the meter math itself is
    /// covered by LoudnessTests; this stands in for the AVAudioFile decoder.
    private struct FixtureDecoder: SegmentDecoder {
        var pcm: DecodedPCM
        var throwOnDecode = false

        func decode(fileURL: URL) throws -> DecodedPCM {
            if throwOnDecode { throw LoudnessMeasurerError.undecodable(fileURL) }
            return pcm
        }
    }

    private final class MemoryLoudnessCache: SourceLoudnessCache {
        var rows: [String: SourceLoudness] = [:]
        func current(key: String) throws -> SourceLoudness? { rows[key] }
        func save(_ loudness: SourceLoudness) throws { rows[loudness.key] = loudness }
    }

    // MARK: - Fixtures

    /// 48 kHz stereo, 3 s of 997 Hz sine at the given dBFS — measures ≈ −23 LUFS at 0 dBFS,
    /// matching the shared test vectors in LoudnessTests.
    private func fixturePCM(dbfs: Double = -20) -> DecodedPCM {
        let sampleRate = 48_000
        let amplitude = pow(10.0, dbfs / 20.0)
        let frames = sampleRate * 3
        var samples = [Float](repeating: 0, count: frames * 2)
        for i in 0..<frames {
            let v = Float(amplitude * sin(2.0 * .pi * 997.0 * Double(i) / Double(sampleRate)))
            samples[i * 2] = v
            samples[i * 2 + 1] = v
        }
        return DecodedPCM(sampleRate: sampleRate, channels: 2, samples: samples)
    }

    private func track(_ id: String, title: String = "Song", durationMs: Int64 = 200_000, url: String? = nil) -> PlayableTrack {
        PlayableTrack(
            id: id, title: title, position: 0, durationMs: durationMs,
            url: url ?? "https://example.com/\(id).mp3"
        )
    }

    private func stubbedSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RangeStubURLProtocol.self]
        return URLSession(configuration: config)
    }

    private func stub(track id: String, statusCode: Int = 200, contentLength: Int64 = 1_000_000, body: Data = Data(repeating: 0x7F, count: 256)) {
        RangeStubURLProtocol.lock.lock(); defer { RangeStubURLProtocol.lock.unlock() }
        RangeStubURLProtocol.routes["https://example.com/\(id).mp3"] = .init(
            statusCode: statusCode, contentLength: contentLength, body: body
        )
    }

    private var session: URLSession!
    private var cache: MemoryLoudnessCache!

    override func setUp() {
        super.setUp()
        RangeStubURLProtocol.reset()
        session = stubbedSession()
        cache = MemoryLoudnessCache()
    }

    override func tearDown() {
        session.finishTasksAndInvalidate()
        super.tearDown()
    }

    private func makeMeasurer(decoder: SegmentDecoder) -> LoudnessMeasurer {
        LoudnessMeasurer(session: session, decoder: decoder, cache: cache)
    }

    // MARK: - Track sampling

    func testSampleIndicesSpreadEvenly() {
        XCTAssertEqual(levelingSampleIndices(total: 7), [1, 3, 5])
        XCTAssertEqual(levelingSampleIndices(total: 2), [0, 1])
        XCTAssertEqual(levelingSampleIndices(total: 0), [])
        XCTAssertEqual(levelingSampleIndices(total: 20), [5, 10, 15])
    }

    // MARK: - Happy path: fetch → decode → meter → cache

    func testMeasuresAndCachesFromThreeSegments() async {
        for i in 1...5 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))
        let tracks = (1...5).map { track("t\($0)") }

        let result = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        XCTAssertNotNil(result)
        XCTAssertEqual(result?.sampledTracks, 3, "At most 3 of the 5 tracks get sampled")
        XCTAssertEqual(result?.lufs ?? 0, -23.0, accuracy: 0.5)
        XCTAssertEqual(cache.rows["show:1997-02-13"]?.key, "show:1997-02-13")
        XCTAssertEqual(cache.rows["show:1997-02-13"]?.sampledTracks, 3)
        // Never the FLAC URL — measurement always rides the MP3 (`track.url`).
        XCTAssertFalse(RangeStubURLProtocol.rangeHeaders.isEmpty)
    }

    func testCachedResultShortCircuitsNetwork() async {
        stub(track: "t1")
        cache.rows["show:1997-02-13"] = SourceLoudness(key: "show:1997-02-13", lufs: -16, peakDb: -2, sampledTracks: 1)
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))

        let result = await measurer.measure(key: "show:1997-02-13", tracks: [track("t1")])

        XCTAssertEqual(result?.lufs, -16, "Cache hit must be returned as-is, not re-measured")
        XCTAssertEqual(RangeStubURLProtocol.requestCount, 0, "A cache hit makes no requests")
    }

    func testConcurrentMeasuresCoalesceOntoOneFetchPass() async {
        for i in 1...3 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))
        let tracks = (1...3).map { track("t\($0)") }

        async let a = measurer.measure(key: "show:1997-02-13", tracks: tracks)
        async let b = measurer.measure(key: "show:1997-02-13", tracks: tracks)
        let (ra, rb) = await (a, b)

        XCTAssertEqual(ra?.key, rb?.key)
        XCTAssertEqual(ra?.lufs, rb?.lufs)
        // One measurement = one HEAD probe + one Range GET per segment, doubled only if
        // the calls failed to coalesce.
        XCTAssertEqual(RangeStubURLProtocol.requestCount, 6, "3 segments × (HEAD + GET); coalescing must not double it")
    }

    // MARK: - Failure handling

    func testFailedSegmentsAreSkippedAndOthersStillMeasure() async {
        stub(track: "t1", statusCode: 404)
        stub(track: "t2", statusCode: 404)
        stub(track: "t3")
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))
        let tracks = (1...3).map { track("t\($0)") }

        let result = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        XCTAssertNotNil(result, "Two dead segments must not cost the source its measurement")
        XCTAssertEqual(result?.sampledTracks, 1)
        XCTAssertNotNil(cache.rows["show:1997-02-13"])
    }

    func testAllSegmentsFailReturnsNilAndCachesNothing() async {
        for i in 1...3 { stub(track: "t\(i)", statusCode: 404) }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))
        let tracks = (1...3).map { track("t\($0)") }

        let result = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        XCTAssertNil(result)
        XCTAssertTrue(cache.rows.isEmpty, "A failed measurement must never read as measured")
    }

    func testUndecodableSegmentsAreSkippedLikeFailedFetches() async {
        for i in 1...3 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM(), throwOnDecode: true))
        let tracks = (1...3).map { track("t\($0)") }

        let result = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        XCTAssertNil(result)
        XCTAssertTrue(cache.rows.isEmpty)
    }

    func testSilenceIsNotCached() async {
        for i in 1...3 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM(dbfs: -140)))
        let tracks = (1...3).map { track("t\($0)") }

        let result = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        XCTAssertNil(result, "All-gated audio means no measurement — unknown loudness plays at 0 dB")
        XCTAssertTrue(cache.rows.isEmpty)
    }

    func testUnplayableTracksYieldNoMeasurement() async {
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))

        let insecure = await measurer.measure(key: "show:1997-02-13", tracks: [track("bad", url: "http://insecure.example.com/a.mp3")])
        XCTAssertNil(insecure, "Non-https MP3 URLs are never fetched")

        let none = await measurer.measure(key: "show:1997-02-13", tracks: [])
        XCTAssertNil(none)
    }

    // MARK: - Temp file lifecycle

    func testTempFilesAreCleanedUpAfterSuccess() async {
        for i in 1...3 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))
        let tracks = (1...3).map { track("t\($0)") }

        _ = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        let leftovers = await measurer.tempFiles()
        XCTAssertTrue(leftovers.isEmpty, "Segment temp files must be deleted after decoding")
    }

    func testTempFilesAreCleanedUpWhenDecodeThrows() async {
        for i in 1...3 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM(), throwOnDecode: true))
        let tracks = (1...3).map { track("t\($0)") }

        _ = await measurer.measure(key: "show:1997-02-13", tracks: tracks)

        let leftovers = await measurer.tempFiles()
        XCTAssertTrue(leftovers.isEmpty, "The temp file goes even when the decoder throws")
    }

    // MARK: - Range targeting

    func testRangeTargetsTheMiddleThirtySeconds() async {
        stub(track: "t1", contentLength: 1_000_000)
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))

        _ = await measurer.measure(key: "show:1997-02-13", tracks: [track("t1")])

        // 200 s track, 1 000 000 bytes → 5 000 B/s. A 30 s slice is 150 000 bytes,
        // centered: bytes 425000–575000.
        XCTAssertEqual(RangeStubURLProtocol.rangeHeaders, ["bytes=425000-575000"])
    }

    func testRangeFallsBackToAssumedByteRateWithoutContentLength() async {
        RangeStubURLProtocol.lock.lock()
        RangeStubURLProtocol.routes["https://example.com/t1.mp3"] = .init(statusCode: 200, contentLength: nil, body: Data(repeating: 0x7F, count: 256))
        RangeStubURLProtocol.lock.unlock()
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))

        _ = await measurer.measure(key: "show:1997-02-13", tracks: [track("t1")])

        // No Content-Length: assume ~128 kbps (16 kB/s). 200 s → 3 200 000 bytes,
        // 30 s slice = 480 000 bytes centered → 1360000–1840000.
        XCTAssertEqual(RangeStubURLProtocol.rangeHeaders, ["bytes=1360000-1840000"])
    }

    func testShortTracksClampTheRangeInsideTheFile() async {
        // A 30 s track at 5 000 B/s is only 150 000 bytes and the slice wants all of it;
        // a 100 000-byte file gets requested whole (0–99999), never off its end.
        stub(track: "t1", contentLength: 100_000)
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))

        _ = await measurer.measure(key: "show:1997-02-13", tracks: [track("t1", durationMs: 30_000)])

        XCTAssertEqual(RangeStubURLProtocol.rangeHeaders, ["bytes=0-99999"])
    }

    // MARK: - Cancellation

    func testCancelledCallerMeasuresNothingAndCachesNothing() async {
        for i in 1...3 { stub(track: "t\(i)") }
        let measurer = makeMeasurer(decoder: FixtureDecoder(pcm: fixturePCM()))
        let tracks = (1...3).map { track("t\($0)") }

        // Yield inside the task before measure() runs so cancel() lands first and the
        // entry check sees a cancelled caller — no scheduling race in the assertion.
        let task = Task {
            await Task.yield()
            return await measurer.measure(key: "show:1997-02-13", tracks: tracks)
        }
        task.cancel()
        let result = await task.value

        XCTAssertNil(result)
        XCTAssertTrue(cache.rows.isEmpty)
        XCTAssertEqual(RangeStubURLProtocol.requestCount, 0)
    }
}

