import Combine
import Foundation

/// Persistent playback preferences (#49).
///
/// Backed by `UserDefaults` with `@Published` properties so UI controls, `AppModel`,
/// and `Player` can observe changes reactively.
@MainActor
public final class PlaybackSettings: ObservableObject {
    private let defaults: UserDefaults
    private let skipFillerKey = "skip_filler_tracks"
    private let levelVolumeKey = "level_volume"

    /// Notifies listeners that cached loudness measurements should be cleared (#269).
    public let clearCacheSubject = PassthroughSubject<Void, Never>()

    /// Whether non-music filler tracks (intro, outro, tuning, banter, crowd noise)
    /// should be skipped automatically during playback queue construction and advancement.
    /// Off by default (`false`).
    @Published public var skipFiller: Bool {
        didSet {
            defaults.set(skipFiller, forKey: skipFillerKey)
        }
    }

    /// Volume leveling (#268): measure each source once in the background and play it back
    /// with a constant gain so switching between phish.in shows and Relisten tapes doesn't
    /// jump the volume. Off by default (`false`) — the app is level-accurate until asked.
    /// Cast sessions get no leveling (the receiver decodes the audio, so there is nothing
    /// for the app to apply gain to); the Settings help text says so.
    @Published public var levelVolume: Bool {
        didSet {
            defaults.set(levelVolume, forKey: levelVolumeKey)
        }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.skipFiller = defaults.bool(forKey: skipFillerKey)
        self.levelVolume = defaults.bool(forKey: levelVolumeKey)
    }

    /// Signals playback and storage to clear all cached loudness measurements and cancel
    /// any in-flight background measurement (#269).
    public func clearMeasuredLoudness() {
        clearCacheSubject.send()
    }
}
