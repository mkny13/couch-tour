import XCTest
@testable import CouchTourKit

/// `TTLCache` in isolation — hit, miss, expiry (with an injected clock, since a real `Date()`
/// can't be faked from a test), and the LRU eviction that bounds memory once a cache is full
/// (#61). Port of `CatalogCacheTest.kt`'s `TtlCacheTest`.
final class TTLCacheTests: XCTestCase {

    func testAPutValueComesBackOnTheNextGet() async {
        let cache = TTLCache<String, Int>(ttl: 1000, maxEntries: 10)

        await cache.put("a", 1)

        let value = await cache.get("a")
        XCTAssertEqual(1, value)
    }

    func testAKeyThatWasNeverPutMisses() async {
        let cache = TTLCache<String, Int>(ttl: 1000, maxEntries: 10)

        let value = await cache.get("missing")
        XCTAssertNil(value)
    }

    func testAnEntryOlderThanTheTtlMissesAndIsDropped() async {
        var now = Date(timeIntervalSince1970: 0)
        let cache = TTLCache<String, Int>(ttl: 1000, maxEntries: 10, now: { now })

        await cache.put("a", 1)
        now = now.addingTimeInterval(1000) // exactly at the ttl boundary counts as expired
        let value = await cache.get("a")

        XCTAssertNil(value)
        let count = await cache.count
        XCTAssertEqual(0, count)
    }

    func testAnEntryJustUnderTheTtlStillHits() async {
        var now = Date(timeIntervalSince1970: 0)
        let cache = TTLCache<String, Int>(ttl: 1000, maxEntries: 10, now: { now })

        await cache.put("a", 1)
        now = now.addingTimeInterval(999)

        let value = await cache.get("a")
        XCTAssertEqual(1, value)
    }

    func testClearDropsEveryEntryRegardlessOfTtl() async {
        let cache = TTLCache<String, Int>(ttl: 1000, maxEntries: 10)
        await cache.put("a", 1)

        await cache.clear()

        let value = await cache.get("a")
        XCTAssertNil(value)
        let count = await cache.count
        XCTAssertEqual(0, count)
    }

    func testPuttingPastMaxEntriesEvictsTheLeastRecentlyUsedEntryNotTheOldestInserted() async {
        let cache = TTLCache<String, Int>(ttl: 60_000, maxEntries: 2)

        await cache.put("a", 1)
        await cache.put("b", 2)
        _ = await cache.get("a") // touch "a" so "b" becomes the least recently used
        await cache.put("c", 3)

        let a = await cache.get("a")
        let b = await cache.get("b")
        let c = await cache.get("c")
        XCTAssertEqual(1, a)
        XCTAssertNil(b)
        XCTAssertEqual(3, c)
        let count = await cache.count
        XCTAssertEqual(2, count)
    }
}

/// The caching layer #61 adds on top of the two `MusicSource`s — a second call for the same
/// artist/period/show is served from memory rather than hitting the network again, and
/// `RelistenCatalogSource.resetCache()`/`PhishInCatalogCache.shared.resetCache()` force a real
/// re-fetch. Port of `CatalogCacheTest.kt`'s `CatalogCacheHitTest`.
final class CatalogCacheHitTests: XCTestCase {

    private var server: MockServer!

    override func setUp() async throws {
        try await super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://mock.test/api")!
        await PhishInCatalogCache.shared.resetCache()
    }

    override func tearDown() async throws {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://api.relisten.net/api")!
        await PhishInCatalogCache.shared.resetCache()
        try await super.tearDown()
    }

