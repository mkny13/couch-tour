import CouchTourKit
import SwiftUI

/// Show/track artwork, or a placeholder when there is none — the common case for Relisten,
/// whose show summaries carry no art at all. `AsyncImage` leans on URLSession's own cache
/// rather than a bespoke one; artwork is small and infrequently switched.
struct ArtworkView: View {
    let url: String?
    /// Feeds `ShowArtworkGenerator`'s deterministic seed for the placeholder — nil at any call
    /// site just means a plainer, still-deterministic "CT" monogram on a generic gradient.
    var artist: String? = nil
    var date: String? = nil
    var size: CGFloat = 36

    /// Artwork grows with the system text size, so a card whose labels got bigger doesn't end
    /// up with a thumbnail that looks stranded beside them (#102). `@ScaledMetric` needs a
    /// literal default, so `size` is applied as a ratio against it.
    @ScaledMetric private var scaleReference: CGFloat = 100
    private var scaledSize: CGFloat { size * (scaleReference / 100) }

    var body: some View {
        Group {
            if let url, let imageURL = URL(string: url) {
                AsyncImage(url: imageURL) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    default:
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: scaledSize, height: scaledSize)
        .clipShape(RoundedRectangle(cornerRadius: scaledSize / 8))
        // The show/track identity beside every one of these already names it; announcing
        // "image" too would just add noise to every VoiceOver pass.
        .accessibilityHidden(true)
    }

    /// #62, decided 2026-08-31: a seeded gradient plus the artist monogram, not a port of
    /// Android's full cassette drawing — most call sites here are small chrome (the mini
    /// player, Home's cards) rather than a dedicated artwork screen. `ShowArtworkGenerator`
    /// supplies the palette, monogram, and date badge; this is just the SwiftUI fill for them.
    private var placeholder: some View {
        let palette = ShowArtworkGenerator.palette(forArtist: artist, date: date)
        return RoundedRectangle(cornerRadius: scaledSize / 8)
            .fill(
                LinearGradient(
                    colors: [palette.primaryColor.color, palette.backgroundColor.color],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .overlay {
                VStack(spacing: scaledSize / 16) {
                    Text(ShowArtworkGenerator.monogram(for: artist))
                        .font(.system(size: scaledSize / 3.2, weight: .bold, design: .rounded))
                        .foregroundStyle(palette.accentColor.color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                        .padding(.horizontal, scaledSize / 10)
                    // Skipped below ~60pt (the mini player's default 36) — there isn't room for
                    // a second line without it reading as noise rather than a date.
                    if scaledSize >= 60 {
                        Text(ShowArtworkGenerator.dateBadge(from: date))
                            .font(.system(size: scaledSize / 11, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.85))
                    }
                }
            }
    }
}

private extension ArtworkRGB {
    var color: Color { Color(red: red, green: green, blue: blue, opacity: opacity) }
}
