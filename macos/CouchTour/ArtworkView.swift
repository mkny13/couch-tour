import SwiftUI

/// Show/track artwork, or a placeholder when there is none — the common case for Relisten,
/// whose show summaries carry no art at all. `AsyncImage` leans on URLSession's own cache
/// rather than a bespoke one; artwork is small and infrequently switched.
struct ArtworkView: View {
    let url: String?
    var size: CGFloat = 36

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
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size / 8))
    }

    private var placeholder: some View {
        RoundedRectangle(cornerRadius: size / 8)
            .fill(.quaternary)
            .overlay {
                Image(systemName: "music.note")
                    .foregroundStyle(.secondary)
                    .font(.system(size: size / 2.5))
            }
    }
}
