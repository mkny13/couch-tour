import Foundation

/// Persistent playback preferences (#49).
///
/// Backed by `UserDefaults` with `@Published` properties so UI controls, `AppModel`,
/// and `Player` can observe changes reactively.
@MainActor
public final class PlaybackSettings: ObservableObject {
    private let defaults: UserDefaults
    private let skipFillerKey = "skip_filler_tracks"

    /// Whether non-music filler tracks (intro, outro, tuning, banter, crowd noise)
    /// should be skipped automatically during playback queue construction and advancement.
    /// Off by default (`false`).
    @Published public var skipFiller: Bool {
        didSet {
            defaults.set(skipFiller, forKey: skipFillerKey)
        }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.skipFiller = defaults.bool(forKey: skipFillerKey)
    }
}
