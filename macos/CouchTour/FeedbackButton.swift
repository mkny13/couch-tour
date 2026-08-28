import AppKit
import CouchTourKit
import SwiftUI

/// macOS parity with Android's Feedback launcher (#87, D180) — a quiet button that opens the
/// browser straight to a pre-filled GitHub new-issue form. No in-app compose UI, no API token;
/// `feedbackIssueURL` (CouchTourKit/Feedback.swift) builds the URL, this just gathers the
/// desktop-specific environment fields Android's `Build`/`BuildConfig` don't have a macOS
/// equivalent of.
struct FeedbackButton: View {
    /// The breadcrumb's leaf (RootView.swift). `SidebarSection` used to supply this and went
    /// away with the sidebar (D203) — no loss: "1997-11-17" or "Grateful Dead" tells you far
    /// more about where a report came from than "Artists" ever did.
    let currentScreen: String

    var body: some View {
        Button {
            NSWorkspace.shared.open(feedbackURL)
        } label: {
            Image(systemName: "questionmark.bubble")
        }
        .buttonStyle(.borderless)
        .help("Send Feedback")
    }

    private var feedbackURL: URL {
        let context = FeedbackContext(
            appVersion: Bundle.main.appMarketingVersion,
            screen: currentScreen,
            device: hardwareModel(),
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            channel: buildChannel
        )
        // feedbackIssueURL only returns nil if the base GitHub URL itself fails to parse, which
        // it can't — it's a fixed literal — so falling back to that same literal is unreachable
        // in practice, not a real error path worth surfacing to the user.
        return feedbackIssueURL(context: context)
            ?? URL(string: "https://github.com/mkny13/couch-tour/issues/new")!
    }

    private var buildChannel: String {
        #if BETA
        "Beta"
        #else
        "Production"
        #endif
    }

    private func hardwareModel() -> String {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        guard size > 0 else { return "Unknown Mac" }
        var buffer = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &buffer, &size, nil, 0)
        return String(cString: buffer)
    }
}
