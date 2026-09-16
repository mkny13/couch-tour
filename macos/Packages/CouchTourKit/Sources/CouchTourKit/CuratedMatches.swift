import Foundation

public struct CuratedMatches: Sendable {
    public static let shared = CuratedMatches()
    
    private let mappings: [String: ExternalRelease]
    
    private init() {
        if let url = Bundle.module.url(forResource: "curated_releases", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode([String: ExternalRelease].self, from: data) {
            self.mappings = decoded
        } else {
            self.mappings = [:]
        }
    }
    
    public func match(backend: Backend, artistId: String, date: String) -> ExternalRelease? {
        let key = "\(backend.rawValue):\(artistId):\(date)"
        return mappings[key]
    }
}
