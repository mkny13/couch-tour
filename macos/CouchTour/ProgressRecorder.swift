import CouchTourKit
import Foundation

/// Ports the save rules from Android's `PlaybackService.saveNow()`: write every 5s while
/// playing, plus immediately on play/pause and track change (D7). `finished` is only ever
/// set by the queue actually draining (D20) — never accepted as a parameter here, so nothing
/// can call `save` and guess a queue is finished from the outside.
@MainActor
final class ProgressRecorder {
    private let store: ProgressStore?
    private var lastSaveTime: Date = .distantPast

    init(store: ProgressStore?) {
        self.store = store
    }

    /// `force` bypasses the 5s throttle — used for play/pause and track-change saves, which
    /// should land immediately rather than wait for the next tick.
    func saveTick(
        queueKey: String?, show: ShowSummary?, track: PlayableTrack?, trackIndex: Int?,
        positionMs: Int64, artURL: String?, force: Bool
    ) {
        guard let store, let queueKey, let show, let track, let trackIndex else { return }
        guard force || Date().timeIntervalSince(lastSaveTime) >= 5 else { return }
        lastSaveTime = Date()

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
    }

    /// Sets the flag on the existing row without touching trackIndex/positionMs — playing it
    /// again is what restarts from the top (D22), not a rewrite of the stopping point.
    func markFinished(queueKey: String?) {
        guard let store, let queueKey else { return }
        try? store.markFinished(key: queueKey)
    }
}
