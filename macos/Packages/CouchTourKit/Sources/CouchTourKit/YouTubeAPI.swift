import Foundation

public struct YouTubeSearchResponse: Decodable, Equatable {
    public let items: [YouTubeSearchResult]
    
    public init(items: [YouTubeSearchResult] = []) {
        self.items = items
    }
}

public struct YouTubeSearchResult: Decodable, Equatable {
    public let id: YouTubeVideoId
    public let snippet: YouTubeSnippet
    
    public init(id: YouTubeVideoId, snippet: YouTubeSnippet) {
        self.id = id
        self.snippet = snippet
    }
}

public struct YouTubeVideoId: Decodable, Equatable {
    public let videoId: String
    
    public init(videoId: String) {
        self.videoId = videoId
    }
}

public struct YouTubeSnippet: Decodable, Equatable {
    public let publishedAt: String?
    public let channelId: String
    public let title: String
    public let description: String?
    public let thumbnails: YouTubeThumbnails?
    
    public init(publishedAt: String? = nil, channelId: String, title: String, description: String? = nil, thumbnails: YouTubeThumbnails? = nil) {
        self.publishedAt = publishedAt
        self.channelId = channelId
        self.title = title
        self.description = description
        self.thumbnails = thumbnails
    }
}

public struct YouTubeThumbnails: Decodable, Equatable {
    public let `default`: YouTubeThumbnail?
    public let medium: YouTubeThumbnail?
    public let high: YouTubeThumbnail?
    
    public init(`default`: YouTubeThumbnail? = nil, medium: YouTubeThumbnail? = nil, high: YouTubeThumbnail? = nil) {
        self.default = `default`
        self.medium = medium
        self.high = high
    }
}

public struct YouTubeThumbnail: Decodable, Equatable {
    public let url: String
    
    public init(url: String) {
        self.url = url
    }
}

public typealias YouTubeVideoDTO = YouTubeSearchResult

extension YouTubeVideoDTO {
    public func toYouTubeVideo() -> YouTubeVideo {
        let thumb = snippet.thumbnails?.high?.url ?? snippet.thumbnails?.medium?.url ?? snippet.thumbnails?.default?.url
        return YouTubeVideo(
            id: id.videoId,
            title: snippet.title,
            thumbnailURL: thumb,
            durationMs: nil, // We don't get duration from search.list
            publishedAt: snippet.publishedAt,
            channelId: snippet.channelId,
            description: snippet.description
        )
    }
}

public enum YouTubeAPI {
    private static let defaultBase = URL(string: "https://www.googleapis.com/youtube/v3")!
    
    public static var baseURL: URL = defaultBase
    public static var apiKey: String?
    
    private static let decoder: JSONDecoder = JSONDecoder()
    
    private static func get(_ url: URL) async throws -> Data {
        let request = URLRequest(url: url)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIException("No HTTP response") }
        guard (200...299).contains(http.statusCode) else { throw APIException("HTTP \(http.statusCode)", code: http.statusCode) }
        return data
    }
    
    public static func search(channelId: String) async throws -> [YouTubeVideo] {
        var components = URLComponents(url: baseURL.appendingPathComponent("search"), resolvingAgainstBaseURL: false)!
        var queryItems = [
            URLQueryItem(name: "part", value: "snippet"),
            URLQueryItem(name: "channelId", value: channelId),
            URLQueryItem(name: "type", value: "video"),
            URLQueryItem(name: "maxResults", value: "50")
        ]
        if let key = apiKey {
            queryItems.append(URLQueryItem(name: "key", value: key))
        }
        components.queryItems = queryItems
        
        let data = try await get(components.url!)
        let response = try decoder.decode(YouTubeSearchResponse.self, from: data)
        return response.items.map { $0.toYouTubeVideo() }
    }
}
