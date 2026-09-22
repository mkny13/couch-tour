import AppKit
import CouchTourKit
import SwiftUI

/// External release link pill (#228).
/// Opens the platform's native app via URL Scheme (spotify://, tidal://) with a web player fallback.
/// Heuristic matches display an "Auto-matched" suffix and a spark icon (D258).
public struct ExternalLinkButton: View {
    public let externalRelease: ExternalRelease

    @Environment(\.ledgerColors) private var colors

    public init(externalRelease: ExternalRelease) {
        self.externalRelease = externalRelease
    }

    public var body: some View {
        Button(action: openLink) {
            HStack(spacing: 8) {
                Image(systemName: iconName)
                    .font(.system(size: 14))
                Text(label)
                    .font(.system(size: 14, weight: .medium))
            }
            .frame(height: 38)
            .padding(.horizontal, 14)
            .foregroundStyle(colors.textSubtle)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(colors.controlOutline, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var iconName: String {
        if externalRelease.isHeuristic {
            return "sparkles"
        }
        switch externalRelease.platform {
        case .spotify: return "music.note"
        case .tidal: return "waveform"
        }
    }

    private var platformLabel: String {
        switch externalRelease.platform {
        case .spotify: return "Spotify"
        case .tidal: return "Tidal"
        }
    }

    /// Heuristic matches show a suffix so users know this is an automated match, not curated.
    private var label: String {
        externalRelease.isHeuristic ? "\(platformLabel) · Auto-matched" : platformLabel
    }

    private func openLink() {
        guard let webURL = URL(string: externalRelease.url) else { return }
        
        let workspace = NSWorkspace.shared
        if let appURL = externalRelease.appURL {
            workspace.open(appURL, configuration: NSWorkspace.OpenConfiguration()) { _, error in
                if error != nil {
                    // Fallback to web if the app fails to open
                    workspace.open(webURL)
                }
            }
        } else {
            workspace.open(webURL)
        }
    }
}
