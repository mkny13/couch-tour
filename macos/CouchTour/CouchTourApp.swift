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
        _player = StateObject(wrappedValue: Player(progressStore: model.progressStore, syncSession: model.syncSession))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appModel)
                .environmentObject(player)
        }
        .commands {
            CommandGroup(after: .sidebar) {
                Button(appModel.showNowPlaying ? "Hide Now Playing" : "Show Now Playing") {
                    appModel.showNowPlaying.toggle()
                }
                .keyboardShortcut("i", modifiers: [.command, .option])
            }

            CommandMenu("Playback") {
                Button(player.isPlaying ? "Pause" : "Play") {
                    player.togglePlayPause()
                }
                .keyboardShortcut(.space, modifiers: [])

                Divider()

                // Command-modified rather than the bare arrow keys browse already uses for
                // list navigation, so the two don't collide.
                Button("Next Track") {
                    player.skipToNext()
                }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled((player.currentIndex ?? -1) >= player.tracks.count - 1)

                Button("Previous Track") {
                    player.skipToPrevious()
                }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled((player.currentIndex ?? 0) == 0)
            }
        }
    }
}
