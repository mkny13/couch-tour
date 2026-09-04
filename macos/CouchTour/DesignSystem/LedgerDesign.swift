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

    private var badgeColor: (bg: Color, border: Color, text: Color) {
        switch type {
        case "PLAYLIST", "LIST":
            return (
                Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.18),
                Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0).opacity(0.45),
                Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0)
            )
        case "SHOW":
            return (
                Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.18),
                Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.45),
                Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0)
            )
        default:
            return (
                Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0).opacity(0.12),
                Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0),
                Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0)
            )
        }
    }

    public var body: some View {
        Text(type)
            .font(.system(size: 9, weight: .semibold))
            .tracking(0.9)
            .foregroundStyle(badgeColor.text)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(badgeColor.bg, in: RoundedRectangle(cornerRadius: 4))
            .overlay(
                RoundedRectangle(cornerRadius: 4)
                    .stroke(badgeColor.border, lineWidth: 1)
            )
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

/// Top 2px progress bar overlay on In-Progress cards.
public struct ProgressBarOverlay: View {
    public let fraction: Double
    public var fillColor: Color

    public init(fraction: Double, fillColor: Color = Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0)) {
        self.fraction = min(max(fraction, 0.0), 1.0)
        self.fillColor = fillColor
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0).opacity(0.10))
                Rectangle()
                    .fill(fillColor)
                    .frame(width: geo.size.width * CGFloat(fraction))
            }
        }
        .frame(height: 2)
    }
}

/// Dismissible Jam Chart note card with 1px border.
public struct JamChartNoteCard: View {
    public let note: String
    public let onDismiss: (() -> Void)?
    @Environment(\.ledgerColors) private var colors

    public init(note: String, onDismiss: (() -> Void)? = nil) {
        self.note = note
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("JAM CHART NOTE · PHISH.IN")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(colors.textMuted)

                Spacer()

                if let onDismiss {
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(colors.textMuted)
                            .frame(width: 22, height: 22)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Dismiss jam chart note")
                }
            }

            Text(note)
                .font(.system(size: 13))
                .lineSpacing(3)
                .foregroundStyle(colors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(colors.isDark ? Color(red: 0x23 / 255.0, green: 0x25 / 255.0, blue: 0x32 / 255.0).opacity(0.72) : Color(red: 0xE6 / 255.0, green: 0xE7 / 255.0, blue: 0xF0 / 255.0).opacity(0.85))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(colors.panelBorder, lineWidth: 1)
        )
    }
}

/// Window traffic lights (12px red/yellow/green) matching macOS window chrome.
public struct TrafficLights: View {
    public init() {}

    public var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(Color(red: 1.0, green: 0x5F / 255.0, blue: 0x57 / 255.0))
                .frame(width: 12, height: 12)
            Circle()
                .fill(Color(red: 0xFE / 255.0, green: 0xBC / 255.0, blue: 0x2E / 255.0))
                .frame(width: 12, height: 12)
            Circle()
                .fill(Color(red: 0x28 / 255.0, green: 0xC8 / 255.0, blue: 0x40 / 255.0))
                .frame(width: 12, height: 12)
        }
    }
}

/// Artwork wrapper with conic-gradient stagelight glow blur.
public struct ConicGlowArtwork: View {
    public let url: String?
    public let artist: String
    public let date: String
    public let size: CGFloat
    public let cornerRadius: CGFloat
    public let glowPadding: CGFloat
    public let blurRadius: CGFloat
    @Environment(\.ledgerColors) private var colors

    public init(
        url: String?,
        artist: String,
        date: String,
        size: CGFloat,
        cornerRadius: CGFloat = 14,
        glowPadding: CGFloat = 14,
        blurRadius: CGFloat = 24
    ) {
        self.url = url
        self.artist = artist
        self.date = date
        self.size = size
        self.cornerRadius = cornerRadius
        self.glowPadding = glowPadding
        self.blurRadius = blurRadius
    }

    public var body: some View {
        ZStack {
            if colors.isDark {
                AngularGradient(
                    gradient: Gradient(colors: [
                        Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0),
                        Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0),
                        Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0),
                        Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0),
                        Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0)
                    ]),
                    center: .center,
                    startAngle: .degrees(200),
                    endAngle: .degrees(560)
                )
                .frame(width: size + glowPadding * 2, height: size + glowPadding * 2)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius + 8))
                .blur(radius: blurRadius)
                .opacity(0.5)
            }

            ArtworkView(url: url, artist: artist, date: date, size: size)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        }
        .frame(width: size, height: size)
    }
}