    func testPhishInSourcePeriodsIsServedFromCacheOnTheSecondCall() async throws {
        let source = PhishInSource()
        server.enqueue(#"[{"period":"1997","shows_with_audio_count":81}]"#)

        let first = try await source.periods(artist: PHISH)
        let second = try await source.periods(artist: PHISH)

        XCTAssertEqual(first, second)
        XCTAssertEqual(1, server.requestCount)
    }

    func testPhishInSourceResetCacheForcesARealRefetch() async throws {
        let source = PhishInSource()
        server.enqueue(#"[{"period":"1997","shows_with_audio_count":81}]"#)
        _ = try await source.periods(artist: PHISH)

        await PhishInCatalogCache.shared.resetCache()
        server.enqueue(#"[{"period":"1998","shows_with_audio_count":40}]"#)
        let after = try await source.periods(artist: PHISH)

        XCTAssertEqual(2, server.requestCount)
        XCTAssertEqual(["1998"], after.map { $0.id })
    }

    func testPhishInSourceShowsIsServedFromCachePerPeriodKeyedIndependently() async throws {
        let source = PhishInSource()
        server.enqueue(#"{"shows":[{"date":"1997-11-17"}]}"#)
        server.enqueue(#"{"shows":[{"date":"1998-11-14"}]}"#)

        _ = try await source.shows(artist: PHISH, period: PeriodRef(id: "1997", label: "1997"))
        _ = try await source.shows(artist: PHISH, period: PeriodRef(id: "1998", label: "1998"))
        let repeat1997 = try await source.shows(artist: PHISH, period: PeriodRef(id: "1997", label: "1997"))
        let repeat1998 = try await source.shows(artist: PHISH, period: PeriodRef(id: "1998", label: "1998"))

        XCTAssertEqual(2, server.requestCount)
        XCTAssertEqual("1997-11-17", repeat1997.first?.date)
        XCTAssertEqual("1998-11-14", repeat1998.first?.date)
    }

    func testPhishInSourceShowIsServedFromCacheOnTheSecondCall() async throws {
        let source = PhishInSource()
        server.enqueue(#"{"date":"1997-11-17","tracks":[]}"#)

        let first = try await source.show(artist: PHISH, date: "1997-11-17", recordingId: nil)
        let second = try await source.show(artist: PHISH, date: "1997-11-17", recordingId: nil)

        XCTAssertEqual(first, second)
        XCTAssertEqual(1, server.requestCount)
    }

    func testRelistenCatalogSourcePeriodsIsServedFromCacheOnTheSecondCall() async throws {
        let source = RelistenCatalogSource()
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        server.enqueue(#"[{"uuid":"y","year":"1977","show_count":57}]"#)

        let first = try await source.periods(artist: artist)
        let second = try await source.periods(artist: artist)

        XCTAssertEqual(first, second)
        XCTAssertEqual(1, server.requestCount)
    }

    func testRelistenCatalogSourcePeriodsCachesSeparatelyPerArtist() async throws {
        let source = RelistenCatalogSource()
        let deadHead = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        let goose = ArtistRef(backend: .relisten, id: "goose", name: "Goose")
        server.enqueue(#"[{"uuid":"y1","year":"1977","show_count":57}]"#)
        server.enqueue(#"[{"uuid":"y2","year":"2023","show_count":40}]"#)

        let deadPeriods = try await source.periods(artist: deadHead)
        let goosePeriods = try await source.periods(artist: goose)

        XCTAssertEqual(2, server.requestCount)
        XCTAssertEqual("1977", deadPeriods.first?.label)
        XCTAssertEqual("2023", goosePeriods.first?.label)
    }

    func testRelistenCatalogSourceShowsIsServedFromCacheOnTheSecondCall() async throws {
        let source = RelistenCatalogSource()
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        server.enqueue(#"{"year":"1977","shows":[]}"#)

        let first = try await source.shows(artist: artist, period: PeriodRef(id: "year-uuid", label: "1977"))
        let second = try await source.shows(artist: artist, period: PeriodRef(id: "year-uuid", label: "1977"))

        XCTAssertEqual(first, second)
        XCTAssertEqual(1, server.requestCount)
    }

    func testRelistenCatalogSourceShowIsServedFromCacheOnTheSecondCall() async throws {
        let source = RelistenCatalogSource()
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead", hasSets: false)
        server.enqueue(
            """
            {"display_date":"1977-05-08","sources":[
                {"uuid":"src-1","sets":[{"index":0,"name":"Set","tracks":[]}]}
            ]}
            """
        )

        let first = try await source.show(artist: artist, date: "1977-05-08", recordingId: nil)
        let second = try await source.show(artist: artist, date: "1977-05-08", recordingId: nil)

        XCTAssertEqual(first, second)
        XCTAssertEqual(1, server.requestCount)
    }

    func testRelistenCatalogSourceResetCacheForcesARealRefetch() async throws {
        let source = RelistenCatalogSource()
        server.enqueue(#"[{"uuid":"u","slug":"phish","name":"Phish"}]"#)
        _ = try await source.artists()

        await source.resetCache()
        server.enqueue(#"[{"uuid":"u2","slug":"phish","name":"Phish"}]"#)
        _ = try await source.artists()

        XCTAssertEqual(2, server.requestCount)
    }
}
