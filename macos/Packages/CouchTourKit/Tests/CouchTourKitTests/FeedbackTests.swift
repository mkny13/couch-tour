import XCTest
@testable import CouchTourKit

final class FeedbackTests: XCTestCase {

    private let context = FeedbackContext(
        appVersion: "0.57-beta",
        screen: "Home",
        device: "Mac15,6",
        osVersion: "15.1",
        channel: "Beta"
    )

    func testTitleFollowsAndroidsFeedbackVersionShape() throws {
        let url = try XCTUnwrap(feedbackIssueURL(context: context))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let title = components.queryItems?.first { $0.name == "title" }?.value
        XCTAssertEqual("Feedback (Couch Tour 0.57-beta)", title)
    }

    func testBodyIncludesEveryEnvironmentField() throws {
        let url = try XCTUnwrap(feedbackIssueURL(context: context))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let body = try XCTUnwrap(components.queryItems?.first { $0.name == "body" }?.value)
        XCTAssertTrue(body.contains("App Version: 0.57-beta"))
        XCTAssertTrue(body.contains("Screen: Home"))
        XCTAssertTrue(body.contains("Device: Mac15,6"))
        XCTAssertTrue(body.contains("macOS: 15.1"))
        XCTAssertTrue(body.contains("Channel: Beta"))
    }

    func testURLPointsAtTheNewIssueEndpoint() {
        let url = feedbackIssueURL(context: context)
        XCTAssertEqual("https://github.com/mkny13/couch-tour/issues/new", url?.absoluteString.components(separatedBy: "?").first)
    }

    func testOddCharactersInFieldsAreProperlyEncodedRatherThanCorruptingTheURL() throws {
        let odd = FeedbackContext(
            appVersion: "0.57-beta",
            screen: "Home & Away?",
            device: "Mac15,6",
            osVersion: "15.1",
            channel: "Beta"
        )
        let url = try XCTUnwrap(feedbackIssueURL(context: odd))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let body = try XCTUnwrap(components.queryItems?.first { $0.name == "body" }?.value)
        XCTAssertTrue(body.contains("Screen: Home & Away?"))
    }
}
