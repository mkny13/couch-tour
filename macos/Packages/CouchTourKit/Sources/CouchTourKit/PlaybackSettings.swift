import Combine
import Foundation

/// Streaming quality preference (#429). Raw values match Android's stored values (D228).
public enum AudioQuality: String, CaseIterable, Identifiable, Sendable {
    case lossless
    case compressed

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .lossless: return "FLAC (lossless)"
        case .compressed: return "MP3"
        }
    }

    /// Picks the stream for a track. Falls back to the other format rather than leaving a
    /// tape unplayable when the preferred one is missing.
    public func resolveURL(flac: String?, mp3: String) -> String {
        let hasFlac = flac?.isEmpty == false
        switch self {
        case .lossless: return hasFlac ? flac! : mp3
        case .compressed: return mp3.isEmpty && hasFlac ? flac! : mp3
        }
    }

    /// Whether `resolveURL` lands on the FLAC stream (drives the FLAC badge).
    public func playsFlac(flac: String?, mp3: String) -> Bool {
        flac?.isEmpty == false && resolveURL(flac: flac, mp3: mp3) == flac
    }
}

/// Persistent playback preferences (#49).
///
/// Backed by `UserDefaults` with `@Published` properties so UI controls, `AppModel`,
/// and `Player` can observe changes reactively.
@MainActor
public final class PlaybackSettings: ObservableObject {
    private let defaults: UserDefaults
    private let skipFillerKey = "skip_filler_tracks"
    private let levelVolumeKey = "level_volume"
    private let audioQualityKey = "audio_quality"
    private let gaplessKey = "gapless"

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

    /// Which stream to play when a track has both FLAC and MP3. Defaults to `.lossless`.
    @Published public var audioQuality: AudioQuality {
        didSet {
            defaults.set(audioQuality.rawValue, forKey: audioQualityKey)
        }
    }

    /// Gapless playback (default on): preload the upcoming queue items. Off, only the current
    /// item is queued and the next is loaded when it ends.
    @Published public var gapless: Bool {
        didSet {
            defaults.set(gapless, forKey: gaplessKey)
        }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.skipFiller = defaults.bool(forKey: skipFillerKey)
        self.levelVolume = defaults.bool(forKey: levelVolumeKey)
        self.audioQuality = defaults.string(forKey: audioQualityKey).flatMap(AudioQuality.init(rawValue:)) ?? .lossless
        self.gapless = defaults.object(forKey: gaplessKey) as? Bool ?? true
    }

    /// Signals playback and storage to clear all cached loudness measurements and cancel
    /// any in-flight background measurement (#269).
    public func clearMeasuredLoudness() {
        clearCacheSubject.send()
    }
}
