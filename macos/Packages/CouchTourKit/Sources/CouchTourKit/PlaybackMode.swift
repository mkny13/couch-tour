import Foundation

/// Whether the player is streaming audio (AVQueuePlayer / Cast) or showing a visible
/// YouTube embedded video (WKWebView / IFrame Player API).
///
/// Visible-only by decision (D256): YouTube's embedded-player policies prohibit
/// hidden/background players and prohibit separating audio from video, so there is no
/// audio-only mode to toggle to — the "toggle" from the original #16 request is dropped
/// along with background playback.
///
/// Lives in CouchTourKit rather than the app target because progress-save and resume
/// logic branch on it, and that branching is testable here via `swift test`. The actual
/// WKWebView surface stays in the app target, which CI does not build (D208) — that is
/// tracked through UAT instead (#231).
public enum PlaybackMode: Equatable, Sendable {
    /// Standard audio playback via AVQueuePlayer (phish.in / Relisten tracks) or Cast.
    case audio
    /// Visible-only YouTube video via the IFrame Player API (D256). The embed is always
    /// on screen — collapsing it out of view is not supported.
    case youtubeVideo
}

/// Which mode a queue kind plays back in. Audio kinds all share `.audio`; only the
/// YouTube namespace has its own player surface. Kept as a free function over `QueueKind`
/// so callers never hand-roll the switch (and tests can pin it).
public func playbackMode(for kind: QueueKind) -> PlaybackMode {
    switch kind {
    case .youtube: return .youtubeVideo
    case .show, .playlist, .recording, .localPlaylist: return .audio
    }
}