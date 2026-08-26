import CouchTourKit
import SwiftUI

/// Playback preferences tab in macOS Settings (⌘,) (#49).
struct PlaybackSettingsView: View {
    @ObservedObject var settings: PlaybackSettings
    @ObservedObject var updater: UpdaterViewModel

    var body: some View {
        Form {
            Section {
                Toggle("Skip filler tracks", isOn: $settings.skipFiller)
                Text("Automatically bypasses non-music tracks (intros, outros, tuning, stage banter, crowd noise, and stage announcements) during playback.")
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
