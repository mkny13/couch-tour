import Foundation
import CoreGraphics
import ImageIO

/// Asynchronous loader and peak extractor for track audio waveforms.
/// Supports both phish.in (opaque signal on transparent background)
/// and archive.org (transparent cutout signal on opaque background).
public actor WaveformLoader {
    public static let shared = WaveformLoader()

    private var cache: [URL: [CGFloat]] = [:]
    private var envelopeCache: [URL: WaveformEnvelope] = [:]

    public init() {}

    /// Derive the archive.org derivative waveform URL from an archive.org MP3 audio URL.
    /// E.g. https://archive.org/download/{id}/{track}.mp3 -> https://archive.org/download/{id}/{track}.png
    public static func archiveOrgWaveformURL(from mp3URLString: String) -> String? {
        guard mp3URLString.lowercased().hasPrefix("https://"), mp3URLString.contains("archive.org/download/"), mp3URLString.hasSuffix(".mp3") else {
            return nil
        }
        return String(mp3URLString.dropLast(4)) + ".png"
    }

    /// Loads and parses waveform envelope from a URL. Results are cached in memory.
    public func loadEnvelope(from url: URL, sampleCount: Int = 400) async -> WaveformEnvelope? {
        if let cached = envelopeCache[url] {
            return cached
        }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
                return nil
            }
            guard let env = Self.extractEnvelope(from: data, sampleCount: sampleCount) else {
                return nil
            }
            envelopeCache[url] = env
            return env
        } catch {
            return nil
        }
    }

    /// Loads and parses waveform peaks from a URL. Results are cached in memory.
    public func loadWaveform(from url: URL, barCount: Int = 95) async -> [CGFloat]? {
        if let cached = cache[url] {
            return cached
        }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
                return nil
            }
            guard let heights = Self.extractHeights(from: data, sampleCount: barCount) else {
                return nil
            }
            cache[url] = heights
            return heights
        } catch {
            return nil
        }
    }

    /// Synchronously extract top and bottom envelope contours from PNG image data.
    /// Returns normalized top and bottom fractions between 0.04 and 0.96.
    public static func extractEnvelope(from data: Data, sampleCount: Int = 400) -> WaveformEnvelope? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }

        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0, sampleCount > 0 else { return nil }

        let colorSpace = CGColorSpaceCreateDeviceGray()
        var rawBytes = [UInt8](repeating: 0, count: width * height * 2)
        guard let context = CGContext(
            data: &rawBytes,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 2,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        let cornerAlpha = rawBytes[1]
        let isInverted = cornerAlpha > 128

        let centerY = height / 2
        let step = Double(width) / Double(sampleCount)
        var topAmps: [CGFloat] = []
        var bottomAmps: [CGFloat] = []
        topAmps.reserveCapacity(sampleCount)
        bottomAmps.reserveCapacity(sampleCount)

        for i in 0..<sampleCount {
            let startX = Int(Double(i) * step)
            let endX = min(Int(Double(i + 1) * step), width)
            var maxTopDist = 0
            var maxBottomDist = 0

            for x in startX..<max(startX + 1, endX) {
                // Top half: from 0 down to centerY
                for y in 0..<centerY {
                    let offset = (y * width + x) * 2
                    let alpha = rawBytes[offset + 1]
                    let isSignal = isInverted ? (alpha < 128) : (alpha > 128)
                    if isSignal {
                        let dist = centerY - y
                        if dist > maxTopDist { maxTopDist = dist }
                        break
                    }
                }
                // Bottom half: from height - 1 up to centerY
                for y in stride(from: height - 1, through: centerY, by: -1) {
                    let offset = (y * width + x) * 2
                    let alpha = rawBytes[offset + 1]
                    let isSignal = isInverted ? (alpha < 128) : (alpha > 128)
                    if isSignal {
                        let dist = y - centerY
                        if dist > maxBottomDist { maxBottomDist = dist }
                        break
                    }
                }
            }
            topAmps.append(CGFloat(maxTopDist) / CGFloat(max(1, centerY)))
            bottomAmps.append(CGFloat(maxBottomDist) / CGFloat(max(1, centerY)))
        }

        let maxTop = topAmps.max() ?? 1.0
        let maxBottom = bottomAmps.max() ?? 1.0
        let peak = max(maxTop, maxBottom)
        let scale = peak > 0.01 ? (0.95 / peak) : 1.0

        let normTop = topAmps.map { max(0.04, min(0.96, $0 * scale)) }
        let normBottom = bottomAmps.map { max(0.04, min(0.96, $0 * scale)) }
        return WaveformEnvelope(top: normTop, bottom: normBottom)
    }

    /// Synchronously extract normalized peak heights from PNG image data.
    /// Returns an array of normalized amplitude values between 0.08 and 0.95.
    public static func extractHeights(from data: Data, sampleCount: Int = 95) -> [CGFloat]? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }

        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0, sampleCount > 0 else { return nil }

        let colorSpace = CGColorSpaceCreateDeviceGray()
        var rawBytes = [UInt8](repeating: 0, count: width * height * 2)
        guard let context = CGContext(
            data: &rawBytes,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 2,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        // Auto-detect polarity: if top-left corner is opaque (archive.org), signal is transparent cutout.
        // If top-left corner is transparent (phish.in), signal is opaque waveform.
        let cornerAlpha = rawBytes[1]
        let isInverted = cornerAlpha > 128

        let step = Double(width) / Double(sampleCount)
        var rawAmps: [CGFloat] = []
        rawAmps.reserveCapacity(sampleCount)

        for i in 0..<sampleCount {
            let startX = Int(Double(i) * step)
            let endX = min(Int(Double(i + 1) * step), width)
            var maxAmp: CGFloat = 0
            for x in startX..<max(startX + 1, endX) {
                var minY = height
                var maxY = -1
                for y in 0..<height {
                    let offset = (y * width + x) * 2
                    let alpha = rawBytes[offset + 1]
                    let isSignal = isInverted ? (alpha < 128) : (alpha > 128)
                    if isSignal {
                        if y < minY { minY = y }
                        if y > maxY { maxY = y }
                    }
                }
                if maxY >= minY {
                    let amp = CGFloat(maxY - minY + 1) / CGFloat(height)
                    if amp > maxAmp { maxAmp = amp }
                }
            }
            rawAmps.append(maxAmp)
        }

        let peak = rawAmps.max() ?? 1.0
        let scale = peak > 0.01 ? (0.95 / peak) : 1.0
        return rawAmps.map { max(0.08, min(0.95, $0 * scale)) }
    }
}

/// Dual-envelope structure representing upper and lower normalized amplitudes across a waveform.
public struct WaveformEnvelope: Sendable, Equatable {
    public let top: [CGFloat]
    public let bottom: [CGFloat]

    public init(top: [CGFloat], bottom: [CGFloat]) {
        self.top = top
        self.bottom = bottom
    }
}
