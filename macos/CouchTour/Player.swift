import AppKit
import AVFoundation
import Combine
import CouchTourKit
import MediaPlayer
import MediaToolbox

/// Wraps `AVQueuePlayer` and remote Google Cast client over one show's filtered track list.
/// Like `PlaybackService.kt` on Android, it coordinates platform media players, progress recording,
/// and remote Cast / AirPlay sender integrations.
///
/// Progress writing lives here rather than in the UI, the same call Android's
/// `PlaybackService` makes — the player itself is what knows when a track actually changed or
/// the queue actually drained (D20), not whichever screen happened to trigger playback.
private let volumeDefaultsKey = "playerVolume"

// MARK: - Volume leveling tap (#268)
//
// The playback half of the leveling pipeline: a constant-gain `MTAudioProcessingTap`
// attached to each queue item's audio track. The measurer (in CouchTourKit) hands back a
// `SourceLoudness`; `Player` turns it into a gain with the shared `levelingGainDb` rule and
// pokes it into the tap storage — no re-seek, no queue rebuild, mid-track if necessary.

/// The tap's mutable state, shared between the main actor (writes) and the realtime audio
/// thread (reads). A non-atomic Float is deliberate: the callback reads one word, so a
/// torn read is impossible on every supported architecture and a one-callback-stale value
/// around a gain change is inaudible.
final class GainTapStorage {
    var gain: Float
    init(gain: Float) { self.gain = gain }
}

// C function pointers — they can't capture, so `clientInfo` (the storage) carries the state.

private let gainTapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
    // Balances the passRetained handed to `clientInfo` at create time.
    Unmanaged<GainTapStorage>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeRetainedValue()
}

private let gainTapProcess: MTAudioProcessingTapProcessCallback = { tap, _, _, ioBufferList, _, _ in
    let storage = Unmanaged<GainTapStorage>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    let gain = storage.gain
    guard gain != 1.0 else { return }
    for buffer in UnsafeMutableAudioBufferListPointer(ioBufferList) {
        guard let data = buffer.mData else { continue }
        let floats = data.assumingMemoryBound(to: Float.self)
        let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
        for i in 0..<count { floats[i] *= gain }
    }
}

@MainActor
final class Player: NSObject, ObservableObject {
    @Published private(set) var show: ShowSummary?
    @Published private(set) var recording: RecordingRef?
    @Published private(set) var tracks: [PlayableTrack] = []
    @Published private(set) var queueKey: String?
    @Published private(set) var currentIndex: Int?
    @Published private(set) var isPlaying = false
    /// Milliseconds into the current track.
    @Published private(set) var positionMs: Int64 = 0
    @Published private(set) var artURL: String?
    @Published private(set) var postShowPrompt: ShowSummary?
    /// The YouTube video the user tapped in the artist page, nil when nothing YouTube is
    /// loaded. Set by `playYoutube`; the WKWebView playback surface #231 builds consumes it.
    @Published private(set) var youtubeVideo: YouTubeVideo?

    // MARK: - Comparison Mode State
    @Published private(set) var alternates: [RecordingRef] = []
    @Published private(set) var isComparingSources = false
    @Published private(set) var comparisonSources: [RecordingRef] = []
    private var comparisonPlayers: [String: AVPlayer] = [:]
    @Published private(set) var activeComparisonSourceId: String? = nil

    struct QueueState {
        let tracks: [PlayableTrack]
        let currentIndex: Int?
        let positionMs: Int64
        let isPlaying: Bool
        let queueKey: String?
        let recording: RecordingRef?
    }
    private var originalQueueState: QueueState?

    var hasRealAlternates: Bool {
        guard let show else { return false }
        return show.artist.hasMultipleSources && !alternates.isEmpty
    }


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

    // MARK: - Volume leveling (#268)

    /// Background measurement pipeline (30s decode-ahead slices → BS.1770 → `progress` cache).
    /// Only created when a `ProgressStore` exists: without the cache there is nowhere to
    /// record a measurement, and re-measuring every queue from scratch would be pure waste.
    private var loudnessMeasurer: LoudnessMeasurer?
    private var levelingTask: Task<Void, Never>?
    private var levelVolumeObservation: AnyCancellable?
    /// Linear gain held for taps that attach after a measurement lands.
    private var pendingLevelingGainLinear: Float = 1.0
    /// Per-queue-item tap gain storage, keyed by item identity.
    private var gainStorageByItem: [ObjectIdentifier: GainTapStorage] = [:]
    /// Bare-Space play/pause. Not a SwiftUI `keyboardShortcut` — see `SpacePlaybackHotkey`.
    private var spaceKeyMonitor: Any?

