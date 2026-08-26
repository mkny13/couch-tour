import XCTest
@testable import CouchTourKit

final class TagTests: XCTestCase {

    private let decoder = JSONDecoder()

    private func fixture(_ name: String) throws -> Data { try fixtureData(name) }

    private let deadArtist = ArtistRef(
        backend: .relisten,
        id: "grateful-dead",
        name: "Grateful Dead",
        hasSets: false,
        hasMultipleSources: true
    )

    // ----------------------------------------------------------- Tag Model

    func testDecodesTagWithAllFields() throws {
        let json = """
        {
            "name": "Jamcharts",
            "description": "Phish.net Jam Charts selections",
            "color": "#888888",
            "priority": 4,
            "notes": "Incredible version with extended Type II jamming."
        }
        """.data(using: .utf8)!

        let tag = try decoder.decode(Tag.self, from: json)
        XCTAssertEqual("Jamcharts", tag.name)
        XCTAssertEqual("Jamcharts", tag.id)
        XCTAssertEqual("Phish.net Jam Charts selections", tag.description)
        XCTAssertEqual("#888888", tag.color)
        XCTAssertEqual(4, tag.priority)
        XCTAssertEqual("Incredible version with extended Type II jamming.", tag.notes)
    }

    func testDecodesMinimalTagWithDefaults() throws {
        let json = """
        {
            "name": "Soundboard"
        }
        """.data(using: .utf8)!

        let tag = try decoder.decode(Tag.self, from: json)
        XCTAssertEqual("Soundboard", tag.name)
        XCTAssertNil(tag.description)
        XCTAssertNil(tag.color)
        XCTAssertEqual(0, tag.priority)
        XCTAssertNil(tag.notes)
    }

    func testTagEqualityAndHashing() {
        let tag1 = Tag(name: "SBD", description: "Soundboard", priority: 10)
        let tag2 = Tag(name: "SBD", description: "Soundboard", priority: 10)
        let tag3 = Tag(name: "FLAC", description: "Lossless", priority: 5)

        XCTAssertEqual(tag1, tag2)
        XCTAssertNotEqual(tag1, tag3)

        var set: Set<Tag> = []
        set.insert(tag1)
        set.insert(tag2)
        set.insert(tag3)
        XCTAssertEqual(2, set.count)
    }

    // ----------------------------------------------------------- Phish.in Tags

    func testDecodesShowAndTrackTagsFromPhishInFixture() throws {
        let show = try decoder.decode(Show.self, from: try fixture("show.json"))

        // Show-level tags
        XCTAssertEqual(1, show.tags.count)
        let showTag = show.tags[0]
        XCTAssertEqual("Lore", showTag.name)
        XCTAssertEqual(22, showTag.priority)
        XCTAssertEqual("#808080", showTag.color)
        XCTAssertEqual("This show was officially released as Live Phish 11.", showTag.notes)

        // Track-level tags
        XCTAssertFalse(show.tracks.isEmpty)
        let tweezer = show.tracks[0]
        XCTAssertEqual(1, tweezer.tags.count)
        let trackTag = tweezer.tags[0]
        XCTAssertEqual("Jamcharts", trackTag.name)
        XCTAssertEqual(4, trackTag.priority)
        XCTAssertEqual("#888888", trackTag.color)
        XCTAssertTrue(trackTag.notes?.contains("fall 1997") == true)
    }

    func testPhishInMappingPropagatesTagsToDomainModels() throws {
        let show = try decoder.decode(Show.self, from: try fixture("show.json"))

        let summary = show.toShowSummary()
        XCTAssertEqual(1, summary.tags.count)
        XCTAssertEqual("Lore", summary.tags[0].name)

        let detail = show.toShowDetail()
        XCTAssertEqual(1, detail.tags.count)
        XCTAssertEqual("Lore", detail.tags[0].name)

        let tweezerTrack = try XCTUnwrap(detail.tracks.first { $0.title == "Tweezer" })
        XCTAssertEqual(1, tweezerTrack.tags.count)
        XCTAssertEqual("Jamcharts", tweezerTrack.tags[0].name)
    }

