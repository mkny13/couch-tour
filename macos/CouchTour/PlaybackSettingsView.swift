import CouchTourKit
import SwiftUI

/// Playback preferences tab in macOS Settings (⌘,) (#49).
struct PlaybackSettingsView: View {
    @ObservedObject var settings: PlaybackSettings

    var body: some View {
        Form {
            Section {
                Toggle("Skip filler tracks", isOn: $settings.skipFiller)
                Text("Automatically bypasses non-music tracks (intros, outros, tuning, stage banter, crowd noise, and stage announcements) during playback.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section {
                #if BETA
                Button("Update Couch Tour Beta...") {
                    AppModel.launchUpdateScript()
                }
                .frame(maxWidth: .infinity, alignment: .center)
                #else
                Button("Update Couch Tour...") {
                    AppModel.launchUpdateScript()
                }
                .frame(maxWidth: .infinity, alignment: .center)
                #endif
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
