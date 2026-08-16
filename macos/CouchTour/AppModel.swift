import CouchTourKit
import Foundation

/// Holds the one `ProgressStore` for the app's lifetime. A failure to open the on-disk
/// database is surfaced rather than crashing the app outright — the browse and playback
/// screens don't depend on it, only Continue Listening and History do.
@MainActor
final class AppModel: ObservableObject {
    let progressStore: ProgressStore?
    let progressStoreError: String?
    let syncSession = SyncSession()

    init() {
        do {
            progressStore = try ProgressStore()
            progressStoreError = nil
        } catch {
            progressStore = nil
            progressStoreError = "Couldn't open the listening history database: \(error)"
        }
    }

    /// One push-then-pull cycle. Fire-and-forget: `sync` is a no-op if unpaired, and any
    /// network failure here is caught and dropped — nothing surfaces a sync error to the UI
    /// in this MVP, matching macOS's own "Task on activate plus a timer" cadence rather than
    /// a guaranteed-background mechanism.
    func syncNow() {
        guard let progressStore else { return }
        Task { try? await syncSession.sync(progressStore) }
    }
}
