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

/// Builds a pre-filled GitHub new-issue URL for the Feedback button (#97). `URLComponents`
/// percent-encodes the title/body query items rather than string interpolation, so anything odd
/// in a screen name or device string can't corrupt the URL.
public func feedbackIssueURL(context: FeedbackContext) -> URL? {
    let title = "Feedback (Couch Tour \(context.appVersion))"
    let body = """
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

    var components = URLComponents(string: "https://github.com/mkny13/couch-tour/issues/new")
    components?.queryItems = [
        URLQueryItem(name: "title", value: title),
        URLQueryItem(name: "body", value: body),
    ]
    return components?.url
}
