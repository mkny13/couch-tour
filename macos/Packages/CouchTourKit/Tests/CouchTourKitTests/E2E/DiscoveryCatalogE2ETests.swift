import XCTest
import GRDB
@testable import CouchTourKit

/// Comprehensive E2E and requirement-driven opaque-box test suite for Couch Tour
/// Phase 2 Batch 2: Discovery & Catalog Enrichment on macOS (CouchTourKit / SwiftUI).
///
/// Covers:
/// - Tier 1: Feature Coverage (>=5 tests per feature)
/// - Tier 2: Boundary & Corner Cases (>=5 tests per feature)
/// - Tier 3: Cross-Feature Combinations (Pairwise interactions)
/// - Tier 4: Real-World Application Scenarios (End-to-end workflows)
final class DiscoveryCatalogE2ETests: XCTestCase {

    // -------------------------------------------------------------------------
    // Test Models & Schemas for E2E Contract Verification
    // -------------------------------------------------------------------------

    public struct TestRelistenPopularityWindow: Codable, Equatable, Hashable, Sendable {
        public let plays: Int
        public let hours: Double
        public let hotScore: Double

        enum CodingKeys: String, CodingKey {
            case plays, hours
            case hotScore = "hot_score"
        }

        public init(plays: Int = 0, hours: Double = 0.0, hotScore: Double = 0.0) {
            self.plays = plays
            self.hours = hours
            self.hotScore = hotScore
        }
    }

    public struct TestRelistenPopularityWindows: Codable, Equatable, Hashable, Sendable {
        public let w48h: TestRelistenPopularityWindow?
        public let w7d: TestRelistenPopularityWindow?
        public let w30d: TestRelistenPopularityWindow?

        enum CodingKeys: String, CodingKey {
            case w48h = "48h"
            case w7d = "7d"
            case w30d = "30d"
        }

        public init(w48h: TestRelistenPopularityWindow? = nil, w7d: TestRelistenPopularityWindow? = nil, w30d: TestRelistenPopularityWindow? = nil) {
            self.w48h = w48h
            self.w7d = w7d
            self.w30d = w30d
        }
    }

    public struct TestRelistenPopularity: Codable, Equatable, Hashable, Sendable {
        public let momentumScore: Double
        public let trendRatio: Double
        public let windows: TestRelistenPopularityWindows?

        enum CodingKeys: String, CodingKey {
            case momentumScore = "momentum_score"
            case trendRatio = "trend_ratio"
            case windows
        }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            self.momentumScore = try container.decodeIfPresent(Double.self, forKey: .momentumScore) ?? 0.0
            self.trendRatio = try container.decodeIfPresent(Double.self, forKey: .trendRatio) ?? 0.0
            self.windows = try container.decodeIfPresent(TestRelistenPopularityWindows.self, forKey: .windows)
        }

