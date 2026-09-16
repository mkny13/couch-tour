import XCTest
@testable import CouchTourKit

/// Cross-feature journeys for Discovery & Catalog (part 2.2 of #199).
///
/// The original port of Android's DiscoveryCatalogE2ETest.kt was false-green: it tested
/// test-local mirrors (a hand-rolled `sortShows` with date-ascending tie-breaks where
/// production uses date-descending, a Hasher-seeded artwork generator whose monogram said
/// "G" for Goose where production says "GOOSE", a phantom `artist_tour_preferences` schema
/// with `preferenceType`/`periodLabel` columns production's `ArtistTourPreference` doesn't
/// have) instead of the production APIs — so any production regression stayed green. All of
/// that was deleted rather than re-pointed, because the unit-level behavior it duplicated
/// already lives in dedicated suites:
///
/// - `RelistenPopularityTests` — popularity decode + all seven `sortShows` modes
/// - `Milestone1StressTests` — tie-break tiers, empty/single/1000-element boundaries
/// - `TagTests` — `filterShowsByTag` semantics
/// - `ArtworkTests` — `ShowArtworkGenerator` hash/palette/monogram/date-badge
/// - `NextStopTests` — `tourFor`/`currentTours`/`oldestUnplayed`/`NextStop.load`
/// - `ProgressStoreTests` — store CRUD, tour-preference persistence, migrations
///
/// What's left here — and what this file exists for — is composing those APIs through real
/// production code paths end to end, which no other suite exercises.
final class DiscoveryCatalogE2ETests: XCTestCase {
    private let gratefulDead = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")

    private struct TestError: Error {}

    private final class MockMusicSource: MusicSource {
        let backend: Backend
        var periodsHandler: ((ArtistRef) -> [PeriodRef])?
        var showsHandler: ((ArtistRef, PeriodRef) -> [ShowSummary])?

        init(backend: Backend) {
            self.backend = backend
        }