    init(progressStore: ProgressStore?, syncSession: SyncSession?, playbackSettings: PlaybackSettings? = nil) {
        recorder = ProgressRecorder(store: progressStore)
        self.progressStore = progressStore
        self.syncSession = syncSession
        self.playbackSettings = playbackSettings
        if let progressStore {
            loudnessMeasurer = LoudnessMeasurer(cache: progressStore)
        }
        super.init()
        levelVolumeObservation = playbackSettings?.$levelVolume.sink { [weak self] enabled in
            self?.levelVolumeDidChange(enabled)
        }
        queuePlayer.volume = volume
        configureRemoteCommands()
        configureSpaceKeyMonitor()
        observePlayer()
        configureCastClient()
    }

    deinit {
        if let timeObserverToken {
            queuePlayer.removeTimeObserver(timeObserverToken)
        }
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
        // Gain decisions are meaningless while the receiver decodes — don't let a
        // in-flight measurement land into the taps mid-cast (#268).
        levelingTask?.cancel()
        levelingTask = nil

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

    // MARK: - Compare Sources

    func enterCompareSourcesMode(sources: [RecordingRef], currentTrack: PlayableTrack, positionMs: Int64) {
        guard let show = show else { return }

        // Store current queue state
        originalQueueState = QueueState(
            tracks: tracks,
            currentIndex: currentIndex,
            positionMs: positionMs,
            isPlaying: isPlaying,
            queueKey: queueKey,
            recording: recording
        )

        let wasPlaying = isPlaying
        if wasPlaying {
            if isCasting {
                castClient.pause()
            } else {
                queuePlayer.pause()
            }
        }

        comparisonSources = sources
        isComparingSources = true
        let currentVolume = volume

        Task {
            var players: [String: AVPlayer] = [:]
            await withTaskGroup(of: (String, AVPlayer?).self) { group in
                for source in sources {
                    group.addTask {
                        let sourceAPI = sourceFor(show.artist.backend)
                        guard let detail = try? await sourceAPI.show(artist: show.artist, date: show.date, recordingId: source.id) else {
                            return (source.id, nil)
                        }

                        let match = detail.tracks.first { track in
                            track.title == currentTrack.title || track.position == currentTrack.position
                        }
                        guard let match = match else { return (source.id, nil) }

                        let playURL = (match.flacUrl?.isEmpty == false) ? match.flacUrl! : match.url
                        let validURL = playURL.lowercased().hasPrefix("https://") ? playURL : "https://invalid.local/blocked"
                        let item = AVPlayerItem(url: URL(string: validURL) ?? URL(string: "https://invalid.local/blocked")!)
                        let player = AVPlayer(playerItem: item)
                        player.volume = currentVolume
                        return (source.id, player)
                    }
                }

                for await (id, player) in group {
                    if let player = player {
                        players[id] = player
                    }
                }
            }

            await MainActor.run {
                self.comparisonPlayers = players
                let time = CMTime(value: positionMs, timescale: 1000)
                for (_, player) in players {
                    player.seek(to: time)
                }
                
                if let first = sources.first?.id, let firstPlayer = players[first] {
                    self.activeComparisonSourceId = first
                    firstPlayer.play()
                }
            }
        }
    }

    func switchComparisonSource(to recordingId: String) {
        guard isComparingSources, activeComparisonSourceId != recordingId else { return }
        
        let oldPlayer = activeComparisonSourceId.flatMap { comparisonPlayers[$0] }
        let newPlayer = comparisonPlayers[recordingId]
        
        oldPlayer?.pause()
        
        let currentTime = oldPlayer?.currentTime() ?? CMTime(value: positionMs, timescale: 1000)
        
        newPlayer?.seek(to: currentTime)
        newPlayer?.play()
        
        activeComparisonSourceId = recordingId
    }

    func exitCompareSourcesMode(confirmSelection: Bool) {
        guard let state = originalQueueState else { return }
        
        let chosenRecordingId = activeComparisonSourceId
        let wasPlaying = state.isPlaying
        let positionMsToRestore: Int64
        
        if let activeId = activeComparisonSourceId, let activePlayer = comparisonPlayers[activeId] {
            positionMsToRestore = Int64(activePlayer.currentTime().seconds * 1000)
        } else {
            positionMsToRestore = state.positionMs
        }
        
        for player in comparisonPlayers.values {
            player.pause()
        }
        comparisonPlayers.removeAll()
        comparisonSources.removeAll()
        isComparingSources = false
        activeComparisonSourceId = nil
        originalQueueState = nil
        
        if confirmSelection, let chosenId = chosenRecordingId, chosenId != state.recording?.id {
            guard let show = show else { return }
            Task {
                let sourceAPI = sourceFor(show.artist.backend)
                guard let detail = try? await sourceAPI.show(artist: show.artist, date: show.date, recordingId: chosenId) else {
                    self.seek(toMs: positionMsToRestore)
                    if wasPlaying {
                        if self.isCasting { self.castClient.play() } else { self.queuePlayer.play() }
                    }
                    return
                }
                
                let matchIndex = detail.tracks.firstIndex { 
                    $0.title == self.currentTrack?.title || $0.position == self.currentTrack?.position
                } ?? state.currentIndex ?? 0
                
                self.play(detail: detail, startIndex: matchIndex, resumePositionMs: positionMsToRestore)
            }
        } else {
            self.seek(toMs: positionMsToRestore)
            if wasPlaying {
                if isCasting {
                    castClient.play()
                } else {
                    queuePlayer.play()
                }
            }
        }
    }

    // MARK: - Queue Playback

    /// Starts a queue-key-bearing show or recording. `resumePositionMs`, when non-zero, is
    /// applied once the starting track is actually ready — see `pendingResumeMs`.
    func play(detail: ShowDetail, startIndex: Int = 0, resumePositionMs: Int64 = 0) {
        show = detail.summary
        recording = detail.recording
        queueKey = detail.queueKey
        alternates = detail.alternates
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

    /// Routes a YouTube video tap from the artist page into the player (#230). Playback
    /// itself is #231 (a WKWebView IFrame surface); this stub only defines the route
    /// contract: any in-flight show/track queue is torn down, the video's thumbnail becomes
    /// the artwork, and the video is published for #231's surface to consume. Callers open
    /// the Now Playing inspector (`appModel.showNowPlaying = true`), the same seam every
    /// other play tap uses.
    func playYoutube(video: YouTubeVideo) {
        stopAudio()
        // State cleared before the queue teardown so the KVO handler sees a nil show and
        // skips the post-show prompt — leaving show playback, not finishing it.
        show = nil
        recording = nil
        queueKey = nil
        postShowPrompt = nil
        youtubeVideo = video
        artURL = video.thumbnailURL
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
    }

    /// Tears the local queue down without the show-finished side effects of a drained
    /// queue — used when leaving show playback for a different media kind.
    private func stopAudio() {
        queuePlayer.pause()
        queuePlayer.removeAllItems()
        gainStorageByItem.removeAll()
        levelingTask?.cancel()
        levelingTask = nil
        pendingLevelingGainLinear = 1.0
        items.removeAll()
        tracks = []
        currentIndex = nil
        isPlaying = false
        positionMs = 0
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
        // New queue → new items → new taps. Drop the old per-item gain state first.
        gainStorageByItem.removeAll()
        levelingTask?.cancel()
        levelingTask = nil
        items = filtered.tracks.map { track in
            let playURL = (track.flacUrl?.isEmpty == false) ? track.flacUrl! : track.url
            let validURL = playURL.lowercased().hasPrefix("https://") ? playURL : "https://invalid.local/blocked"
            return AVPlayerItem(url: URL(string: validURL) ?? URL(string: "https://invalid.local/blocked")!)
        }
        for item in items[filtered.startIndex...] {
            queuePlayer.insert(item, after: nil)
        }
        currentIndex = filtered.startIndex
        positionMs = 0
        pendingResumeMs = resumePositionMs > 0 ? resumePositionMs : nil
        observeCurrentItemReadyForResume()
        scheduleGainTaps()
        scheduleLeveling()
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
                let newRate = change.newValue ?? 0
                let isPlaying = newRate > 0
                guard self.isPlaying != isPlaying else { return }
                self.isPlaying = isPlaying
                self.updateNowPlayingElapsedTime()
                if isPlaying { self.claimNowPlaying(playing: true) }
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
        let didSave = recorder.saveTick(
            queueKey: queueKey, show: show, track: currentTrack, trackIndex: currentIndex,
            positionMs: positionMs, artURL: artURL, force: force
        )
        if didSave && force, let syncSession, let progressStore {
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

    // MARK: - Volume leveling (#268)

    /// Queue items from the playhead onward — the ones actually inserted into
    /// `queuePlayer` (playback can start mid-show; see `items`).
    private var liveItems: [AVPlayerItem] {
        guard let currentIndex, items.indices.contains(currentIndex) else { return [] }
        return Array(items[currentIndex...])
    }

    /// Reacts to the Settings toggle. Turning it on attaches taps and kicks off a
    /// measurement (cache hit returns near-instantly); turning it off drops every tap
    /// back to unity gain but leaves them attached — re-enabling on the same queue
    /// then needs no new tap setup, just the cached measurement.
    private func levelVolumeDidChange(_ enabled: Bool) {
        guard !isCasting else { return }
        levelingTask?.cancel()
        levelingTask = nil
        pendingLevelingGainLinear = 1.0
        for storage in gainStorageByItem.values { storage.gain = 1.0 }
        if enabled {
            scheduleGainTaps()
            scheduleLeveling()
        }
    }

    private func scheduleGainTaps() {
        guard playbackSettings?.levelVolume == true, !isCasting else { return }
        for item in liveItems {
            attachGainTap(to: item)
        }
    }

    /// Runs the decode-ahead measurement in the background and applies the resulting
    /// gain in place. One task per source key: a newer `startQueue` cancels the stale
    /// task, and a completed measurement whose source has since changed is discarded
    /// (its cached `SourceLoudness` still benefits the next play of that source).
    private func scheduleLeveling() {
        guard let settings = playbackSettings, settings.levelVolume else { return }
        guard !isCasting else { return }
        guard let measurer = loudnessMeasurer,
              let key = tracks.first?.levelingKey else { return }
        let queueTracks = tracks
        levelingTask?.cancel()
        levelingTask = Task { [weak self] in
            let row = await measurer.measure(key: key, tracks: queueTracks)
            guard let row, !Task.isCancelled else { return }
            guard let self, self.tracks.first?.levelingKey == key else { return }
            // `measure` returning nil (segments unfetchable / decode failure) means no
            // trustworthy loudness — stay at 0 dB rather than guess.
            self.applyLevelingGainDb(levelingGainDb(lufs: row.lufs, peakDbfs: row.peakDb))
        }
    }

    private func applyLevelingGainDb(_ db: Double) {
        let linear = Float(pow(10.0, db / 20.0))
        pendingLevelingGainLinear = linear
        for storage in gainStorageByItem.values { storage.gain = linear }
    }

    /// Registers `item` for a tap and resolves its audio track asynchronously. The
    /// storage is registered up front so a Settings toggle or a completed measurement
    /// mid-load is not lost; a queue teardown between registration and track load is
    /// detected by identity and abandons the tap.
    private func attachGainTap(to item: AVPlayerItem) {
        let itemID = ObjectIdentifier(item)
        guard gainStorageByItem[itemID] == nil else { return }
        let storage = GainTapStorage(gain: pendingLevelingGainLinear)
        gainStorageByItem[itemID] = storage
        Task { [weak self] in
            guard let self else { return }
            guard self.gainStorageByItem[itemID] === storage else { return }
            let audioTracks: [AVAssetTrack]
            do {
                audioTracks = try await item.asset.loadTracks(withMediaType: .audio)
            } catch {
                return
            }
            guard let audioTrack = audioTracks.first,
                  self.gainStorageByItem[itemID] === storage else { return }
            self.installGainTap(storage: storage, audioTrack: audioTrack, on: item)
        }
    }

    private func installGainTap(storage: GainTapStorage, audioTrack: AVAssetTrack, on item: AVPlayerItem) {
        let storageUnmanaged = Unmanaged.passRetained(storage)
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: storageUnmanaged.toOpaque(),
            init: nil,
            finalize: gainTapFinalize,
            prepare: nil,
            unprepare: nil,
            process: gainTapProcess
        )
        var tapOut: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PostEffects,
            &tapOut
        )
        guard status == noErr, let tap = tapOut else {
            storageUnmanaged.release()
            return
        }
        let parameters = AVMutableAudioMixInputParameters(track: audioTrack)
        parameters.audioTapProcessor = tap
        // MTAudioProcessingTapCreate handed us +1; the audio mix holds its own retain.
        Unmanaged.passUnretained(tap).release()
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        item.audioMix = mix
    }
}
