import Foundation

// YouTube Data API v3 client, mirroring the PhishInAPI/RelistenAPI shape: a stateless enum
// with a test-overridable `baseURL`, a private `get()` wrapping `URLSession.shared`, and
// DTOs that decode defensively. Only `search.list` is implemented — Part 1 (#229) fetches a
// channel's video list; duration needs a separate `videos.list` call and is deferred to the
// playback work (#231), which is why `YouTubeVideo.durationMs` stays optional here.

public struct YouTubeSearchResponse: Decodable, Equatable {
    public let items: [YouTubeVideoDTO]

    enum CodingKeys: String, CodingKey {
        case items
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decodeIfPresent([YouTubeVideoDTO].self, forKey: .items) ?? []
    }

    public init(items: [YouTubeVideoDTO] = []) {
        self.items = items
    }
}

/// Flattened `search.list` item: `id.videoId` plus the `snippet` fields the catalog needs.
/// Every field decodes with `decodeIfPresent` and a safe default, so a trimmed or extended
/// real-world payload never throws — the same style as `Show`/`Track` in PhishInAPI.swift.
public struct YouTubeVideoDTO: Decodable, Equatable {
    public let videoId: String
    public let title: String
    public let description: String?
    public let publishedAt: String?
    public let channelId: String?
    public let thumbnailURL: String?

    enum TopKeys: String, CodingKey {
        case id, snippet
    }

    enum IDKeys: String, CodingKey {
        case videoId
    }

    enum SnippetKeys: String, CodingKey {
        case title, description, publishedAt, channelId, thumbnails
    }

    enum ThumbKeys: String, CodingKey {
        case high, medium, `default`
    }

    enum ThumbURLKeys: String, CodingKey {
        case url
    }

    public init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: TopKeys.self)
        let id = try top.nestedContainer(keyedBy: IDKeys.self, forKey: .id)
        videoId = try id.decodeIfPresent(String.self, forKey: .videoId) ?? ""

        guard let snippet = try? top.nestedContainer(keyedBy: SnippetKeys.self, forKey: .snippet) else {
            title = ""
            description = nil
            publishedAt = nil
            channelId = nil
            thumbnailURL = nil
            return
        }
        title = try snippet.decodeIfPresent(String.self, forKey: .title) ?? ""
        description = try snippet.decodeIfPresent(String.self, forKey: .description)
        publishedAt = try snippet.decodeIfPresent(String.self, forKey: .publishedAt)
        channelId = try snippet.decodeIfPresent(String.self, forKey: .channelId)
        // Prefer the largest size present; search.list omits sizes unpredictably.
        thumbnailURL = try? Self.thumbnailURL(in: snippet)
    }

    private static func thumbnailURL(in snippet: KeyedDecodingContainer<SnippetKeys>) throws -> String? {
        guard let thumbs = try? snippet.nestedContainer(keyedBy: ThumbKeys.self, forKey: .thumbnails) else {
            return nil
        }
        for size in [ThumbKeys.high, .medium, .default] {
            if let thumb = try? thumbs.nestedContainer(keyedBy: ThumbURLKeys.self, forKey: size),
               let url = try thumb.decodeIfPresent(String.self, forKey: .url) {
                return url
            }
        }
        return nil
    }

    public init(
        videoId: String, title: String, description: String? = nil, publishedAt: String? = nil,
        channelId: String? = nil, thumbnailURL: String? = nil
    ) {
        self.videoId = videoId
        self.title = title
        self.description = description
        self.publishedAt = publishedAt
        self.channelId = channelId
        self.thumbnailURL = thumbnailURL
    }

    /// `search.list`'s `publishedAt` is an ISO-8601 string; nil (rather than a throw) on
    /// anything unparseable, so one malformed timestamp can't fail a whole channel listing.
    public var publishedAtDate: Date? {
        publishedAt.flatMap { Self.iso8601.date(from: $0) }
    }

    private static let iso8601 = ISO8601DateFormatter()
}

extension YouTubeVideoDTO {
    /// The `channelId` parameter is the seam Part 2 (#230) resolves through: the artist→
    /// channel mapping happens upstream of this mapping, falling back to the snippet's own.
    public func toYouTubeVideo(channelId overrideChannelId: String? = nil) -> YouTubeVideo {
        YouTubeVideo(
            id: videoId,
            title: title,
            thumbnailURL: thumbnailURL,
            durationMs: nil,
            publishedAt: publishedAtDate,
            channelId: overrideChannelId ?? self.channelId ?? "",
            description: description
        )
    }
}

public enum YouTubeAPI {
    private static let defaultBase = URL(string: "https://www.googleapis.com/youtube/v3")!

    /// Overridden by tests to point at a local mock server.
    public static var baseURL: URL = defaultBase

    /// Owner-supplied credential (D44 precedent — the builder does not register a Google
    /// Cloud project). Sent as `?key=` only when non-nil; the app target wires the real key.
    public static var apiKey: String?

    private static let decoder = JSONDecoder()

    private static func get(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIException("No HTTP response") }
        guard (200...299).contains(http.statusCode) else {
            throw APIException("HTTP \(http.statusCode)", code: http.statusCode)
        }
        return data
    }

    public static func search(channelId: String) async throws -> [YouTubeVideo] {
        var components = URLComponents(
            url: baseURL.appendingPathComponent("search"), resolvingAgainstBaseURL: false
        )!
        var query = [
            URLQueryItem(name: "part", value: "snippet"),
            URLQueryItem(name: "channelId", value: channelId),
            URLQueryItem(name: "type", value: "video"),
            URLQueryItem(name: "maxResults", value: "50"),
            URLQueryItem(name: "order", value: "date"),
        ]
        if let apiKey {
            query.append(URLQueryItem(name: "key", value: apiKey))
        }
        components.queryItems = query

        let data = try await get(components.url!)
        let response = try decoder.decode(YouTubeSearchResponse.self, from: data)
        return response.items.map { $0.toYouTubeVideo() }
    }
}
