import AppKit
import AVFoundation
import Combine
import CouchTourKit
import MediaPlayer

/// Wraps `AVQueuePlayer` and remote Google Cast client over one show's filtered track list.
/// Like `PlaybackService.kt` on Android, it coordinates platform media players, progress recording,
/// and remote Cast / AirPlay sender integrations.
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

    // MARK: - Cast & Remote Routing State
    @Published private(set) var isCasting = false
    @Published private(set) var castDeviceName: String?
    public let castDiscovery = CastDiscovery()
    public let castClient = CastClient()

    /// App-level volume (0...1), independent of system volume — persisted so it survives a
    /// relaunch. `didSet` doesn't fire for the `init` assignment, so `init` also sets
    /// `queuePlayer.volume` directly.
    @Published var volume: Float = UserDefaults.standard.object(forKey: volumeDefaultsKey) as? Float ?? 1.0 {
        didSet {
            queuePlayer.volume = volume
            if isCasting {
                castClient.setVolume(Double(volume))
            }
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
        configureCastClient()
    }

    deinit {
        if let spaceKeyMonitor {
            NSEvent.removeMonitor(spaceKeyMonitor)
        }
    }

    // MARK: - Cast Integration

    private func configureCastClient() {
        castClient.onPositionTick = { [weak self] pos in
            guard let self, self.isCasting else { return }
            self.positionMs = pos
            self.saveProgress(force: false)
            self.updateNowPlayingElapsedTime()
        }

        castClient.onPlaybackStateChanged = { [weak self] playing in
            guard let self, self.isCasting else { return }
            self.isPlaying = playing
            self.updateNowPlayingElapsedTime()
            self.claimNowPlaying(playing: playing)
            self.saveProgress(force: true)
        }

        castClient.onTrackFinished = { [weak self] in
            guard let self, self.isCasting else { return }
            self.handleRemoteTrackFinished()
        }
    }

    public func connectCast(to device: CastDevice) {
        isCasting = true
        castDeviceName = device.name
        queuePlayer.pause()

        castClient.connect(to: device)
        if let track = currentTrack {
            castClient.load(
                track: track,
                show: show,
                queueKey: queueKey,
                resumePositionMs: positionMs
            )
            castClient.setVolume(Double(volume))
        }
        updateNowPlayingInfo()
    }

    public func disconnectCast() {
        guard isCasting else { return }
        let currentPos = positionMs
        let wasPlaying = isPlaying

        castClient.disconnect()
        isCasting = false
        castDeviceName = nil

        // D62: Coming back from the TV lands paused
        isPlaying = false
        if let currentTrack, let currentIndex {
            startQueue(tracks: tracks, startIndex: currentIndex, resumePositionMs: currentPos)
            queuePlayer.pause()
        }
        updateNowPlayingInfo()
        saveProgress(force: true)
    }

    private func handleRemoteTrackFinished() {
        guard let currentIndex else { return }
        let nextIndex = currentIndex + 1
        if tracks.indices.contains(nextIndex) {
            seek(toTrack: nextIndex)
        } else {
            // Show finished
            recorder.markFinished(queueKey: queueKey)
            self.currentIndex = nil
            self.isPlaying = false
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
        }
    }

    // MARK: - Queue Playback

    /// Starts a queue-key-bearing show or recording. `resumePositionMs`, when non-zero, is
    /// applied once the starting track is actually ready — see `pendingResumeMs`.
    func play(detail: ShowDetail, startIndex: Int = 0, resumePositionMs: Int64 = 0) {
        show = detail.summary
        queueKey = detail.queueKey
        artURL = detail.summary.artURL ?? detail.tracks.first?.artURL
        loadArtwork(for: artURL)

        let filtered = filterPlaybackTracks(
            tracks: detail.tracks,
            startIndex: startIndex,
            skipFiller: playbackSettings?.skipFiller ?? false
        )
        self.tracks = filtered.tracks
        self.currentIndex = filtered.startIndex
        postShowPrompt = nil

        if isCasting, let track = currentTrack {
            positionMs = resumePositionMs
            castClient.load(
                track: track,
                show: show,
                queueKey: queueKey,
                resumePositionMs: resumePositionMs
            )
            updateNowPlayingInfo()
            claimNowPlaying(playing: true)
            saveProgress(force: true)
        } else {
            startQueue(tracks: detail.tracks, startIndex: startIndex, resumePositionMs: resumePositionMs)
        }
    }

    private func loadArtwork(for urlString: String?) {
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
        if isCasting {
            if isPlaying {
                castClient.pause()
            } else {
                castClient.play()
            }
        } else {
            if queuePlayer.rate == 0 {
                queuePlayer.play()
            } else {
                queuePlayer.pause()
            }
        }
    }

    func skipToNext() {
        if isCasting {
            guard let currentIndex, currentIndex < tracks.count - 1 else { return }
            seek(toTrack: currentIndex + 1)
        } else {
            queuePlayer.advanceToNextItem()
        }
    }

    func skipToPrevious() {
        guard let currentIndex, currentIndex > 0 else { return }
        seek(toTrack: currentIndex - 1)
    }

    func seek(toTrack index: Int) {
        guard tracks.indices.contains(index) else { return }
        if isCasting {
            currentIndex = index
            positionMs = 0
            if let track = currentTrack {
                castClient.load(track: track, show: show, queueKey: queueKey, resumePositionMs: 0)
            }
            updateNowPlayingInfo()
            saveProgress(force: true)
        } else {
            startQueue(tracks: tracks, startIndex: index)
        }
    }

    func seek(toMs ms: Int64) {
        positionMs = ms
        if isCasting {
            castClient.seek(toMs: ms)
        } else {
            queuePlayer.seek(to: CMTime(value: ms, timescale: 1000))
        }
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
                guard let self, !self.isCasting else { return }
                self.isPlaying = (change.newValue ?? 0) > 0
                self.updateNowPlayingElapsedTime()
                if (change.newValue ?? 0) > 0 { self.claimNowPlaying(playing: true) }
                self.saveProgress(force: true)
            }
        }
        timeObserverToken = queuePlayer.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            guard let self, !self.isCasting, time.isValid, !time.isIndefinite else { return }
            self.positionMs = Int64(time.seconds * 1000)
            // AVQueuePlayer keeps firing this observer on its interval even while paused —
            // without this guard, a show left loaded-but-paused (e.g. overnight) got its local
            // progress row re-stamped with a fresh updatedAt every ~5s for no real change. The
            // next sync then saw that row as "changed" and pushed the stale position, clobbering
            // whatever a second device had actually advanced to (last-write-wins). Mirrors
            // Android's `if (active.isPlaying) saveNow()` gate in PlaybackService.kt.
            if self.isPlaying {
                self.saveProgress(force: false)
            }
        }
    }

    private func currentItemDidChange() {
        guard !isCasting else { return }
        guard let item = queuePlayer.currentItem,
              let index = items.firstIndex(where: { $0 === item }) else {
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
        if force, let syncSession, let progressStore {
            syncSession.requestDebouncedPush(progressStore)
        }
    }

    // MARK: - Now Playing / media keys

    private func configureSpaceKeyMonitor() {
        spaceKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard SpacePlaybackHotkey.shouldHandle(event, firstResponder: NSApp.keyWindow?.firstResponder)
            else { return event }
            if !event.isARepeat {
                Task { @MainActor in self?.togglePlayPause() }
            }
            return nil
        }
    }

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
        } else if isPlaying {
            center.playbackState = .playing
        } else {
            center.playbackState = .paused
        }
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if self?.isCasting == true {
                    self?.castClient.play()
                } else {
                    self?.queuePlayer.play()
                }
            }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if self?.isCasting == true {
                    self?.castClient.pause()
                } else {
                    self?.queuePlayer.pause()
                }
            }
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
        if let artworkImage {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: artworkImage.size) { _ in artworkImage }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        applyPlaybackState()
    }

    private func updateNowPlayingElapsedTime() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        applyPlaybackState()
    }

    private func albumTitle(for track: PlayableTrack, show: ShowSummary) -> String {
        let fromTrack = [track.showDate, track.venueName].compactMap { $0 }.joined(separator: " · ")
        if !fromTrack.isEmpty { return fromTrack }
        return "\(show.date) · \(show.where_)"
    }
}
