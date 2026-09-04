import Foundation

/// Ports the save rules from Android's `PlaybackService.saveNow()`: write every 5s while
/// playing, plus immediately on play/pause and track change (D7). `finished` is only ever
/// set by the queue actually draining (D20) — never accepted as a parameter here, so nothing
/// can call `save` and guess a queue is finished from the outside.
@MainActor
public final class ProgressRecorder {
    private let store: ProgressStore?
    private var lastSaveTime: Date = .distantPast

    private var lastSavedQueueKey: String?
    private var lastSavedTrackIndex: Int?
    private var lastSavedPositionMs: Int64?

    public init(store: ProgressStore?) {
        self.store = store
    }

    /// `force` bypasses the 5s throttle — used for play/pause and track-change saves, which
    /// should land immediately rather than wait for the next tick.
    ///
    /// Returns `true` if a record was actually written to the store, `false` if skipped
    /// (e.g. throttled or position unchanged).
    @discardableResult
    public func saveTick(
        queueKey: String?, show: ShowSummary?, track: PlayableTrack?, trackIndex: Int?,
        positionMs: Int64, artURL: String?, force: Bool
    ) -> Bool {
        guard let store, let queueKey, let show, let track, let trackIndex else { return false }
        guard force || Date().timeIntervalSince(lastSaveTime) >= 5 else { return false }

        // If the queue, track, and position haven't changed since the last save, do not
        // rewrite the row with a new `updatedAt` timestamp. Writing an unchanged position
        // with `Date.now` creates a false "newer" timestamp that clobbers genuine playback
        // from other devices in sync (#127).
        if queueKey == lastSavedQueueKey &&
            trackIndex == lastSavedTrackIndex &&
            positionMs == lastSavedPositionMs {
            return false
        }

        lastSaveTime = Date()
        lastSavedQueueKey = queueKey
        lastSavedTrackIndex = trackIndex
        lastSavedPositionMs = positionMs

        // dismissed is written false unconditionally, same as Android: saving during
        // playback is what brings a previously-dismissed queue back (D38's test case).
        let row = PlaybackProgress(
            queueKey: queueKey,
            title: show.date,
            subtitle: show.where_,
            artUrl: artURL,
            trackIndex: trackIndex,
            positionMs: positionMs,
            trackTitle: track.title,
            updatedAt: Int64(Date().timeIntervalSince1970 * 1000),
            finished: false,
            dismissed: false,
            artist: show.artist.name
        )
        try? store.put(row)
        return true
    }

    /// Sets the flag on the existing row without touching trackIndex/positionMs — playing it
    /// again is what restarts from the top (D22), not a rewrite of the stopping point.
    public func markFinished(queueKey: String?) {
        guard let store, let queueKey else { return }
        lastSavedQueueKey = nil
        lastSavedTrackIndex = nil
        lastSavedPositionMs = nil
        try? store.markFinished(key: queueKey)
    }
}