    func testDecodesSearchHitTagsFromSearchFixture() throws {
        let search = try decoder.decode(SearchResults.self, from: try fixture("search.json"))

        let trackWithTags = search.tracks.first { !$0.tags.isEmpty }
        XCTAssertNotNil(trackWithTags)
        XCTAssertTrue(trackWithTags?.tags.contains { $0.name == "Jamcharts" } == true)
    }

    // ----------------------------------------------------------- Relisten Synthetic Tags

    func testRelistenShowSummaryMapsSyntheticTags() {
        let sbdSummary = RelistenShowSummary(
            displayDate: "1977-05-08",
            sourceCount: 3,
            hasSoundboardSource: true,
            hasStreamableFlacSource: false
        ).toShowSummary(artist: deadArtist)

        XCTAssertEqual(["SBD"], sbdSummary.tags.map { $0.name })

        let flacSummary = RelistenShowSummary(
            displayDate: "1977-05-08",
            sourceCount: 3,
            hasSoundboardSource: false,
            hasStreamableFlacSource: true
        ).toShowSummary(artist: deadArtist)

        XCTAssertEqual(["FLAC"], flacSummary.tags.map { $0.name })

        let bothSummary = RelistenShowSummary(
            displayDate: "1977-05-08",
            sourceCount: 5,
            hasSoundboardSource: true,
            hasStreamableFlacSource: true
        ).toShowSummary(artist: deadArtist)

        XCTAssertEqual(["SBD", "FLAC"], bothSummary.tags.map { $0.name })
    }

    func testRecordingRefGeneratesSyntheticTags() {
        let sbd = RecordingRef(id: "1", label: "SBD", isSoundboard: true, hasFlac: false)
        XCTAssertEqual(["SBD"], sbd.tags.map { $0.name })

        let flac = RecordingRef(id: "2", label: "FLAC", isSoundboard: false, hasFlac: true)
        XCTAssertEqual(["FLAC"], flac.tags.map { $0.name })

        let matrix = RecordingRef(id: "3", label: "Matrix", isSoundboard: false, hasFlac: true, lineage: "SBD + AUD Matrix")
        XCTAssertEqual(["Matrix", "FLAC"], matrix.tags.map { $0.name })

        let allThree = RecordingRef(id: "4", label: "Master", isSoundboard: true, hasFlac: true, taper: "Matrix by Seamons")
        XCTAssertEqual(["SBD", "Matrix", "FLAC"], allThree.tags.map { $0.name })
    }

    func testRelistenShowWithSourcesPropagatesTagsToDetailAndTracks() throws {
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        let detail = show.toShowDetail(artist: deadArtist)

        // Show should have SBD, Matrix, and FLAC tags since sources include them
        let tagNames = detail.tags.map { $0.name }
        XCTAssertTrue(tagNames.contains("SBD"))
        XCTAssertTrue(tagNames.contains("FLAC"))

        // Also check summary carries the same tags
        XCTAssertEqual(detail.tags, detail.summary.tags)
    }

    // ----------------------------------------------------------- Tag Filtering Logic

    func testFilteringShowsByTag() {
        let s1 = ShowSummary(artist: PHISH, date: "1997-11-17", tags: [Tag(name: "Lore"), Tag(name: "SBD")])
        let s2 = ShowSummary(artist: PHISH, date: "1997-11-22", tags: [Tag(name: "Jamcharts")])
        let s3 = ShowSummary(artist: PHISH, date: "1997-12-30", tags: [Tag(name: "SBD"), Tag(name: "Jamcharts")])
        let allShows = [s1, s2, s3]

        let sbdShows = allShows.filter { show in show.tags.contains { $0.name == "SBD" } }
        XCTAssertEqual(["1997-11-17", "1997-12-30"], sbdShows.map { $0.date })

        let jamchartShows = allShows.filter { show in show.tags.contains { $0.name == "Jamcharts" } }
        XCTAssertEqual(["1997-11-22", "1997-12-30"], jamchartShows.map { $0.date })

        let loreShows = allShows.filter { show in show.tags.contains { $0.name == "Lore" } }
        XCTAssertEqual(["1997-11-17"], loreShows.map { $0.date })
    }