        func artists() async throws -> [ArtistRef] { [] }
        func periods(artist: ArtistRef) async throws -> [PeriodRef] {
            periodsHandler?(artist) ?? []
        }
        func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
            showsHandler?(artist, period) ?? []
        }
        func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
            throw TestError()
        }
        func search(term: String) async throws -> SearchHits {
            SearchHits()
        }
    }

    private func show(
        _ date: String,
        tourName: String? = nil,
        tags: [Tag] = [],
        popularity: RelistenPopularity? = nil
    ) -> ShowSummary {
        ShowSummary(
            artist: gratefulDead,
            date: date,
            venue: "Barton Hall",
            tourName: tourName,
            tags: tags,
            popularity: popularity
        )
    }

    private func popularity(
        _48h: Double = 0,
        momentum: Double = 0,
        trendRatio: Double = 0
    ) -> RelistenPopularity {
        RelistenPopularity(
            momentumScore: momentum,
            trendRatio: trendRatio,
            windows: ["48h": WindowPopularity(plays: 10, hotScore: _48h)]
        )
    }

    // MARK: - Journey: defunct-artist tour completion (tourFor → tag filter → oldestUnplayed → artwork)

    /// A defunct artist with a saved tour preference, a tag-filtered view of that tour, and
    /// history accumulated show by show — the full next-stop pipeline, through the real
    /// production functions instead of the local resolver this file used to reimplement.
    func testJourneyDefunctArtistTourCompletionAdvancesThroughTagFilteredShows() async throws {
        let mock = MockMusicSource(backend: .relisten)
        mock.periodsHandler = { _ in [PeriodRef(id: "1977", label: "1977")] }
        mock.showsHandler = { artist, _ in
            [
                ShowSummary(artist: artist, date: "1977-05-07", tourName: "Spring 1977", tags: [Tag(name: "SBD")]),
                ShowSummary(artist: artist, date: "1977-05-08", tourName: "Spring 1977", tags: [Tag(name: "SBD")]),
                ShowSummary(artist: artist, date: "1977-05-09", tourName: "Spring 1977", tags: [Tag(name: "AUD")]),
            ]
        }

        let pref = ArtistTourPreference(artistKey: gratefulDead.key, tourName: "Spring 1977", year: "1977")
        let tourShows = await tourFor(artist: gratefulDead, preference: pref, source: { _ in mock })
        XCTAssertEqual(3, tourShows.count)

        // The soundboard-filtered surface picks only the SBD shows out of the tour.
        let sbdShows = filterShowsByTag(tourShows, tagName: "SBD")
        XCTAssertEqual(["1977-05-07", "1977-05-08"], sbdShows.map { $0.date })

        // Walk the next-stop engine across the filtered surface, feeding it history in the
        // production queue-key format.
        let stop1 = oldestUnplayed(candidates: sbdShows, played: [])
        XCTAssertEqual("1977-05-07", stop1?.date)

        var played = playedShowIds(from: [recordingQueueKey("grateful-dead", "1977-05-07", "tape-1")])
        let stop2 = oldestUnplayed(candidates: sbdShows, played: played)
        XCTAssertEqual("1977-05-08", stop2?.date)
        XCTAssertTrue(stop2?.tags.contains { $0.name == "SBD" } == true)

        // The resolved stop carries everything the procedural artwork needs.
        XCTAssertEqual("GD", ShowArtworkGenerator.monogram(for: stop2?.artist.name))
        XCTAssertEqual("1977", ShowArtworkGenerator.year(from: stop2?.date))
        XCTAssertEqual("05/08", ShowArtworkGenerator.monthDay(from: stop2?.date))

        // With every candidate played, the engine has nothing left to offer.
        played = playedShowIds(from: [
            recordingQueueKey("grateful-dead", "1977-05-07", "tape-1"),
            recordingQueueKey("grateful-dead", "1977-05-08", "tape-1"),
        ])
        XCTAssertNil(oldestUnplayed(candidates: sbdShows, played: played))
    }

    // MARK: - Journey: discovery pipeline (tag filter → trending sort)

    /// Browsing by tag and then re-sorting by trending — the two surfaces the discovery UI
    /// chains — must compose through the real `filterShowsByTag`/`sortShows`, not a local
    /// reimplementation (the old copy here tested its own `Array.filter` instead).
    func testJourneyDiscoveryPipelineTagFilterThenTrendingSort() {
        let shows = [
            show("1977-05-07", tags: [Tag(name: "SBD")], popularity: popularity(_48h: 15)),
            show("1977-05-08", tags: [Tag(name: "SBD"), Tag(name: "Matrix")], popularity: popularity(_48h: 95)),
            show("1977-05-09", tags: [Tag(name: "Matrix")], popularity: popularity(_48h: 40)),
        ]

        let matrixShows = filterShowsByTag(shows, tagName: "Matrix")
        XCTAssertEqual(2, matrixShows.count)

        let trending = sortShows(matrixShows, by: .trending48h)
        XCTAssertEqual("1977-05-08", trending[0].date)
        XCTAssertEqual(95.0, trending[0].hotScore48h, accuracy: 0.001)
    }

    // MARK: - Journey: persisted progress drives the next stop across store reopen

    /// Progress and tour preferences survive a store reopen on the real production schema
    /// (real migrations, real `phishin.db`-shaped file), and the reopened history still
    /// advances the next-stop engine. This replaces the old phantom-schema migration tests,
    /// which created their own table layout production never used.
    func testJourneyPersistedHistoryAndPreferenceSurviveStoreReopen() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let dbURL = dir.appendingPathComponent("phishin.db")

        let store = try ProgressStore(url: dbURL)
        try store.put(PlaybackProgress(
            queueKey: recordingQueueKey("grateful-dead", "1977-05-07", "tape-1"),
            title: "1977-05-07", subtitle: "Boston Garden", trackIndex: 2,
            positionMs: 45_000, trackTitle: "Scarlet Begonias", updatedAt: 1_000, finished: true,
            artist: "Grateful Dead"
        ))
        try store.saveTourPreference(ArtistTourPreference(
            artistKey: gratefulDead.key, tourName: "Spring 1977", year: "1977"
        ))

        // Reopen: a fresh instance over the same file, migrations already applied.
        let reopened = try ProgressStore(url: dbURL)
        let history = try reopened.get(key: recordingQueueKey("grateful-dead", "1977-05-07", "tape-1"))
        XCTAssertEqual("1977-05-07", history?.title)
        XCTAssertEqual(true, history?.finished)
        let pref = try reopened.getTourPreference(artistKey: gratefulDead.key)
        XCTAssertEqual("Spring 1977", pref?.tourName)

        // The reopened history is what makes the next stop 05-08, not 05-07 again.
        let candidates = [
            show("1977-05-07", tourName: "Spring 1977"),
            show("1977-05-08", tourName: "Spring 1977"),
        ]
        let playedKeys = [recordingQueueKey("grateful-dead", "1977-05-07", "tape-1")]
        XCTAssertEqual(
            "1977-05-08",
            oldestUnplayed(candidates: candidates, played: playedShowIds(from: playedKeys))?.date
        )
    }
}
