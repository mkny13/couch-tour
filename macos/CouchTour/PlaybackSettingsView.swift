import CouchTourKit
import SwiftUI

/// Playback preferences tab in macOS Settings (⌘,) (#49).
struct PlaybackSettingsView: View {
    @ObservedObject var settings: PlaybackSettings
    @ObservedObject var updater: UpdaterViewModel
    @ObservedObject var themeSettings: ThemeSettings

    var body: some View {
        Form {
            Section("Appearance") {
                Picker("Theme", selection: $themeSettings.themeMode) {
                    ForEach(ThemeMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
            }
            Section {
                Toggle("Skip filler tracks", isOn: $settings.skipFiller)
                Text("Automatically bypasses non-music tracks (intros, outros, tuning, stage banter, crowd noise, and stage announcements) during playback.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Toggle("Level volume across sources", isOn: $settings.levelVolume)
                Text("Measures each recording's loudness in the background (30-second decoded slices, EBU R128 / BS.1770) and plays it back with a constant gain, so a quiet audience tape and a hot soundboard mix play at the same loudness. Google Cast sessions are excluded — the receiver decodes the audio.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Software Updates") {
                Toggle("Automatically check for updates", isOn: $updater.automaticallyChecksForUpdates)
                Button("Check for Updates...") {
                    updater.checkForUpdates()
                }
                .disabled(!updater.canCheckForUpdates)
                .frame(maxWidth: .infinity, alignment: .center)
            }
            Section {
                Text(Bundle.main.appVersionString)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .formStyle(.grouped)
    }
}