    func testFilterShowsByTagHelperFunction() {
        let s1 = ShowSummary(artist: PHISH, date: "1997-11-17", tags: [Tag(name: "Lore"), Tag(name: "SBD")])
        let s2 = ShowSummary(artist: PHISH, date: "1997-11-22", tags: [Tag(name: "Jamcharts")])
        let s3 = ShowSummary(artist: PHISH, date: "1997-12-30", tags: [Tag(name: "SBD"), Tag(name: "Jamcharts")])
        let allShows = [s1, s2, s3]

        // Exact match
        XCTAssertEqual(["1997-11-17", "1997-12-30"], filterShowsByTag(allShows, tagName: "SBD").map { $0.date })
        // Case-insensitive
        XCTAssertEqual(["1997-11-17", "1997-12-30"], allShows.filterByTag("sbd").map { $0.date })
        XCTAssertEqual(["1997-11-22", "1997-12-30"], allShows.filterByTag("JAMCHARTS").map { $0.date })
        // "All" returns everything
        XCTAssertEqual(3, allShows.filterByTag("All").count)
        XCTAssertEqual(3, allShows.filterByTag("all").count)
        XCTAssertEqual(3, allShows.filterByTag("").count)
        XCTAssertEqual(3, allShows.filterByTag("   ").count)
        // Non-existent tag
        XCTAssertTrue(allShows.filterByTag("NonExistent").isEmpty)
    }

    func testFilterTracksByTagHelperFunction() {
        let t1 = PlayableTrack(id: "1", title: "Tweezer", url: "https://example.com/1.mp3", tags: [Tag(name: "Jamcharts")])
        let t2 = PlayableTrack(id: "2", title: "Prince Caspian", url: "https://example.com/2.mp3", tags: [])
        let t3 = PlayableTrack(id: "3", title: "Ghost", url: "https://example.com/3.mp3", tags: [Tag(name: "Jamcharts"), Tag(name: "SBD")])
        let tracks = [t1, t2, t3]

        XCTAssertEqual(["Tweezer", "Ghost"], filterTracksByTag(tracks, tagName: "Jamcharts").map { $0.title })
        XCTAssertEqual(["Tweezer", "Ghost"], tracks.filterByTag("jamcharts").map { $0.title })
        XCTAssertEqual(["Ghost"], tracks.filterByTag("SBD").map { $0.title })
        XCTAssertEqual(3, tracks.filterByTag("All").count)
        XCTAssertEqual(3, tracks.filterByTag("").count)
        XCTAssertTrue(tracks.filterByTag("FLAC").isEmpty)
    }

    func testShowSummarySortedByOption() {
        let s1 = ShowSummary(artist: PHISH, date: "1997-11-17", rating: 8.0)
        let s2 = ShowSummary(artist: PHISH, date: "1997-11-22", rating: 9.5)
        let s3 = ShowSummary(artist: PHISH, date: "1997-12-30", rating: 9.0)
        let shows = [s1, s2, s3]

        let sortedRating = shows.sorted(by: .ratingDesc)
        XCTAssertEqual(["1997-11-22", "1997-12-30", "1997-11-17"], sortedRating.map { $0.date })

        let sortedDateAsc = shows.sorted(by: .dateAsc)
        XCTAssertEqual(["1997-11-17", "1997-11-22", "1997-12-30"], sortedDateAsc.map { $0.date })
    }
}
