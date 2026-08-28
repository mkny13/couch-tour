import SwiftUI

/// Show/track artwork, or a placeholder when there is none — the common case for Relisten,
/// whose show summaries carry no art at all. `AsyncImage` leans on URLSession's own cache
/// rather than a bespoke one; artwork is small and infrequently switched.
struct ArtworkView: View {
    let url: String?
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

    private var placeholder: some View {
        RoundedRectangle(cornerRadius: scaledSize / 8)
            .fill(.quaternary)
            .overlay {
                Image(systemName: "music.note")
                    .foregroundStyle(.secondary)
                    .font(.system(size: scaledSize / 2.5))
            }
    }
}
