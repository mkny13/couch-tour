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
        _player = StateObject(wrappedValue: Player(
            progressStore: model.progressStore,
            syncSession: model.syncSession,
            playbackSettings: model.playbackSettings
        ))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appModel)
                .environmentObject(player)
                .environmentObject(appModel.favorites)
                .environmentObject(appModel.likedTracks)
                .environmentObject(appModel.playbackSettings)
                .environmentObject(appModel.phishInSession)
        }
        .commands {
            CommandGroup(after: .appInfo) {
                Button("Check for Updates...") {
                    appModel.checkForUpdates()
                }
                .disabled(!appModel.updater.canCheckForUpdates)
            }

            CommandGroup(after: .sidebar) {
                Button(appModel.showNowPlaying ? "Hide Now Playing" : "Show Now Playing") {
                    appModel.showNowPlaying.toggle()
                }
                .keyboardShortcut("i", modifiers: [.command, .option])

                // Focuses the toolbar's search field. With the sidebar gone the field is
                // always on screen, so this no longer has to navigate anywhere first — it
                // just asks for focus (RootView consumes the flag; `Commands` can't reach a
                // `@FocusState` directly).
                Button("Find") {
                    appModel.focusSearchField = true
                }
                .keyboardShortcut("f", modifiers: .command)
            }

            // The one thing a visible list of destinations was actually good for: jumping
            // straight across the app. Now invisible chrome, which costs nothing, and it
            // covers the only real regression from removing the sidebar — a cross-section
            // jump otherwise routes through Home (D203).
            CommandGroup(after: .toolbar) {
                Divider()
                Button("Home") { appModel.jump(to: nil) }
                    .keyboardShortcut("1", modifiers: .command)
                Button("Artists") { appModel.jump(to: .artists) }
                    .keyboardShortcut("2", modifiers: .command)
                Button("Listening") { appModel.jump(to: .listening) }
                    .keyboardShortcut("3", modifiers: .command)
                Button("Playlists") { appModel.jump(to: .playlists) }
                    .keyboardShortcut("4", modifiers: .command)
            }

            CommandMenu("Playback") {
                Button(player.isPlaying ? "Pause" : "Play") {
                    player.togglePlayPause()
                }
                // Space is handled by Player's local NSEvent monitor (D177), not a
                // Command keyboardShortcut — SwiftUI never delivers bare Space to a
                // menu action when a List has focus, which is almost always. Keeping a
                // .keyboardShortcut(.space) here would also steal the key from Search.

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

                Divider()

                Toggle("Skip Filler Tracks", isOn: Binding(
                    get: { appModel.playbackSettings.skipFiller },
                    set: { appModel.playbackSettings.skipFiller = $0 }
                ))

                if player.isCasting {
                    Divider()
                    Button("Disconnect Cast (\(player.castDeviceName ?? "Device"))") {
                        player.disconnectCast()
                    }
                }
            }
        }

        // ⌘, opens this — declaring the scene is all it takes, no Commands entry needed.
        // Sync used to be a sidebar section (RootView.swift); it's the only settings-like
        // surface the app has, so it moved here rather than duplicating the form in both
        // places (D171). Account (#57) joined it as a second tab rather than a third sidebar
        // section or its own window, same reasoning. Playback settings (#49) forms the third tab.
        //
        // The selection binding is what lets Home's Settings & status tiles land on the form
        // they name (D203): each tile sets `settingsTab` and then opens this window, replacing
        // the duplicate Account and Sync sheets Home used to carry (superseding D197).
        Settings {
            TabView(selection: $appModel.settingsTab) {
                PlaybackSettingsView(settings: appModel.playbackSettings, updater: appModel.updater)
                    .tabItem { Label("Playback", systemImage: "play.circle") }
                    .tag(SettingsTab.playback)
                AccountView(session: appModel.phishInSession)
                    .tabItem { Label("Account", systemImage: "person.circle") }
                    .tag(SettingsTab.account)
                SyncView(syncSession: appModel.syncSession, sync: { appModel.syncNow() })
                    .tabItem { Label("Sync", systemImage: "arrow.triangle.2.circlepath") }
                    .tag(SettingsTab.sync)
            }
            .frame(width: 450)
        }
    }
}
