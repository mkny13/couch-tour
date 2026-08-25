import AppKit
import AVFoundation
import Combine
import CouchTourKit
import MediaPlayer

/// Wraps `AVQueuePlayer` over one show's filtered track list. This is app-target code, not
/// `CouchTourKit` — like `PlaybackService.kt` on Android, it's inherently coupled to a
/// platform media framework rather than portable logic (see the plan's M1/M2/M3 split).
///
/// `AVQueuePlayer` only ever advances forward through its queue and drains to empty at the
/// end — there is no "next show" to roll into because only one show's tracks are ever loaded,
/// so stopping at the encore (D13) is what happens by default, not something coded for.
///
/// Progress writing lives here rather than in the UI, the same call Android's
/// `PlaybackService` makes — the player itself is what knows when a track actually changed or
/// the queue actually drained (D20), not whichever screen happened to trigger playback.
private let volumeDefaultsKey = "playerVolume"

@MainActor
final class Player: NSObject, ObservableObject {
    @Published private(set) var show: ShowSummary?
    @Published private(set) var tracks: [PlayableTrack] = []
    @Published private(set) var queueKey: String?
    @Published private(set) var currentIndex: Int?
    @Published private(set) var isPlaying = false
    /// Milliseconds into the current track.
    @Published private(set) var positionMs: Int64 = 0
    @Published private(set) var artURL: String?
    @Published private(set) var postShowPrompt: ShowSummary?

    /// App-level volume (0...1), independent of system volume — persisted so it survives a
    /// relaunch. `didSet` doesn't fire for the `init` assignment, so `init` also sets
    /// `queuePlayer.volume` directly.
    @Published var volume: Float = UserDefaults.standard.object(forKey: volumeDefaultsKey) as? Float ?? 1.0 {
        didSet {
            queuePlayer.volume = volume
            UserDefaults.standard.set(volume, forKey: volumeDefaultsKey)
        }
    }
    private var volumeBeforeMute: Float?

    func toggleMute() {
        if let volumeBeforeMute {
            volume = volumeBeforeMute
            self.volumeBeforeMute = nil
        } else {
            volumeBeforeMute = volume
            volume = 0
        }
    }

    var currentTrack: PlayableTrack? {
        guard let currentIndex, tracks.indices.contains(currentIndex) else { return nil }
        return tracks[currentIndex]
    }

    private let queuePlayer = AVQueuePlayer()
    private let recorder: ProgressRecorder
    private let progressStore: ProgressStore?
    private let syncSession: SyncSession?
    private let playbackSettings: PlaybackSettings?
    /// The `NSImage` currently cached for `artURL`, and the URL it belongs to — so a late
    /// asynchronous load can be dropped if the show has changed by the time it finishes.
    private var artworkImage: NSImage?
    private var artworkLoadURL: String?

    /// Indexed identically to `tracks`, even though only a suffix of it is ever inserted into
    /// `queuePlayer` (playback starts mid-show when a track other than the first is tapped) —
    /// this is what lets `currentItemDidChange` map an `AVPlayerItem` back to a `tracks` index.
    private var items: [AVPlayerItem] = []
    /// A stored position to seek to once the first item of a resumed queue is actually ready
    /// to play — seeking before that is silently ignored by AVFoundation.
    private var pendingResumeMs: Int64?

    private var timeObserverToken: Any?
    private var currentItemObservation: NSKeyValueObservation?
    private var rateObservation: NSKeyValueObservation?
    private var itemStatusObservation: NSKeyValueObservation?
    /// Bare-Space play/pause. Not a SwiftUI `keyboardShortcut` — see `SpacePlaybackHotkey`.
    private var spaceKeyMonitor: Any?

    init(progressStore: ProgressStore?, syncSession: SyncSession?, playbackSettings: PlaybackSettings? = nil) {
        recorder = ProgressRecorder(store: progressStore)
        self.progressStore = progressStore
        self.syncSession = syncSession
        self.playbackSettings = playbackSettings
        super.init()
        queuePlayer.volume = volume
        configureRemoteCommands()
        configureSpaceKeyMonitor()
        observePlayer()
    }

    deinit {
        if let spaceKeyMonitor {
            NSEvent.removeMonitor(spaceKeyMonitor)
        }
    }

    /// Starts a queue-key-bearing show or recording. `resumePositionMs`, when non-zero, is
    /// applied once the starting track is actually ready — see `pendingResumeMs`.
    func play(detail: ShowDetail, startIndex: Int = 0, resumePositionMs: Int64 = 0) {
        show = detail.summary
        queueKey = detail.queueKey
        // Same fallback Android's recordingTrackItems uses: the show's own art, else the
        // first track's, since a Relisten show summary often carries no art of its own.
        artURL = detail.summary.artURL ?? detail.tracks.first?.artURL
        loadArtwork(for: artURL)
        startQueue(tracks: detail.tracks, startIndex: startIndex, resumePositionMs: resumePositionMs)
    }

