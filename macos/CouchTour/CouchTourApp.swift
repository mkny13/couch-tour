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
                .environmentObject(appModel.favorites)
        }
        .commands {
            CommandGroup(after: .sidebar) {
                Button(appModel.showNowPlaying ? "Hide Now Playing" : "Show Now Playing") {
                    appModel.showNowPlaying.toggle()
                }
                .keyboardShortcut("i", modifiers: [.command, .option])

                // Switches to the Search section and focuses its field in one step —
                // AppModel is the only thing both this scene and SearchView can reach.
                Button("Find") {
                    appModel.selection = .search
                    appModel.focusSearchField = true
                }
                .keyboardShortcut("f", modifiers: .command)
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

        // ⌘, opens this — declaring the scene is all it takes, no Commands entry needed.
        // Sync used to be a sidebar section (RootView.swift); it's the only settings-like
        // surface the app has, so it moved here rather than duplicating the form in both
        // places (D171). Account (#57) joined it as a second tab rather than a third sidebar
        // section or its own window, same reasoning.
        Settings {
            TabView {
                AccountView(session: appModel.phishInSession)
                    .tabItem { Label("Account", systemImage: "person.circle") }
                SyncView(syncSession: appModel.syncSession, sync: { appModel.syncNow() })
                    .tabItem { Label("Sync", systemImage: "arrow.triangle.2.circlepath") }
            }
            .frame(width: 450)
        }
    }
}
