/// Whether the player is currently streaming audio (AVQueuePlayer / Cast) or showing
/// a visible YouTube embedded video (WKWebView / IFrame Player API).
///
/// Lives in CouchTourKit rather than the app target because progress-save and resume
/// logic needs to branch on it — and that branching is testable here via `swift test`.
/// The actual WKWebView surface stays in the app target (not testable by CI, tracked
/// through UAT instead — D208, #231).
public enum PlaybackMode: Equatable, Sendable {
    /// Standard audio playback via AVQueuePlayer (phish.in / Relisten tracks) or Cast.
    case audio
    /// Visible-only YouTube video via the IFrame Player API (D252). No background /
    /// audio-only mode — YouTube's embedded-player policies prohibit hidden players and
    /// separating audio from video.
    case youtubeVideo
}