    /// Fetches and caches the `NSImage` for the system Now Playing widget (D107). Guards
    /// against a stale load landing after the show has already changed again.
    private func loadArtwork(for urlString: String?) {
        // Cleared synchronously rather than left stale until the new fetch resolves — otherwise
        // switching shows could briefly attach the previous show's art to the new track's
        // Now Playing metadata.
        artworkImage = nil
        guard let urlString, let url = URL(string: urlString) else {
            artworkLoadURL = nil
            return
        }
        artworkLoadURL = urlString
        Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let image = NSImage(data: data) else { return }
            await MainActor.run {
                guard let self, self.artworkLoadURL == urlString else { return }
                self.artworkImage = image
                self.updateNowPlayingInfo()
            }
        }
    }

    func togglePlayPause() {
        if queuePlayer.rate == 0 {
            queuePlayer.play()
        } else {
            queuePlayer.pause()
        }
    }

    func skipToNext() {
        queuePlayer.advanceToNextItem()
    }

    /// `AVQueuePlayer` has no notion of "previous" — it only ever advances. Going back means
    /// rebuilding the queue from one track earlier, the same path `seek(toTrack:)` uses.
    func skipToPrevious() {
        guard let currentIndex, currentIndex > 0 else { return }
        seek(toTrack: currentIndex - 1)
    }

    func seek(toTrack index: Int) {
        startQueue(tracks: tracks, startIndex: index)
    }

    func seek(toMs ms: Int64) {
        queuePlayer.seek(to: CMTime(value: ms, timescale: 1000))
        positionMs = ms
        updateNowPlayingElapsedTime()
        saveProgress(force: true)
    }

    private func startQueue(tracks: [PlayableTrack], startIndex: Int, resumePositionMs: Int64 = 0) {
        guard tracks.indices.contains(startIndex) else { return }
        let filtered = filterPlaybackTracks(
            tracks: tracks,
            startIndex: startIndex,
            skipFiller: playbackSettings?.skipFiller ?? false
        )
        self.tracks = filtered.tracks
        postShowPrompt = nil
        queuePlayer.removeAllItems()
        items = filtered.tracks.map { track in
            let playURL = (track.flacUrl?.isEmpty == false) ? track.flacUrl! : track.url
            return AVPlayerItem(url: URL(string: playURL) ?? URL(fileURLWithPath: "/dev/null"))
        }
        for item in items[filtered.startIndex...] {
            queuePlayer.insert(item, after: nil)
        }
        currentIndex = filtered.startIndex
        positionMs = 0
        pendingResumeMs = resumePositionMs > 0 ? resumePositionMs : nil
        observeCurrentItemReadyForResume()
        queuePlayer.play()
        updateNowPlayingInfo()
        // Force .playing before the rate KVO fires — media keys stay with Spotify
        // if we claim the Now Playing slot while still .paused (D179).
        claimNowPlaying(playing: true)
        saveProgress(force: true)
    }

    // MARK: - Player observation

    private func observePlayer() {
        currentItemObservation = queuePlayer.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.currentItemDidChange() }
        }
        rateObservation = queuePlayer.observe(\.rate, options: [.new]) { [weak self] _, change in
            Task { @MainActor in
                self?.isPlaying = (change.newValue ?? 0) > 0
                self?.updateNowPlayingElapsedTime()
                // Re-claim on every transition to playing — Spotify will have taken
                // the session if anything played there while we were paused.
                if (change.newValue ?? 0) > 0 { self?.claimNowPlaying(playing: true) }
                self?.saveProgress(force: true)
            }
        }
        timeObserverToken = queuePlayer.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            guard let self, time.isValid, !time.isIndefinite else { return }
            self.positionMs = Int64(time.seconds * 1000)
            self.saveProgress(force: false)
        }
    }

    private func currentItemDidChange() {
        guard let item = queuePlayer.currentItem,
              let index = items.firstIndex(where: { $0 === item }) else {
            // The queue drained — the show finished. Nothing to advance into (D13). The
            // stopping point stays exactly as last saved; only the flag changes (D20).
            recorder.markFinished(queueKey: queueKey)
            currentIndex = nil
            isPlaying = false
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            MPNowPlayingInfoCenter.default().playbackState = .stopped
            if let finishedShow = self.show {
                Task { @MainActor [weak self] in
                    let nextStop = await findNextTourStop(
                        artist: finishedShow.artist,
                        currentDate: finishedShow.date,
                        tourName: finishedShow.tourName
                    )
                    if let self, self.currentIndex == nil {
                        self.postShowPrompt = nextStop
                    }
                }
            }
            return
        }
        currentIndex = index
        positionMs = 0
        updateNowPlayingInfo()
        saveProgress(force: true)
    }

    func dismissPostShowPrompt() {
        postShowPrompt = nil
    }

    func playNextTourStop(_ show: ShowSummary) {
        dismissPostShowPrompt()
        Task { @MainActor in
            guard let detail = try? await sourceFor(show.artist.backend).show(artist: show.artist, date: show.date, recordingId: nil) else { return }
            self.play(detail: detail)
        }
    }

    /// Applies `pendingResumeMs` the moment the just-started item is actually ready — seeking
    /// any earlier is silently dropped by AVFoundation.
    private func observeCurrentItemReadyForResume() {
        guard let item = queuePlayer.currentItem else { return }
        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            guard observedItem.status == .readyToPlay else { return }
            Task { @MainActor in
                guard let self, let ms = self.pendingResumeMs else { return }
                self.pendingResumeMs = nil
                self.seek(toMs: ms)
            }
        }
    }

    private func saveProgress(force: Bool) {
        recorder.saveTick(
            queueKey: queueKey, show: show, track: currentTrack, trackIndex: currentIndex,
            positionMs: positionMs, artURL: artURL, force: force
        )
        // Only on the same events that bypass the local 5s throttle — the periodic tick
        // shouldn't also be resetting a sync debounce every half-second.
        if force, let syncSession, let progressStore {
            syncSession.requestDebouncedPush(progressStore)
        }
    }

    // MARK: - Now Playing / media keys

    private func configureSpaceKeyMonitor() {
        spaceKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard SpacePlaybackHotkey.shouldHandle(event, firstResponder: NSApp.keyWindow?.firstResponder)
            else { return event }
            // Held Space would otherwise toggle on every repeat tick.
            if !event.isARepeat {
                Task { @MainActor in self?.togglePlayPause() }
            }
            return nil
        }
    }

    /// Publish `playbackState` on the shared Now Playing center. macOS does not
    /// infer this from AVPlayer (iOS does), and the keyboard play/pause key follows
    /// whichever app last set `.playing` — leaving it unset is why Spotify kept
    /// the key while we were the ones making sound (D179).
    private func claimNowPlaying(playing: Bool? = nil) {
        let center = MPNowPlayingInfoCenter.default()
        if let playing {
            center.playbackState = playing ? .playing : .paused
        } else {
            applyPlaybackState()
        }
    }

    private func applyPlaybackState() {
        let center = MPNowPlayingInfoCenter.default()
        if currentTrack == nil {
            center.playbackState = .stopped
        } else if queuePlayer.rate > 0 {
            center.playbackState = .playing
        } else {
            center.playbackState = .paused
        }
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.queuePlayer.play() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.queuePlayer.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skipToNext() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skipToPrevious() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(toMs: Int64(event.positionTime * 1000)) }
            return .success
        }
    }

    /// Full metadata — called on track change. Title/artist/album mirror the Android
    /// MediaSession exactly (MediaItems.kt's `coreMediaItem`/`albumFor`, D50): album is the
    /// show the track was actually played at, which is what lets an external scrobbler like
    /// the Last.fm app work from this MediaPlayer info alone.
    private func updateNowPlayingInfo() {
        guard let track = currentTrack, let show else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            MPNowPlayingInfoCenter.default().playbackState = .stopped
            return
        }
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = track.title
        info[MPMediaItemPropertyArtist] = show.artist.name
        info[MPMediaItemPropertyAlbumTitle] = albumTitle(for: track, show: show)
        info[MPMediaItemPropertyPlaybackDuration] = Double(track.durationMs) / 1000
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        // Rebuilt from a stored image rather than fetched here — this method reconstructs the
        // whole dictionary on every track change, and the async fetch in loadArtwork(for:) may
        // not have completed yet when that happens.
        if let artworkImage {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: artworkImage.size) { _ in artworkImage }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        applyPlaybackState()
    }

    /// The lightweight per-tick update — only the elapsed-time key changes while a track
    /// plays, so this avoids rebuilding the whole dictionary every 0.5s.
    private func updateNowPlayingElapsedTime() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        applyPlaybackState()
    }

    /// "1997-11-17 · McNichols Arena", falling back to the show summary when a track carries
    /// no show info of its own — port of `albumFor` in Android's MediaItems.kt.
    private func albumTitle(for track: PlayableTrack, show: ShowSummary) -> String {
        let fromTrack = [track.showDate, track.venueName].compactMap { $0 }.joined(separator: " · ")
        if !fromTrack.isEmpty { return fromTrack }
        return "\(show.date) · \(show.where_)"
    }
}
