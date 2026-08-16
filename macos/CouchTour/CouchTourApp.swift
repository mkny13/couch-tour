import SwiftUI

@main
struct CouchTourApp: App {
    @StateObject private var appModel: AppModel
    @StateObject private var player: Player

    init() {
        // Player needs AppModel's ProgressStore to record playback, so AppModel is built
        // first and handed in rather than each @StateObject initializing independently.
        let model = AppModel()
        _appModel = StateObject(wrappedValue: model)
        _player = StateObject(wrappedValue: Player(progressStore: model.progressStore))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appModel)
                .environmentObject(player)
        }
    }
}