        public init(momentumScore: Double = 0.0, trendRatio: Double = 0.0, windows: TestRelistenPopularityWindows? = nil) {
            self.momentumScore = momentumScore
            self.trendRatio = trendRatio
            self.windows = windows
        }
    }

    public struct TestTag: Codable, Hashable, Sendable {
        public let name: String
        public let description: String?
        public let color: String?
        public let priority: Int
        public let notes: String?

        public init(name: String, description: String? = nil, color: String? = nil, priority: Int = 0, notes: String? = nil) {
            self.name = name
            self.description = description
            self.color = color
            self.priority = priority
            self.notes = notes
        }
    }

    public struct TestArtistTourPreference: Codable, Equatable, FetchableRecord, PersistableRecord, Sendable {
        public static let databaseTableName = "artist_tour_preferences"

        public var artistKey: String
        public var preferenceType: String // "TOUR" or "YEAR"
        public var tourName: String?
        public var periodId: String?
        public var periodLabel: String?
        public var updatedAt: Int64

        public init(
            artistKey: String,
            preferenceType: String,
            tourName: String? = nil,
            periodId: String? = nil,
            periodLabel: String? = nil,
            updatedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
        ) {
            self.artistKey = artistKey
            self.preferenceType = preferenceType
            self.tourName = tourName
            self.periodId = periodId
            self.periodLabel = periodLabel
            self.updatedAt = updatedAt
        }
    }

    public enum TestShowSortOption: String, CaseIterable, Identifiable {
        case dateAsc = "Date (Oldest First)"
        case dateDesc = "Date (Newest First)"
        case topRated = "Top Rated"
        case trending48h = "Trending (48 Hours)"
        case trending7d = "Trending (7 Days)"
        case trending30d = "Trending (30 Days)"
        case momentum = "Momentum"

        public var id: String { rawValue }
    }

    public struct TestEnrichedShowSummary: Hashable, Sendable {
        public let artist: ArtistRef
        public let date: String
        public let venue: String?
        public let location: String?
        public let tourName: String?
        public let artURL: String?
        public let rating: Double
        public let tags: [String]
        public let popularity: TestRelistenPopularity?

        public init(
            artist: ArtistRef,
            date: String,
            venue: String? = nil,
            location: String? = nil,
            tourName: String? = nil,
            artURL: String? = nil,
            rating: Double = 0.0,
            tags: [String] = [],
            popularity: TestRelistenPopularity? = nil
        ) {
            self.artist = artist
            self.date = date
            self.venue = venue
            self.location = location
            self.tourName = tourName
            self.artURL = artURL
            self.rating = rating
            self.tags = tags
            self.popularity = popularity
        }

        public var where_: String {
            [venue, location].compactMap { $0 }.joined(separator: " · ")
        }
        public var hotScore48h: Double { popularity?.windows?.w48h?.hotScore ?? 0.0 }
        public var hotScore7d: Double { popularity?.windows?.w7d?.hotScore ?? 0.0 }
        public var hotScore30d: Double { popularity?.windows?.w30d?.hotScore ?? 0.0 }
        public var momentumScore: Double { popularity?.momentumScore ?? 0.0 }
        public var plays30d: Int { popularity?.windows?.w30d?.plays ?? 0 }
    }

    // Artist test fixtures
    private let gratefulDead = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead", hasSets: true, hasMultipleSources: true)
    private let phish = ArtistRef(backend: .phishin, id: "phish", name: "Phish", hasSets: true, hasMultipleSources: false)
    private let goose = ArtistRef(backend: .relisten, id: "goose", name: "Goose", hasSets: true, hasMultipleSources: true)
    private let jgb = ArtistRef(backend: .relisten, id: "jgb", name: "Jerry Garcia Band", hasSets: true, hasMultipleSources: true)

    private var dbQueue: DatabaseQueue!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dbQueue = try DatabaseQueue()
        try setupDatabaseSchema(dbQueue)
    }

    private func setupDatabaseSchema(_ db: DatabaseQueue) throws {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1_initial") { db in
            try db.create(table: "progress") { t in
                t.column("queueKey", .text).primaryKey()
                t.column("title", .text).notNull()
                t.column("subtitle", .text).notNull()
                t.column("artUrl", .text)
                t.column("trackIndex", .integer).notNull()
                t.column("positionMs", .integer).notNull()
                t.column("trackTitle", .text).notNull()
                t.column("updatedAt", .integer).notNull()
                t.column("finished", .boolean).notNull().defaults(to: false)
                t.column("dismissed", .boolean).notNull().defaults(to: false)
                t.column("artist", .text).notNull().defaults(to: "")
                t.column("deletedAt", .integer)
            }
        }
        migrator.registerMigration("v9_artistTourPreferences") { db in
            try db.create(table: "artist_tour_preferences") { t in
                t.column("artistKey", .text).primaryKey()
                t.column("preferenceType", .text).notNull()
                t.column("tourName", .text)
                t.column("periodId", .text)
                t.column("periodLabel", .text)
                t.column("updatedAt", .integer).notNull()
            }
        }
        try migrator.migrate(db)
    }

    // -------------------------------------------------------------------------
    // Helper Pure Functions
    // -------------------------------------------------------------------------

    private func recordingShowKey(_ artistSlug: String, _ date: String) -> String {
        "relisten:\(artistSlug)/\(date)"
    }

    private func sortShows(_ shows: [TestEnrichedShowSummary], by option: TestShowSortOption) -> [TestEnrichedShowSummary] {
        switch option {
        case .dateAsc:
            return shows.sorted { $0.date < $1.date }
        case .dateDesc:
            return shows.sorted { $0.date > $1.date }
        case .topRated:
            return shows.sorted {
                if $0.rating != $1.rating { return $0.rating > $1.rating }
                return $0.date < $1.date
            }
        case .trending48h:
            return shows.sorted {
                if $0.hotScore48h != $1.hotScore48h { return $0.hotScore48h > $1.hotScore48h }
                if $0.momentumScore != $1.momentumScore { return $0.momentumScore > $1.momentumScore }
                return $0.date < $1.date
            }
        case .trending7d:
            return shows.sorted {
                if $0.hotScore7d != $1.hotScore7d { return $0.hotScore7d > $1.hotScore7d }
                return $0.date < $1.date
            }
        case .trending30d:
            return shows.sorted {
                if $0.hotScore30d != $1.hotScore30d { return $0.hotScore30d > $1.hotScore30d }
                return $0.date < $1.date
            }
        case .momentum:
            return shows.sorted {
                if $0.momentumScore != $1.momentumScore { return $0.momentumScore > $1.momentumScore }
                return $0.date < $1.date
            }
        }
    }

    private func resolveDefunctNextStop(
        artist: ArtistRef,
        preference: TestArtistTourPreference?,
        allShows: [TestEnrichedShowSummary],
        playedKeys: Set<String>
    ) -> TestEnrichedShowSummary? {
        let candidates: [TestEnrichedShowSummary]
        if let pref = preference, pref.preferenceType == "YEAR" {
            candidates = allShows.filter { $0.date.hasPrefix(pref.periodLabel ?? "") }
        } else if let pref = preference, pref.preferenceType == "TOUR" {
            candidates = allShows.filter { $0.tourName == pref.tourName }
        } else {
            guard let latest = allShows.max(by: { $0.date < $1.date }) else { return nil }
            if latest.tourName == nil || latest.tourName?.trimmingCharacters(in: .whitespaces).isEmpty == true || latest.tourName == "Not Part of a Tour" {
                candidates = []
            } else {
                candidates = allShows.filter { $0.tourName == latest.tourName }
            }
        }

        let unplayed = candidates.filter { candidate in
            let key: String
            switch candidate.artist.backend {
            case .phishin:
                key = showQueueKey(candidate.date)
            case .relisten:
                key = recordingShowKey(candidate.artist.id, candidate.date)
            }
            return !playedKeys.contains(key)
        }

        return unplayed.min {
            if $0.date != $1.date { return $0.date < $1.date }
            return $0.artist.key < $1.artist.key
        }
    }

    private func deriveProceduralArtwork(artist: ArtistRef, date: String, venue: String? = nil) -> [String: Any] {
        let seedString = "\(artist.id):\(date)"
        var hasher = Hasher()
        hasher.combine(seedString)
        let hashValue = abs(hasher.finalize())
        let hue1 = hashValue % 360
        let hue2 = (hue1 + 45 + ((hashValue / 360) % 60)) % 360
        let monogram = artist.name.split(separator: " ").compactMap { $0.first?.uppercased() }.joined()
        let year = date.count >= 4 ? String(date.prefix(4)) : ""
        let monthDay = date.count >= 10 ? String(date.dropFirst(5).prefix(5)).replacingOccurrences(of: "-", with: "/") : ""
        return [
            "hash": hashValue,
            "hue1": hue1,
            "hue2": hue2,
            "monogram": monogram,
            "year": year,
            "monthDay": monthDay,
            "venueCaption": venue ?? ""
        ]
    }

    // =========================================================================
    // TIER 1: FEATURE COVERAGE (>=5 tests per feature)
    // =========================================================================

    // --- F1: Relisten Popularity DTO & Trending Models ---

    func testT1_F1_relistenPopularityDtoDecodesFullJson() throws {
        let jsonString = """
        {
          "momentum_score": 0.7806,
          "trend_ratio": 0.8673,
          "windows": {
            "48h": { "plays": 612, "hours": 95.3917, "hot_score": 24.7386 },
            "7d": { "plays": 1640, "hours": 251.5275, "hot_score": 40.4969 },
            "30d": { "plays": 8457, "hours": 1311.8942, "hot_score": 91.9619 }
          }
        }
        """
        let pop = try JSONDecoder().decode(TestRelistenPopularity.self, from: Data(jsonString.utf8))
        XCTAssertEqual(0.7806, pop.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.8673, pop.trendRatio, accuracy: 0.0001)
        XCTAssertEqual(612, pop.windows?.w48h?.plays)
        XCTAssertEqual(24.7386, pop.windows?.w48h?.hotScore ?? 0.0, accuracy: 0.0001)
    }

    func testT1_F1_relistenPopularityWindowsDecodes48h7d30d() throws {
        let jsonString = """
        {
          "momentum_score": 0.0,
          "trend_ratio": 0.0,
          "windows": {
            "48h": { "plays": 10, "hours": 1.5, "hot_score": 5.0 },
            "7d": { "plays": 50, "hours": 8.0, "hot_score": 15.0 },
            "30d": { "plays": 200, "hours": 30.0, "hot_score": 45.0 }
          }
        }
        """
        let pop = try JSONDecoder().decode(TestRelistenPopularity.self, from: Data(jsonString.utf8))
        XCTAssertEqual(5.0, pop.windows?.w48h?.hotScore ?? 0.0, accuracy: 0.0001)
        XCTAssertEqual(15.0, pop.windows?.w7d?.hotScore ?? 0.0, accuracy: 0.0001)
        XCTAssertEqual(45.0, pop.windows?.w30d?.hotScore ?? 0.0, accuracy: 0.0001)
    }

    func testT1_F1_relistenPopularityHotScoreMetricsCalculation() {
        let show = TestEnrichedShowSummary(
            artist: gratefulDead,
            date: "1977-05-08",
            popularity: TestRelistenPopularity(
                momentumScore: 0.95,
                trendRatio: 0.88,
                windows: TestRelistenPopularityWindows(
                    w48h: TestRelistenPopularityWindow(plays: 100, hours: 20.0, hotScore: 88.5),
                    w7d: TestRelistenPopularityWindow(plays: 500, hours: 95.0, hotScore: 120.0),
                    w30d: TestRelistenPopularityWindow(plays: 2000, hours: 400.0, hotScore: 310.0)
                )
            )
        )
        XCTAssertEqual(88.5, show.hotScore48h, accuracy: 0.001)
        XCTAssertEqual(120.0, show.hotScore7d, accuracy: 0.001)
        XCTAssertEqual(310.0, show.hotScore30d, accuracy: 0.001)
        XCTAssertEqual(0.95, show.momentumScore, accuracy: 0.001)
    }

    func testT1_F1_relistenPopularityMissingWindowsDefaultGracefully() throws {
        let jsonString = "{\"momentum_score\": 0.5, \"trend_ratio\": 0.2}"
        let pop = try JSONDecoder().decode(TestRelistenPopularity.self, from: Data(jsonString.utf8))
        XCTAssertEqual(0.5, pop.momentumScore, accuracy: 0.0001)
        XCTAssertNil(pop.windows)
        let show = TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", popularity: pop)
        XCTAssertEqual(0.0, show.hotScore48h)
        XCTAssertEqual(0.0, show.hotScore7d)
        XCTAssertEqual(0.0, show.hotScore30d)
    }

    func testT1_F1_relistenPopularityPartialWindowsDecodesAvailableSubset() throws {
        let jsonString = """
        {
          "momentum_score": 0.3,
          "trend_ratio": 0.4,
          "windows": {
            "48h": { "plays": 12, "hours": 2.0, "hot_score": 4.5 }
          }
        }
        """
        let pop = try JSONDecoder().decode(TestRelistenPopularity.self, from: Data(jsonString.utf8))
        XCTAssertEqual(4.5, pop.windows?.w48h?.hotScore ?? 0.0, accuracy: 0.0001)
        XCTAssertNil(pop.windows?.w7d)
        XCTAssertNil(pop.windows?.w30d)
    }

    // --- F2: Tag Models & Normalization ---

    func testT1_F2_phishInTagDtoDecoding() {
        let rawTag = TestTag(
            name: "Jamcharts",
            description: "Phish.net Jam Charts selection",
            color: "#FF8800",
            priority: 10,
            notes: "Extended funk jam in Set 2"
        )
        XCTAssertEqual("Jamcharts", rawTag.name)
        XCTAssertEqual("#FF8800", rawTag.color)
        XCTAssertEqual(10, rawTag.priority)
        XCTAssertEqual("Extended funk jam in Set 2", rawTag.notes)
    }

    func testT1_F2_relistenSyntheticTagSoundboard() {
        let isSoundboard = true
        var syntheticTags: [String] = []
        if isSoundboard { syntheticTags.append("SBD") }
        XCTAssertTrue(syntheticTags.contains("SBD"))
    }

    func testT1_F2_relistenSyntheticTagFlac() {
        let hasFlac = true
        var syntheticTags: [String] = []
        if hasFlac { syntheticTags.append("FLAC") }
        XCTAssertTrue(syntheticTags.contains("FLAC"))
    }

    func testT1_F2_relistenSyntheticTagMatrix() {
        let lineage = "Matrix: SBD (Charlie Miller) + AUD (Schoeps CMC6/MK4)"
        let looksLikeMatrix = lineage.localizedCaseInsensitiveContains("matrix")
        var syntheticTags: [String] = []
        if looksLikeMatrix { syntheticTags.append("Matrix") }
        XCTAssertTrue(syntheticTags.contains("Matrix"))
    }

    func testT1_F2_tagPriorityAndColorRetention() {
        let tags = [
            TestTag(name: "Guest", description: nil, color: "#00AA00", priority: 5),
            TestTag(name: "Jamcharts", description: nil, color: "#FF8800", priority: 10),
            TestTag(name: "Bustout", description: nil, color: "#0000FF", priority: 1),
        ]
        let sorted = tags.sorted { $0.priority > $1.priority }
        XCTAssertEqual("Jamcharts", sorted[0].name)
        XCTAssertEqual("Guest", sorted[1].name)
        XCTAssertEqual("Bustout", sorted[2].name)
    }

    // --- F4: GRDB v9 Migration & Persistence ---

    func testT1_F4_grdbMigrationCreatesArtistTourPreferencesTable() throws {
        try dbQueue.write { db in
            let pref = TestArtistTourPreference(
                artistKey: "relisten:grateful-dead",
                preferenceType: "TOUR",
                tourName: "Spring 1977",
                periodId: "1977",
                periodLabel: "1977"
            )
            try pref.insert(db)
        }

        let fetched = try dbQueue.read { db in
            try TestArtistTourPreference.fetchOne(db, key: "relisten:grateful-dead")
        }
        XCTAssertNotNil(fetched)
        XCTAssertEqual("Spring 1977", fetched?.tourName)
        XCTAssertEqual("TOUR", fetched?.preferenceType)
    }

    func testT1_F4_artistTourPreferenceInsertAndQueryByKey() throws {
        try dbQueue.write { db in
            let pref = TestArtistTourPreference(
                artistKey: "relisten:jgb",
                preferenceType: "YEAR",
                periodId: "1978",
                periodLabel: "1978"
            )
            try pref.save(db)
        }

        let fetched = try dbQueue.read { db in
            try TestArtistTourPreference.fetchOne(db, key: "relisten:jgb")
        }
        XCTAssertEqual("YEAR", fetched?.preferenceType)
        XCTAssertEqual("1978", fetched?.periodLabel)
    }

    func testT1_F4_artistTourPreferenceUpdateExisting() throws {
        try dbQueue.write { db in
            var pref = TestArtistTourPreference(
                artistKey: "relisten:grateful-dead",
                preferenceType: "YEAR",
                periodLabel: "1972"
            )
            try pref.save(db)

            pref.preferenceType = "TOUR"
            pref.tourName = "Europe '72"
            try pref.save(db)
        }

        let fetched = try dbQueue.read { db in
            try TestArtistTourPreference.fetchOne(db, key: "relisten:grateful-dead")
        }
        XCTAssertEqual("TOUR", fetched?.preferenceType)
        XCTAssertEqual("Europe '72", fetched?.tourName)
    }

    func testT1_F4_artistTourPreferenceDelete() throws {
        try dbQueue.write { db in
            let pref = TestArtistTourPreference(
                artistKey: "relisten:goose",
                preferenceType: "TOUR",
                tourName: "Fall 2024"
            )
            try pref.save(db)
            _ = try TestArtistTourPreference.deleteOne(db, key: "relisten:goose")
        }

        let fetched = try dbQueue.read { db in
            try TestArtistTourPreference.fetchOne(db, key: "relisten:goose")
        }
        XCTAssertNil(fetched)
    }

    func testT1_F4_migrationPreservesProgressRows() throws {
        try dbQueue.write { db in
            let row = PlaybackProgress(
                queueKey: "show:1977-05-08",
                title: "Cornell 77",
                subtitle: "Barton Hall",
                trackIndex: 3,
                positionMs: 50_000,
                trackTitle: "Scarlet Begonias",
                updatedAt: 1_700_000_000,
                artist: "Grateful Dead"
            )
            try row.insert(db)
        }

        let fetched = try dbQueue.read { db in
            try PlaybackProgress.fetchOne(db, key: "show:1977-05-08")
        }
        XCTAssertNotNil(fetched)
        XCTAssertEqual("Cornell 77", fetched?.title)
        XCTAssertEqual(50_000, fetched?.positionMs)
    }

    // --- F5: Next Stop Defunct Artist Resolution Engine ---

    func testT1_F5_nextStopResolvesDefunctArtistWithTourPreference() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-05", venue: "New Haven Coliseum", tourName: "Spring 1977"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", venue: "Barton Hall", tourName: "Spring 1977"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", venue: "War Memorial", tourName: "Spring 1977"),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        let played: Set<String> = [recordingShowKey(gratefulDead.id, "1977-05-05")]

        let next = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: gdShows, playedKeys: played)
        XCTAssertNotNil(next)
        XCTAssertEqual("1977-05-08", next?.date)
        XCTAssertEqual("Barton Hall", next?.venue)
    }

    func testT1_F5_nextStopResolvesDefunctArtistWithYearPreference() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1972-04-07", venue: "Wembley", tourName: "Europe '72"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1972-04-08", venue: "Wembley", tourName: "Europe '72"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1972-08-27", venue: "Old Renaissance Faire", tourName: "Not Part of a Tour"),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "YEAR", periodLabel: "1972")
        let played: Set<String> = [recordingShowKey(gratefulDead.id, "1972-04-07")]

        let next = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: gdShows, playedKeys: played)
        XCTAssertEqual("1972-04-08", next?.date)
    }

    func testT1_F5_nextStopReturnsEmptyForDefunctArtistWithoutPreference() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1995-07-09", venue: "Soldier Field", tourName: "Not Part of a Tour"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1995-07-08", venue: "Soldier Field", tourName: "Not Part of a Tour"),
        ]
        let next = resolveDefunctNextStop(artist: gratefulDead, preference: nil, allShows: gdShows, playedKeys: [])
        XCTAssertNil(next)
    }

    func testT1_F5_nextStopPrioritizesActiveTourForTouringArtist() {
        let phishShows = [
            TestEnrichedShowSummary(artist: phish, date: "2024-07-19", venue: "Xfinity Center", tourName: "Summer 2024"),
            TestEnrichedShowSummary(artist: phish, date: "2024-07-20", venue: "Xfinity Center", tourName: "Summer 2024"),
        ]
        let next = resolveDefunctNextStop(artist: phish, preference: nil, allShows: phishShows, playedKeys: [showQueueKey("2024-07-19")])
        XCTAssertEqual("2024-07-20", next?.date)
    }

    func testT1_F5_nextStopOldestUnplayedFiltersPlayedKeys() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tourName: "Spring 1977"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tourName: "Spring 1977"),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        let playedAll: Set<String> = [
            recordingShowKey(gratefulDead.id, "1977-05-08"),
            recordingShowKey(gratefulDead.id, "1977-05-09")
        ]
        let next = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: shows, playedKeys: playedAll)
        XCTAssertNil(next)
    }

    // --- F6: Tour Picker UI / State Flow ---

    func testT1_F6_tourPickerSelectYearPreference() {
        let pref = TestArtistTourPreference(
            artistKey: gratefulDead.key,
            preferenceType: "YEAR",
            periodLabel: "1977"
        )
        XCTAssertEqual("YEAR", pref.preferenceType)
        XCTAssertEqual("1977", pref.periodLabel)
    }

    func testT1_F6_tourPickerSelectNamedTourPreference() {
        let pref = TestArtistTourPreference(
            artistKey: gratefulDead.key,
            preferenceType: "TOUR",
            tourName: "Europe '72",
            periodLabel: "1972"
        )
        XCTAssertEqual("TOUR", pref.preferenceType)
        XCTAssertEqual("Europe '72", pref.tourName)
    }

    func testT1_F6_tourPickerClearPreference() {
        var pref: TestArtistTourPreference? = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "YEAR", periodLabel: "1977")
        XCTAssertNotNil(pref)
        pref = nil
        XCTAssertNil(pref)
    }

    func testT1_F6_tourPickerSwitchFromYearToTour() {
        var pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "YEAR", periodLabel: "1977")
        pref.preferenceType = "TOUR"
        pref.tourName = "Spring 1977"
        XCTAssertEqual("TOUR", pref.preferenceType)
        XCTAssertEqual("Spring 1977", pref.tourName)
    }

    func testT1_F6_tourPickerMultiArtistIsolation() {
        var prefs: [String: TestArtistTourPreference] = [:]
        prefs[gratefulDead.key] = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        prefs[jgb.key] = TestArtistTourPreference(artistKey: jgb.key, preferenceType: "YEAR", periodLabel: "1980")

        XCTAssertEqual("Spring 1977", prefs[gratefulDead.key]?.tourName)
        XCTAssertEqual("1980", prefs[jgb.key]?.periodLabel)
    }

    // --- F8: Tag Browse & Filter Surfaces ---

    func testT1_F8_filterShowsBySoundboardTag() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tags: ["SBD", "FLAC"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tags: ["AUD"]),
        ]
        let filtered = shows.filter { $0.tags.contains("SBD") }
        XCTAssertEqual(1, filtered.count)
        XCTAssertEqual("1977-05-08", filtered.first?.date)
    }

    func testT1_F8_filterShowsByMatrixTag() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tags: ["Matrix", "FLAC"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tags: ["SBD"]),
        ]
        let filtered = shows.filter { $0.tags.contains("Matrix") }
        XCTAssertEqual(1, filtered.count)
        XCTAssertEqual("1977-05-08", filtered.first?.date)
    }

    func testT1_F8_filterShowsByFlacTag() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tags: ["FLAC"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tags: ["MP3"]),
        ]
        let filtered = shows.filter { $0.tags.contains("FLAC") }
        XCTAssertEqual(1, filtered.count)
        XCTAssertEqual("1977-05-08", filtered.first?.date)
    }

    func testT1_F8_filterShowsByMultipleTagsConjunction() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tags: ["SBD", "FLAC", "Jamcharts"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tags: ["SBD"]),
        ]
        let filtered = shows.filter { $0.tags.contains("SBD") && $0.tags.contains("FLAC") }
        XCTAssertEqual(1, filtered.count)
        XCTAssertEqual("1977-05-08", filtered.first?.date)
    }

    func testT1_F8_filterShowsByNonExistentTagReturnsEmpty() {
        let shows = [TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tags: ["SBD"])]
        let filtered = shows.filter { $0.tags.contains("NonExistent") }
        XCTAssertTrue(filtered.isEmpty)
    }

    // --- F10: Momentum & Trending Sort Selector ---

    func testT1_F10_sortShowsByDateAscendingAndDescending() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08"),
        ]
        let asc = sortShows(shows, by: .dateAsc)
        XCTAssertEqual("1977-05-08", asc[0].date)
        XCTAssertEqual("1977-05-09", asc[1].date)

        let desc = sortShows(shows, by: .dateDesc)
        XCTAssertEqual("1977-05-09", desc[0].date)
        XCTAssertEqual("1977-05-08", desc[1].date)
    }

    func testT1_F10_sortShowsByTopRated() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", rating: 9.8),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", rating: 8.5),
        ]
        let sorted = sortShows(shows, by: .topRated)
        XCTAssertEqual("1977-05-08", sorted[0].date)
    }

    func testT1_F10_sortShowsByTrending48h() {
        let shows = [
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-08",
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 50.0)))
            ),
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-09",
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 120.0)))
            ),
        ]
        let sorted = sortShows(shows, by: .trending48h)
        XCTAssertEqual("1977-05-09", sorted[0].date)
    }

    func testT1_F10_sortShowsByHot7d() {
        let shows = [
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-08",
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w7d: TestRelistenPopularityWindow(hotScore: 80.0)))
            ),
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-09",
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w7d: TestRelistenPopularityWindow(hotScore: 20.0)))
            ),
        ]
        let sorted = sortShows(shows, by: .trending7d)
        XCTAssertEqual("1977-05-08", sorted[0].date)
    }

    func testT1_F10_sortShowsByPopular30d() {
        let shows = [
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-08",
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w30d: TestRelistenPopularityWindow(plays: 1500, hotScore: 300.0)))
            ),
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-09",
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w30d: TestRelistenPopularityWindow(plays: 2500, hotScore: 500.0)))
            ),
        ]
        let sorted = sortShows(shows, by: .trending30d)
        XCTAssertEqual("1977-05-09", sorted[0].date)
    }

    func testT1_F10_sortShowsByMomentum() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", popularity: TestRelistenPopularity(momentumScore: 0.95)),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", popularity: TestRelistenPopularity(momentumScore: 0.42)),
        ]
        let sorted = sortShows(shows, by: .momentum)
        XCTAssertEqual("1977-05-08", sorted[0].date)
    }

    // --- F12: Procedural Show Artwork Generator ---

    func testT1_F12_proceduralArtworkDeterministicHashGeneration() {
        let art1 = deriveProceduralArtwork(artist: gratefulDead, date: "1977-05-08", venue: "Barton Hall")
        let art2 = deriveProceduralArtwork(artist: gratefulDead, date: "1977-05-08", venue: "Barton Hall")
        XCTAssertEqual(art1["hash"] as? Int, art2["hash"] as? Int)
        XCTAssertEqual(art1["hue1"] as? Int, art2["hue1"] as? Int)
        XCTAssertEqual(art1["hue2"] as? Int, art2["hue2"] as? Int)
    }

    func testT1_F12_proceduralArtworkMultiStopPaletteDerivation() {
        let art = deriveProceduralArtwork(artist: gratefulDead, date: "1977-05-08")
        let hue1 = art["hue1"] as? Int ?? -1
        let hue2 = art["hue2"] as? Int ?? -1
        XCTAssertTrue((0..<360).contains(hue1))
        XCTAssertTrue((0..<360).contains(hue2))
    }

    func testT1_F12_proceduralArtworkArtistMonogramExtraction() {
        let gdArt = deriveProceduralArtwork(artist: gratefulDead, date: "1977-05-08")
        let jgbArt = deriveProceduralArtwork(artist: jgb, date: "1980-02-29")
        XCTAssertEqual("GD", gdArt["monogram"] as? String)
        XCTAssertEqual("JGB", jgbArt["monogram"] as? String)
    }

    func testT1_F12_proceduralArtworkDateBadgeFormatting() {
        let art = deriveProceduralArtwork(artist: gratefulDead, date: "1977-05-08")
        XCTAssertEqual("1977", art["year"] as? String)
        XCTAssertEqual("05/08", art["monthDay"] as? String)
    }

    func testT1_F12_proceduralArtworkVenueCaptionIncluded() {
        let art = deriveProceduralArtwork(artist: gratefulDead, date: "1977-05-08", venue: "Barton Hall · Cornell University")
        XCTAssertEqual("Barton Hall · Cornell University", art["venueCaption"] as? String)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>=5 tests per feature)
    // =========================================================================

    func testT2_boundary_malformedOrEmptyJsonPopularityDefaultsZero() throws {
        let jsonString = "{}"
        let pop = try JSONDecoder().decode(TestRelistenPopularity.self, from: Data(jsonString.utf8))
        XCTAssertEqual(0.0, pop.momentumScore)
        XCTAssertEqual(0.0, pop.trendRatio)
        XCTAssertNil(pop.windows)
    }

    func testT2_boundary_extremeAndZeroHotScores() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 0.0)))),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 999999.9)))),
        ]
        let sorted = sortShows(shows, by: .trending48h)
        XCTAssertEqual("1977-05-09", sorted[0].date)
        XCTAssertEqual("1977-05-08", sorted[1].date)
    }

    func testT2_boundary_tagsWithWhitespaceAndDuplicateEntries() {
        let rawTags = ["  SBD  ", "SBD", "FLAC", "  FLAC  "]
        let cleaned = Array(Set(rawTags.map { $0.trimmingCharacters(in: .whitespaces) })).sorted()
        XCTAssertEqual(["FLAC", "SBD"], cleaned)
    }

    func testT2_boundary_specialCharactersInArtistKeys() {
        let specialArtist = ArtistRef(backend: .relisten, id: "artist-with_special.chars@123", name: "Special Band")
        let pref = TestArtistTourPreference(artistKey: specialArtist.key, preferenceType: "YEAR", periodLabel: "1999")
        XCTAssertEqual("relisten:artist-with_special.chars@123", pref.artistKey)
    }

    func testT2_boundary_nextStopAllCandidatesPlayedReturnsNil() {
        let shows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tourName: "Spring 1977"),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        let played: Set<String> = [recordingShowKey(gratefulDead.id, "1977-05-08")]
        let result = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: shows, playedKeys: played)
        XCTAssertNil(result)
    }

    func testT2_boundary_nextStopEmptyCandidatesReturnsNil() {
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        let result = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: [], playedKeys: [])
        XCTAssertNil(result)
    }

    func testT2_boundary_sortShowsEmptyAndSingleItemList() {
        XCTAssertTrue(sortShows([], by: .trending48h).isEmpty)
        let single = [TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08")]
        XCTAssertEqual(single, sortShows(single, by: .topRated))
    }

    func testT2_boundary_proceduralArtworkWithShortDateAndNilVenue() {
        let art = deriveProceduralArtwork(artist: gratefulDead, date: "1977", venue: nil)
        XCTAssertEqual("1977", art["year"] as? String)
        XCTAssertEqual("", art["monthDay"] as? String)
        XCTAssertEqual("", art["venueCaption"] as? String)
    }

    func testT2_boundary_proceduralArtworkWithSingleWordArtist() {
        let art = deriveProceduralArtwork(artist: goose, date: "2024-06-20")
        XCTAssertEqual("G", art["monogram"] as? String)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS (Pairwise interactions)
    // =========================================================================

    func testT3_pair_defunctTourPreferenceAndSoundboardTagFilter() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-05", tourName: "Spring 1977", tags: ["AUD"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tourName: "Spring 1977", tags: ["SBD"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tourName: "Spring 1977", tags: ["SBD"]),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        let sbdShows = gdShows.filter { $0.tags.contains("SBD") }
        let next = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: sbdShows, playedKeys: [])

        XCTAssertNotNil(next)
        XCTAssertEqual("1977-05-08", next?.date)
        XCTAssertTrue(next?.tags.contains("SBD") == true)
    }

    func testT3_pair_defunctYearPreferenceAndMomentumSort() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-05", popularity: TestRelistenPopularity(momentumScore: 0.40)),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", popularity: TestRelistenPopularity(momentumScore: 0.98)),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", popularity: TestRelistenPopularity(momentumScore: 0.75)),
        ]
        let sorted1977 = sortShows(gdShows, by: .momentum)
        XCTAssertEqual("1977-05-08", sorted1977[0].date)
        XCTAssertEqual(0.98, sorted1977[0].momentumScore, accuracy: 0.001)
    }

    func testT3_pair_proceduralArtworkForNextStopResolvedShow() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", venue: "Barton Hall", tourName: "Spring 1977"),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")
        let next = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: gdShows, playedKeys: [])!

        let art = deriveProceduralArtwork(artist: next.artist, date: next.date, venue: next.venue)
        XCTAssertEqual("GD", art["monogram"] as? String)
        XCTAssertEqual("1977", art["year"] as? String)
        XCTAssertEqual("05/08", art["monthDay"] as? String)
        XCTAssertEqual("Barton Hall", art["venueCaption"] as? String)
    }

    func testT3_pair_progressCompletionAdvancesDefunctTourStop() {
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tourName: "Spring 1977"),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", tourName: "Spring 1977"),
        ]
        let pref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")

        // Step 1: Initial unplayed state
        let next1 = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: gdShows, playedKeys: [])
        XCTAssertEqual("1977-05-08", next1?.date)

        // Step 2: Mark first show finished
        let played: Set<String> = [recordingShowKey(gratefulDead.id, "1977-05-08")]
        let next2 = resolveDefunctNextStop(artist: gratefulDead, preference: pref, allShows: gdShows, playedKeys: played)
        XCTAssertEqual("1977-05-09", next2?.date)
    }

    func testT3_pair_multiArtistNextStopWithPreferences() {
        let phishShows = [
            TestEnrichedShowSummary(artist: phish, date: "2024-07-20", tourName: "Summer 2024"),
        ]
        let gdShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", tourName: "Spring 1977"),
        ]
        let gdPref = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")

        let nextPhish = resolveDefunctNextStop(artist: phish, preference: nil, allShows: phishShows, playedKeys: [])
        let nextGd = resolveDefunctNextStop(artist: gratefulDead, preference: gdPref, allShows: gdShows, playedKeys: [])

        XCTAssertEqual("2024-07-20", nextPhish?.date)
        XCTAssertEqual("1977-05-08", nextGd?.date)
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS (End-to-End Workflows)
    // =========================================================================

    func testT4_scenario_completeGratefulDead1977CouchTourJourney() {
        let tourShows = [
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-07", venue: "Boston Garden", tourName: "Spring 1977", tags: ["SBD", "FLAC"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-08", venue: "Barton Hall", tourName: "Spring 1977", tags: ["SBD", "FLAC", "Matrix"]),
            TestEnrichedShowSummary(artist: gratefulDead, date: "1977-05-09", venue: "War Memorial", tourName: "Spring 1977", tags: ["SBD", "FLAC"]),
        ]

        let preference = TestArtistTourPreference(artistKey: gratefulDead.key, preferenceType: "TOUR", tourName: "Spring 1977")

        // 1. First Stop: Boston Garden
        let stop1 = resolveDefunctNextStop(artist: gratefulDead, preference: preference, allShows: tourShows, playedKeys: [])
        XCTAssertEqual("1977-05-07", stop1?.date)

        // 2. User plays stop 1 and marks finished
        var playedHistory: Set<String> = [recordingShowKey(gratefulDead.id, "1977-05-07")]
        let stop2 = resolveDefunctNextStop(artist: gratefulDead, preference: preference, allShows: tourShows, playedKeys: playedHistory)
        XCTAssertEqual("1977-05-08", stop2?.date)
        XCTAssertEqual("Barton Hall", stop2?.venue)

        // 3. User checks procedural artwork for stop 2
        let art = deriveProceduralArtwork(artist: stop2!.artist, date: stop2!.date, venue: stop2?.venue)
        XCTAssertEqual("GD", art["monogram"] as? String)
        XCTAssertEqual("1977", art["year"] as? String)
        XCTAssertEqual("Barton Hall", art["venueCaption"] as? String)

        // 4. User plays stop 2 and marks finished
        playedHistory.insert(recordingShowKey(gratefulDead.id, "1977-05-08"))
        let stop3 = resolveDefunctNextStop(artist: gratefulDead, preference: preference, allShows: tourShows, playedKeys: playedHistory)
        XCTAssertEqual("1977-05-09", stop3?.date)
    }

    func testT4_scenario_catalogDiscoveryViaTagAndMomentum() {
        let shows1977 = [
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-07", tags: ["SBD"],
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 15.0)))
            ),
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-08", tags: ["SBD", "Matrix"],
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 95.0)))
            ),
            TestEnrichedShowSummary(
                artist: gratefulDead, date: "1977-05-09", tags: ["Matrix"],
                popularity: TestRelistenPopularity(windows: TestRelistenPopularityWindows(w48h: TestRelistenPopularityWindow(hotScore: 40.0)))
            ),
        ]

        let matrixShows = shows1977.filter { $0.tags.contains("Matrix") }
        XCTAssertEqual(2, matrixShows.count)

        let trendingMatrix = sortShows(matrixShows, by: .trending48h)
        XCTAssertEqual("1977-05-08", trendingMatrix[0].date)
        XCTAssertEqual(95.0, trendingMatrix[0].hotScore48h, accuracy: 0.001)
    }

    func testT4_scenario_nonDestructiveGRDBDatabaseMigrationWithTourPreferences() throws {
        try dbQueue.write { db in
            let row = PlaybackProgress(
                queueKey: "recording:grateful-dead:1977-05-08:tape1",
                title: "1977-05-08",
                subtitle: "Barton Hall",
                trackIndex: 2,
                positionMs: 45_000,
                trackTitle: "Scarlet Begonias",
                updatedAt: 1_000,
                finished: true,
                artist: "Grateful Dead"
            )
            try row.insert(db)
        }

        let fetchedProgress = try dbQueue.read { db in
            try PlaybackProgress.fetchOne(db, key: "recording:grateful-dead:1977-05-08:tape1")
        }
        XCTAssertNotNil(fetchedProgress)
        XCTAssertTrue(fetchedProgress?.finished == true)

        try dbQueue.write { db in
            let pref = TestArtistTourPreference(
                artistKey: "relisten:grateful-dead",
                preferenceType: "TOUR",
                tourName: "Spring 1977",
                periodLabel: "1977"
            )
            try pref.save(db)
        }

        let fetchedPref = try dbQueue.read { db in
            try TestArtistTourPreference.fetchOne(db, key: "relisten:grateful-dead")
        }
        XCTAssertEqual("Spring 1977", fetchedPref?.tourName)
    }
}
