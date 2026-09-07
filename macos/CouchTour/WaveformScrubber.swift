import SwiftUI
import CouchTourKit

/// Vector waveform scrubber for macOS SwiftUI.
/// Renders dynamic audio waveform bars when available, falling back to a default waveform.
/// Supports drag-to-seek and tap-to-seek.
public struct WaveformScrubber: View {
    public let progressFraction: Double // 0.0 .. 1.0
    public let waveformURL: String?
    public let onSeek: (Double) -> Void

    @Environment(\.ledgerColors) private var colors
    @State private var isDragging: Bool = false
    @State private var dragFraction: Double?
    @State private var dynamicEnvelope: WaveformEnvelope?

    // Sampled normalized fallback heights (0.1 .. 1.0) across 95 sample points
    private static let fallbackHeights: [CGFloat] = [
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

    private static let fallbackEnvelope = WaveformEnvelope(
        top: fallbackHeights,
        bottom: fallbackHeights.map { $0 * 0.92 }
    )

    public init(
        progressFraction: Double,
        waveformURL: String? = nil,
        onSeek: @escaping (Double) -> Void
    ) {
        self.progressFraction = max(0.0, min(1.0, progressFraction))
        self.waveformURL = waveformURL
        self.onSeek = onSeek
    }

    private var activeFraction: Double {
        dragFraction ?? progressFraction
    }

    private var currentEnvelope: WaveformEnvelope {
        dynamicEnvelope ?? Self.fallbackEnvelope
    }

    public var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let height = geometry.size.height
            let envelope = currentEnvelope
            let playedWidth = width * CGFloat(activeFraction)

            ZStack(alignment: .leading) {
                // 1. Background unplayed continuous waveform
                ContinuousWaveformShape(envelope: envelope)
                    .fill(colors.textPrimary.opacity(0.20))
                    .frame(width: width, height: height)

                // Unplayed center hairline
                Rectangle()
                    .fill(colors.textPrimary.opacity(0.20))
                    .frame(width: width, height: 1.5)
                    .offset(y: 0)

                // 2. Played continuous waveform clipped to progress with spec gradient
                if playedWidth > 0 {
                    ZStack(alignment: .leading) {
                        ContinuousWaveformShape(envelope: envelope)
                            .fill(LedgerTheme.specGradient)
                            .frame(width: width, height: height)

                        Rectangle()
                            .fill(LedgerTheme.specGradient)
                            .frame(width: width, height: 2.0)
                    }
                    .mask(
                        Rectangle()
                            .frame(width: playedWidth, height: height)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    )
                }

                // 3. 2px playhead needle cursor matching design spec
                if playedWidth > 0 {
                    Rectangle()
                        .fill(Color.white)
                        .frame(width: 2, height: height)
                        .offset(x: max(0, min(width - 2, playedWidth - 1)))
                        .shadow(color: colors.accent.opacity(0.6), radius: 2)
                }
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
        .task(id: waveformURL) {
            guard let urlString = waveformURL, let url = URL(string: urlString) else {
                dynamicEnvelope = nil
                return
            }
            if let env = await WaveformLoader.shared.loadEnvelope(from: url, sampleCount: 400) {
                dynamicEnvelope = env
            } else {
                dynamicEnvelope = nil
            }
        }
    }
}

/// Continuous solid silhouette path drawn from top and bottom envelope slices.
private struct ContinuousWaveformShape: Shape {
    let envelope: WaveformEnvelope

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let top = envelope.top
        let bottom = envelope.bottom
        guard !top.isEmpty, !bottom.isEmpty else { return path }

        let count = top.count
        let step = rect.width / CGFloat(max(1, count - 1))
        let centerY = rect.midY
        let maxAmplitude = rect.height * 0.44

        // Start at top-left
        let firstTopY = centerY - top[0] * maxAmplitude
        path.move(to: CGPoint(x: 0, y: firstTopY))

        // Trace top contour from left to right
        for i in 1..<count {
            let x = CGFloat(i) * step
            let y = centerY - top[i] * maxAmplitude
            path.addLine(to: CGPoint(x: x, y: y))
        }

        // Trace to rightmost edge at centerline
        path.addLine(to: CGPoint(x: rect.width, y: centerY))

        // Trace bottom contour from right to left
        for i in stride(from: count - 1, through: 0, by: -1) {
            let x = CGFloat(i) * step
            let bFraction = i < bottom.count ? bottom[i] : top[i]
            let y = centerY + bFraction * maxAmplitude
            path.addLine(to: CGPoint(x: x, y: y))
        }

        path.closeSubpath()
        return path
    }
}
