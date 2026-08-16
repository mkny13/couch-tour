import CouchTourKit
import Foundation

/// Holds the one `ProgressStore` for the app's lifetime. A failure to open the on-disk
/// database is surfaced rather than crashing the app outright — the browse and playback
/// screens don't depend on it, only Continue Listening and History do.
@MainActor
final class AppModel: ObservableObject {
    let progressStore: ProgressStore?
    let progressStoreError: String?

    init() {
        do {
            progressStore = try ProgressStore()
            progressStoreError = nil
        } catch {
            progressStore = nil
            progressStoreError = "Couldn't open the listening history database: \(error)"
        }
    }
}
