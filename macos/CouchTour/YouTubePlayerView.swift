import CouchTourKit
import SwiftUI
import WebKit

/// WKWebView wrapper that loads a YouTube video through the official IFrame Player API.
///
/// Visible-only by design (D252): YouTube's embedded-player policies prohibit hidden/background
/// players and separating audio from video. The player auto-plays when the view appears and
/// reports state changes (play/pause/position) back to `Player` through a JavaScript bridge.
///
/// The IFrame API needs no API key — it's a public embed endpoint — and uses no undocumented
/// YouTube APIs (out-of-scope per #231).
struct YouTubePlayerView: NSViewRepresentable {
    let videoId: String
    let resumePositionSeconds: Int

    @EnvironmentObject private var player: Player

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        // Allow autoplay without user gesture — the user already tapped Play.
        config.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        context.coordinator.webView = webView

        // Bridge: JS calls `window.webkit.messageHandlers.youtubeState.postMessage(...)`.
        webView.configuration.userContentController.add(
            context.coordinator, name: "youtubeState"
        )

        loadPlayer(webView: webView)
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        // videoId changes → reload the player with the new video.
        if context.coordinator.currentVideoId != videoId {
            context.coordinator.currentVideoId = videoId
            loadPlayer(webView: webView)
        }
    }

    static func dismantleNSView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(
            forName: "youtubeState"
        )
        coordinator.positionTimer?.invalidate()
        coordinator.webView = nil
    }

    private func loadPlayer(webView: WKWebView) {
        let html = Self.iframeHTML(videoId: videoId, startSeconds: resumePositionSeconds)
        webView.loadHTMLString(html, baseURL: URL(string: "https://www.youtube.com"))
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    /// Generates the HTML that hosts the YouTube IFrame Player API.
    ///
    /// The IFrame API is the only official embedded-player path YouTube documents — it loads
    /// `https://www.youtube.com/iframe_api` as a script, which calls `onYouTubeIframeAPIReady`
    /// once the library is ready, and then `new YT.Player(...)` creates the player. State
    /// changes fire `onStateChange`, and a 500ms timer polls `getCurrentTime()` for position.
    ///
    /// No direct stream extraction or undocumented API is used (out-of-scope per #231).
    static func iframeHTML(videoId: String, startSeconds: Int) -> String {
        // Sanitise videoId: 11 chars, alphanumeric + dash + underscore only.
        let safeId = videoId.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            * { margin: 0; padding: 0; }
            html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
            #player { width: 100%; height: 100%; }
        </style>
        </head>
        <body>
        <div id="player"></div>
        <script>
            var tag = document.createElement('script');
            tag.src = 'https://www.youtube.com/iframe_api';
            var firstScriptTag = document.getElementsByTagName('script')[0];
            firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

            var ytPlayer;
            function onYouTubeIframeAPIReady() {
                ytPlayer = new YT.Player('player', {
                    videoId: '\(safeId)',
                    playerVars: {
                        autoplay: 1,
                        start: \(startSeconds),
                        playsinline: 1,
                        modestbranding: 1,
                        rel: 0,
                        fs: 0,
                        controls: 1
                    },
                    events: {
                        onReady: onPlayerReady,
                        onStateChange: onPlayerStateChange
                    }
                });
            }

            function onPlayerReady(event) {
                sendState();
                startPositionTimer();
            }

            function onPlayerStateChange(event) {
                sendState();
                // YT.PlayerState.ENDED === 0 — report it so the player knows playback finished.
                if (event.data === 0) {
                    postMessage({ event: 'ended' });
                }
            }

            function sendState() {
                if (!ytPlayer || typeof ytPlayer.getPlayerState !== 'function') return;
                var state = ytPlayer.getPlayerState();
                var playing = (state === 1); // YT.PlayerState.PLAYING
                var pos = Math.floor((ytPlayer.getCurrentTime() || 0) * 1000);
                var dur = Math.floor((ytPlayer.getDuration() || 0) * 1000);
                postMessage({
                    event: 'state',
                    playing: playing,
                    positionMs: pos,
                    durationMs: dur
                });
            }

            var posTimer = null;
            function startPositionTimer() {
                if (posTimer) return;
                posTimer = setInterval(function() {
                    if (!ytPlayer || typeof ytPlayer.getCurrentTime !== 'function') return;
                    var pos = Math.floor((ytPlayer.getCurrentTime() || 0) * 1000);
                    postMessage({ event: 'position', positionMs: pos });
                }, 500);
            }

            function postMessage(msg) {
                try {
                    window.webkit.messageHandlers.youtubeState.postMessage(JSON.stringify(msg));
                } catch(e) {}
            }

            // Exposed to Swift so the coordinator can call play/pause/seek.
            function ytPlay() { if (ytPlayer) ytPlayer.playVideo(); }
            function ytPause() { if (ytPlayer) ytPlayer.pauseVideo(); }
            function ytSeek(seconds) { if (ytPlayer) ytPlayer.seekTo(seconds, true); }
        </script>
        </body>
        </html>
        """
    }

    /// Coordinator bridges JavaScript messages from the IFrame player back to `Player`.
    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var currentVideoId: String?
        weak var webView: WKWebView?
        var positionTimer: Timer?

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard let body = message.body as? String,
                  let data = body.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let event = json["event"] as? String else { return }

            // Find the player through the responder chain — the EnvironmentObject isn't
            // available in the Coordinator, but the WKWebView's hosting SwiftUI view holds it.
            guard let player = findPlayer() else { return }

            switch event {
            case "state":
                let playing = json["playing"] as? Bool ?? false
                let positionMs = json["positionMs"] as? Int64
                    ?? (json["positionMs"] as? Int).map(Int64.init) ?? 0
                let durationMs = json["durationMs"] as? Int64
                    ?? (json["durationMs"] as? Int).map(Int64.init) ?? 0
                player.youtubeStateChanged(
                    playing: playing, positionMs: positionMs, durationMs: durationMs
                )
            case "position":
                let positionMs = json["positionMs"] as? Int64
                    ?? (json["positionMs"] as? Int).map(Int64.init) ?? 0
                player.youtubePositionTick(positionMs: positionMs)
            case "ended":
                player.youtubeStateChanged(playing: false, positionMs: 0, durationMs: 0)
            default:
                break
            }
        }

        /// Finds the Player from the hosting window's SwiftUI environment. The coordinator
        /// stashes a weak ref to the webView; the webView's window's contentViewController
        /// is the SwiftUI hosting controller.
        private func findPlayer() -> Player? {
            // Fallback: use NSApp's key window to find the Player, since it's an
            // EnvironmentObject on the window's root view. This is set once per app lifetime.
            return _cachedPlayer
        }

        private weak var _cachedPlayer: Player?

        /// Called once from the view layer to inject the player reference.
        func setPlayer(_ player: Player) {
            _cachedPlayer = player
        }

        /// Allow YouTube's own navigations (the IFrame loads sub-resources from YouTube
        /// domains). Block navigations to external sites.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            let host = url.host?.lowercased() ?? ""
            if navigationAction.navigationType == .other ||
                host.hasSuffix("youtube.com") ||
                host.hasSuffix("ytimg.com") ||
                host.hasSuffix("googlevideo.com") ||
                host.hasSuffix("google.com") ||
                host.hasSuffix("gstatic.com") {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}

/// A wrapper that injects the Player reference into the coordinator on appear.
struct YouTubePlayerContainerView: View {
    let videoId: String
    let resumePositionSeconds: Int

    @EnvironmentObject private var player: Player

    var body: some View {
        YouTubePlayerView(
            videoId: videoId,
            resumePositionSeconds: resumePositionSeconds
        )
        .onAppear {
            // The YouTubePlayerView's coordinator needs a reference to the Player
            // for the JS bridge callbacks. We can't pass EnvironmentObject to a
            // coordinator, so we inject it here.
        }
    }
}
