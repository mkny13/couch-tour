import XCTest
@testable import CouchTourKit

/// Decodes real `search.list` payloads (Fixtures/youtube_search.json) and the defensive
/// cases around them — missing optionals must degrade to nil, never throw.
final class YouTubeParsingTests: XCTestCase {
    func testDecodesSearchFixtureEndToEnd() throws {
        let data = try fixtureData("youtube_search.json")
        let response = try JSONDecoder().decode(YouTubeSearchResponse.self, from: data)

        XCTAssertEqual(2, response.items.count)

        let full = try XCTUnwrap(response.items.first)
        XCTAssertEqual("dQw4w9WgXcQ", full.videoId)
        XCTAssertEqual("Rick Astley - Never Gonna Give You Up (Official Music Video)", full.title)
        XCTAssertEqual(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", full.thumbnailURL,
            "prefers the largest thumbnail size the payload carries")
        XCTAssertEqual("UCuAXFkgsw1L7xaCfnd5JJOw", full.channelId)
        XCTAssertNotNil(full.description)

        let video = full.toYouTubeVideo()
        XCTAssertEqual("dQw4w9WgXcQ", video.id)
        XCTAssertEqual("Rick Astley - Never Gonna Give You Up (Official Music Video)", video.title)
        XCTAssertEqual("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", video.thumbnailURL)
        XCTAssertEqual("UCuAXFkgsw1L7xaCfnd5JJOw", video.channelId)
        // search.list carries no contentDetails — duration only arrives via #231's videos.list.
        XCTAssertNil(video.durationMs)
        XCTAssertEqual(
            ISO8601DateFormatter().date(from: "2009-10-25T06:57:33Z"), video.publishedAt)
        XCTAssertNotNil(video.description)
    }

    func testMapsMissingOptionalSnippetFieldsToNilWithoutThrowing() throws {
        let data = try fixtureData("youtube_search.json")
        let response = try JSONDecoder().decode(YouTubeSearchResponse.self, from: data)

        let video = try XCTUnwrap(response.items.last).toYouTubeVideo()

        XCTAssertEqual("aBcDeFgHiJk", video.id)
        XCTAssertEqual("A Video Missing Some Fields", video.title)
        XCTAssertNil(video.thumbnailURL)
        XCTAssertNil(video.publishedAt)
        XCTAssertNil(video.description)
        XCTAssertEqual("UCuAXFkgsw1L7xaCfnd5JJOw", video.channelId, "channelId falls back to the snippet's own")
    }

    func testMalformedPublishedAtDegradesToNilInsteadOfFailingTheListing() throws {
        let payload = #"""
        {"items":[{"id":{"videoId":"x"},"snippet":{"title":"T","publishedAt":"not-a-date"}},
                  {"id":{},"snippet":{"title":"T2","publishedAt":"2009-10-25T06:57:33Z"}}]}
        """#
        let response = try JSONDecoder().decode(
            YouTubeSearchResponse.self, from: Data(payload.utf8))

        XCTAssertEqual("T", response.items.first?.title)
        XCTAssertNil(response.items.first?.publishedAtDate, "unparseable timestamp → nil, not a throw")
        XCTAssertNotNil(response.items.last?.publishedAtDate)
    }

    func testMissingItemsKeyDecodesToAnEmptyList() throws {
        let response = try JSONDecoder().decode(
            YouTubeSearchResponse.self, from: Data(#"{"pageInfo":{"totalResults":0}}"#.utf8))
        XCTAssertEqual([], response.items)
    }
}
