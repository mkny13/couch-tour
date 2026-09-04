import CouchTourKit
import SwiftUI

/// Ledger design system color tokens, gradients, and type badges for macOS CouchTour.
/// Tokens mirror `design/handoff/README.md` and Android's `Theme.kt` / `DesignComponents.kt`.
public enum LedgerTheme {
    public static let specGradient = LinearGradient(
        colors: [
            Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0),
            Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0),
            Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0),
            Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0)
        ],
        startPoint: .leading,
        endPoint: .trailing
    )

    public static let coverArtGradient = LinearGradient(
        stops: [
            .init(color: Color(red: 0xD9 / 255.0, green: 0x77 / 255.0, blue: 0x06 / 255.0), location: 0.0),
            .init(color: Color(red: 0x99 / 255.0, green: 0x1B / 255.0, blue: 0x1B / 255.0), location: 0.55),
            .init(color: Color(red: 0x1E / 255.0, green: 0x1B / 255.0, blue: 0x4B / 255.0), location: 1.0)
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

public extension Color {
    static let ledgerDarkBackground = Color(red: 0x16 / 255.0, green: 0x18 / 255.0, blue: 0x26 / 255.0)
    static let ledgerDarkElevated = Color(red: 0x12 / 255.0, green: 0x14 / 255.0, blue: 0x1F / 255.0)
    static let ledgerDarkSurface = Color(red: 0x1C / 255.0, green: 0x1E / 255.0, blue: 0x2C / 255.0)
    static let ledgerDarkDivider = Color(red: 0x23 / 255.0, green: 0x25 / 255.0, blue: 0x32 / 255.0)
    static let ledgerDarkPanelBorder = Color(red: 0x29 / 255.0, green: 0x2B / 255.0, blue: 0x31 / 255.0)
    static let ledgerDarkControlOutline = Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0)
    static let ledgerDarkTextPrimary = Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0)
    static let ledgerDarkTextSecondary = Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0)
    static let ledgerDarkTextMuted = Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0)
    static let ledgerDarkAccent = Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0)
    static let ledgerDarkRatingAmber = Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0)

    static let ledgerLightBackground = Color(red: 1.0, green: 1.0, blue: 1.0)
    static let ledgerLightElevated = Color(red: 0xF7 / 255.0, green: 0xF7 / 255.0, blue: 0xFB / 255.0)
    static let ledgerLightSurface = Color(red: 0xF0 / 255.0, green: 0xF1 / 255.0, blue: 0xF7 / 255.0)
    static let ledgerLightDivider = Color(red: 0xE4 / 255.0, green: 0xE7 / 255.0, blue: 0xF5 / 255.0)
    static let ledgerLightPanelBorder = Color(red: 0xD7 / 255.0, green: 0xDA / 255.0, blue: 0xE8 / 255.0)
    static let ledgerLightTextPrimary = Color(red: 0x20 / 255.0, green: 0x22 / 255.0, blue: 0x2C / 255.0)
    static let ledgerLightTextSecondary = Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0)
    static let ledgerLightTextMuted = Color(red: 0x76 / 255.0, green: 0x7A / 255.0, blue: 0x8C / 255.0)
    static let ledgerLightAccent = Color(red: 0x6F / 255.0, green: 0x62 / 255.0, blue: 0xC7 / 255.0)
    static let ledgerLightRatingAmber = Color(red: 0xA0 / 255.0, green: 0x66 / 255.0, blue: 0x15 / 255.0)
}

/// Adaptive Ledger color resolver based on ColorScheme.
public struct LedgerColors {
    public let isDark: Bool

    public var background: Color { isDark ? .ledgerDarkBackground : .ledgerLightBackground }
    public var elevated: Color { isDark ? .ledgerDarkElevated : .ledgerLightElevated }
    public var surface: Color { isDark ? .ledgerDarkSurface : .ledgerLightSurface }
    public var divider: Color { isDark ? .ledgerDarkDivider : .ledgerLightDivider }
    public var panelBorder: Color { isDark ? .ledgerDarkPanelBorder : .ledgerLightPanelBorder }
    public var controlOutline: Color { isDark ? .ledgerDarkControlOutline : .ledgerLightPanelBorder }
    public var textPrimary: Color { isDark ? .ledgerDarkTextPrimary : .ledgerLightTextPrimary }
    public var textSecondary: Color { isDark ? .ledgerDarkTextSecondary : .ledgerLightTextSecondary }
    public var textMuted: Color { isDark ? .ledgerDarkTextMuted : .ledgerLightTextMuted }
    public var accent: Color { isDark ? .ledgerDarkAccent : .ledgerLightAccent }
    public var ratingAmber: Color { isDark ? .ledgerDarkRatingAmber : .ledgerLightRatingAmber }
}

public extension EnvironmentValues {
    var ledgerColors: LedgerColors {
        LedgerColors(isDark: colorScheme == .dark)
    }
}

/// Fixed-width 44dp type badge (LIST, SHOW, TRACK) ensuring consistent row alignment.
public struct TypeBadge: View {
    public let type: String
    @Environment(\.ledgerColors) private var colors

    public init(type: String) {
        self.type = type.uppercased()
    }

    private var badgeColor: (bg: Color, text: Color) {
        switch type {
        case "LIST":
            return (Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.18), colors.accent)
        case "SHOW":
            return (Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.18), colors.ratingAmber)
        default:
            return (colors.textMuted.opacity(0.15), colors.textSecondary)
        }
    }

    public var body: some View {
        Text(type)
            .font(.system(size: 10, weight: .bold))
            .tracking(0.8)
            .foregroundStyle(badgeColor.text)
            .frame(width: 44, height: 18)
            .background(badgeColor.bg, in: RoundedRectangle(cornerRadius: 3))
    }
}

/// Hairline gradient accent rule with optional start/end fade.
public struct GradientHairline: View {
    public var height: CGFloat = 1
    public var opacity: Double = 0.85

    public init(height: CGFloat = 1, opacity: Double = 0.85) {
        self.height = height
        self.opacity = opacity
    }

    public var body: some View {
        Rectangle()
            .fill(LedgerTheme.specGradient)
            .frame(height: height)
            .opacity(opacity)
    }
}
