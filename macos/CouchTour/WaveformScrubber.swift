import SwiftUI

/// Vector waveform scrubber for macOS SwiftUI.
/// Renders the dual-layer waveform bars from the design handoff SVG (#wave-np / #wave-pl).
/// Supports drag-to-seek and tap-to-seek.
public struct WaveformScrubber: View {
    public let progressFraction: Double // 0.0 .. 1.0
    public let onSeek: (Double) -> Void

    @Environment(\.ledgerColors) private var colors
    @State private var isDragging: Bool = false
    @State private var dragFraction: Double?

    // Sampled normalized waveform peak heights (0.1 .. 1.0) across 95 sample points
    private static let waveformHeights: [CGFloat] = [
        0.18, 0.28, 0.42, 0.35, 0.58, 0.72, 0.85, 0.65, 0.45, 0.38,
        0.52, 0.68, 0.90, 0.95, 0.80, 0.60, 0.42, 0.30, 0.48, 0.62,
        0.75, 0.88, 0.70, 0.55, 0.40, 0.35, 0.50, 0.65, 0.82, 0.92,
        0.85, 0.68, 0.52, 0.38, 0.45, 0.60, 0.78, 0.86, 0.74, 0.58,
        0.42, 0.32, 0.50, 0.68, 0.85, 0.94, 0.88, 0.72, 0.54, 0.40,
        0.48, 0.64, 0.80, 0.89, 0.76, 0.60, 0.45, 0.35, 0.52, 0.70,
        0.86, 0.95, 0.82, 0.65, 0.48, 0.36, 0.50, 0.66, 0.84, 0.90,
        0.78, 0.62, 0.44, 0.32, 0.46, 0.62, 0.79, 0.88, 0.75, 0.58,
        0.40, 0.30, 0.45, 0.60, 0.76, 0.85, 0.72, 0.55, 0.38, 0.28,
        0.40, 0.52, 0.65, 0.48, 0.32
    ]

    public init(progressFraction: Double, onSeek: @escaping (Double) -> Void) {
        self.progressFraction = max(0.0, min(1.0, progressFraction))
        self.onSeek = onSeek
    }

    private var activeFraction: Double {
        dragFraction ?? progressFraction
    }

    public var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let height = geometry.size.height

            ZStack(alignment: .leading) {
                // Background unplayed waveform
                WaveformBarsShape(heights: Self.waveformHeights)
                    .fill(colors.textPrimary.opacity(0.18))
                    .frame(width: width, height: height)

                // Played waveform clipped to progress
                WaveformBarsShape(heights: Self.waveformHeights)
                    .fill(LedgerTheme.specGradient)
                    .frame(width: width, height: height)
                    .mask(
                        Rectangle()
                            .frame(width: width * CGFloat(activeFraction), height: height)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    )

                // Thin 1.5px scrubber head indicator
                Rectangle()
                    .fill(Color.white)
                    .frame(width: 2, height: height)
                    .offset(x: max(0, min(width - 2, width * CGFloat(activeFraction) - 1)))
                    .shadow(color: colors.accent.opacity(0.6), radius: 2)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let frac = max(0.0, min(1.0, Double(value.location.x / width)))
                        dragFraction = frac
                        isDragging = true
                    }
                    .onEnded { value in
                        let frac = max(0.0, min(1.0, Double(value.location.x / width)))
                        dragFraction = nil
                        isDragging = false
                        onSeek(frac)
                    }
            )
        }
        .frame(height: 38)
    }
}

private struct WaveformBarsShape: Shape {
    let heights: [CGFloat]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard !heights.isEmpty else { return path }

        let count = heights.count
        let barWidth: CGFloat = 2.0
        let spacing = (rect.width - CGFloat(count) * barWidth) / CGFloat(max(1, count - 1))
        let centerY = rect.midY

        for (index, hFraction) in heights.enumerated() {
            let x = CGFloat(index) * (barWidth + max(0.5, spacing))
            let barHeight = rect.height * hFraction * 0.9
            let y = centerY - barHeight / 2.0
            path.addRoundedRect(
                in: CGRect(x: x, y: y, width: barWidth, height: barHeight),
                cornerSize: CGSize(width: 1, height: 1)
            )
        }
        return path
    }
}
