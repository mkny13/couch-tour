import Foundation

/// The environment fields substituted into a feedback issue's body — device/OS values are
/// gathered by the caller (`Foundation`/`ProcessInfo` reads aren't available to a portable
/// package target in a form worth mocking) so this stays pure and testable. Mirrors Android's
/// `launchFeedback` (`FeedbackButton.kt`, #87, D180).
public struct FeedbackContext: Sendable {
    public let appVersion: String
    public let screen: String
    public let device: String
    public let osVersion: String
    public let channel: String

    public init(appVersion: String, screen: String, device: String, osVersion: String, channel: String) {
        self.appVersion = appVersion
        self.screen = screen
        self.device = device
        self.osVersion = osVersion
        self.channel = channel
    }
}

/// Longest diagnostics summary placed in the issue body, in characters.
public let feedbackDiagnosticsSummaryMaxChars = 1000
/// Browsers and GitHub start rejecting pre-filled URLs well past this; the issue asks for < 2500.
public let feedbackURLMaxLength = 2400

/// Builds a pre-filled GitHub new-issue URL for the Feedback button (#97). `URLComponents`
/// percent-encodes the title/body query items rather than string interpolation, so anything odd
/// in a screen name or device string can't corrupt the URL. A non-empty `diagnosticsSummary`
/// adds a `## Diagnostics` section (#436); it is truncated so the encoded URL stays under
/// `feedbackURLMaxLength` (percent-encoding can triple the length of newlines and punctuation).
public func feedbackIssueURL(context: FeedbackContext, diagnosticsSummary: String? = nil) -> URL? {
    var summary = String((diagnosticsSummary ?? "").prefix(feedbackDiagnosticsSummaryMaxChars))
    while true {
        let url = buildFeedbackURL(context: context, diagnosticsSummary: summary)
        if summary.isEmpty || (url?.absoluteString.count ?? 0) <= feedbackURLMaxLength { return url }
        summary = String(summary.prefix(max(0, summary.count - 50)))
    }
}

private func buildFeedbackURL(context: FeedbackContext, diagnosticsSummary: String) -> URL? {
    let title = "Feedback (Couch Tour \(context.appVersion))"
    var body = """
    ## Feedback
    [Describe your feedback, suggestion, or issue here]

    ---
    ## Environment
    - App Version: \(context.appVersion)
    - Screen: \(context.screen)
    - Device: \(context.device)
    - macOS: \(context.osVersion)
    - Channel: \(context.channel)
    """
    if !diagnosticsSummary.isEmpty {
        body += "\n\n## Diagnostics\n\(diagnosticsSummary)"
    }

    var components = URLComponents(string: "https://github.com/mkny13/couch-tour/issues/new")
    components?.queryItems = [
        // blank issues are disabled on the repo, so GitHub rejects a template-less pre-filled
        // URL with "Unable to create issue" (#295); the name must match a real file under
        // .github/ISSUE_TEMPLATE/.
        URLQueryItem(name: "template", value: "bug_report.md"),
        URLQueryItem(name: "title", value: title),
        URLQueryItem(name: "body", value: body),
    ]
    return components?.url
}
