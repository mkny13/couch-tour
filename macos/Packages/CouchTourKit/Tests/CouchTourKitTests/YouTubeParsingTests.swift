import XCTest
@testable import CouchTourKit

final class YouTubeParsingTests: XCTestCase {
    func testDecodesSearchFixtureEndToEnd() throws {
        let data = Fixture.load("youtube_search.json")
        let response = try JSONDecoder().decode(YouTubeSearchResponse.self, from: data)
        let videos = response.items.map { $0.toYouTubeVideo() }
        
        XCTAssertEqual(2, videos.count)
        
        // Assert the video with all fields
        let fullVideo = videos[0]
        XCTAssertEqual("dQw4w9WgXcQ", fullVideo.id)
        XCTAssertEqual("Rick Astley - Never Gonna Give You Up (Official Music Video)", fullVideo.title)
        XCTAssertEqual("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", fullVideo.thumbnailURL)
        XCTAssertNil(fullVideo.durationMs)
        XCTAssertEqual("2009-10-25T06:57:33Z", fullVideo.publishedAt)
        XCTAssertEqual("UC38IQsckIs700Z21h0A", fullVideo.channelId)
        XCTAssertEqual("The official video for “Never Gonna Give You Up” by Rick Astley", fullVideo.description)
        
        // Assert the video with missing fields
        let missingFieldsVideo = videos[1]
        XCTAssertEqual("missingFieldsVideo", missingFieldsVideo.id)
        XCTAssertEqual("A Video Missing Some Fields", missingFieldsVideo.title)
        XCTAssertNil(missingFieldsVideo.thumbnailURL)
        XCTAssertNil(missingFieldsVideo.durationMs)
        XCTAssertNil(missingFieldsVideo.publishedAt)
        XCTAssertEqual("UC38IQsckIs700Z21h0A", missingFieldsVideo.channelId)
        XCTAssertNil(missingFieldsVideo.description)
    }
}
