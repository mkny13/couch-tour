import CouchTourKit

/// Which YouTube channel each artist's videos come from — the artist→channel seam #229
/// left as a parameter (D251). Curated by hand, keyed `backend:artistId`, because there is
/// no catalog API for it: YouTube channel IDs are opaque and only the owner knows which
/// channel is authoritative for a given tape source. An artist absent from the map has no
/// YouTube section at all — the artist page hides it rather than guessing a channel.
enum YouTubeChannels {
    private static let mappings: [String: String] = [
        // Phish's official channel, resolved from youtube.com/@phish's channel metadata.
        "phishin:phish": "UCDEPOd0RCvw8iSTqFpSBZLA",
    ]

    static func channel(for artist: ArtistRef) -> String? {
        mappings["\(artist.backend.rawValue):\(artist.id)"]
    }
}
