import SwiftUI

/// The card that takes you somewhere. With the sidebar gone (D203) these are the app's
/// navigation, not decoration, which is what the chevron is for — #102's first complaint was
/// that Home's clickable cards looked exactly like its non-clickable ones.
struct NavigationTile<Trailing: View>: View {
    let title: String
    let subtitle: String
    let icon: String
    var iconColor: Color = .accentColor
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(iconColor)
                .frame(width: 32)
                // Decorative: the title beside it already says what this is, so announcing the
                // glyph too would just make VoiceOver read every tile twice.
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            trailing()
        }
        .cardSurface()
        .contentShape(Rectangle())
    }
}

/// The "this goes somewhere" mark. A named type rather than an inline `Image` so the default
/// initializer below has something concrete to constrain `Trailing` to.
struct DisclosureChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.caption)
            .foregroundStyle(.tertiary)
            .accessibilityHidden(true)
    }
}

extension NavigationTile where Trailing == DisclosureChevron {
    init(title: String, subtitle: String, icon: String, iconColor: Color = .accentColor) {
        self.init(title: title, subtitle: subtitle, icon: icon, iconColor: iconColor) {
            DisclosureChevron()
        }
    }
}
