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

    func testURLCarriesTheBugReportTemplate() throws {
        // The repo disables blank issues (#295): without a template parameter GitHub rejects
        // the pre-filled URL with "Unable to create issue".
        let url = try XCTUnwrap(feedbackIssueURL(context: context))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let template = components.queryItems?.first { $0.name == "template" }?.value
        XCTAssertEqual("bug_report.md", template)
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

    private func body(of url: URL?) throws -> String {
        let url = try XCTUnwrap(url)
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        return try XCTUnwrap(components.queryItems?.first { $0.name == "body" }?.value)
    }

    func testDiagnosticsSummaryAddsASection() throws {
        let body = try body(of: feedbackIssueURL(context: context, diagnosticsSummary: "Entries: 12\nSize: 900 bytes"))
        XCTAssertTrue(body.contains("## Diagnostics\nEntries: 12\nSize: 900 bytes"))
    }

    func testNoSummaryOmitsTheDiagnosticsSection() throws {
        let without = try body(of: feedbackIssueURL(context: context))
        XCTAssertFalse(without.contains("## Diagnostics"))
        XCTAssertFalse(try body(of: feedbackIssueURL(context: context, diagnosticsSummary: "")).contains("## Diagnostics"))
        XCTAssertEqual(without, try body(of: feedbackIssueURL(context: context, diagnosticsSummary: nil)))
    }

    func testHugeSummaryIsCappedAndURLStaysUnderLimit() throws {
        // Newlines and punctuation percent-encode to 3 chars each — the worst case for URL length.
        let huge = String(repeating: "k: v\n", count: 2000)
        let url = try XCTUnwrap(feedbackIssueURL(context: context, diagnosticsSummary: huge))
        XCTAssertLessThan(url.absoluteString.count, 2500)
        let body = try body(of: url)
        XCTAssertTrue(body.contains("## Diagnostics"))
        let summary = try XCTUnwrap(body.components(separatedBy: "## Diagnostics\n").last)
        XCTAssertLessThanOrEqual(summary.count, feedbackDiagnosticsSummaryMaxChars)
    }
}
