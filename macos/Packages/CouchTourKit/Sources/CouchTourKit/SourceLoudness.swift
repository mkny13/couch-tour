import Foundation
import GRDB

/// A cached loudness measurement for one *source* — a whole show mix or a Relisten tape —
/// keyed by the queue-key grammar (`show:<date>`, `relisten:<artist>/<date>/<sourceId>`,
/// see QueueKey.swift). Volume leveling (#266) derives one static gain per source from it;
/// the meter itself is a later slice, this is only the cache it will write into.
///
/// Column names are snake_case, matching Android's `source_loudness` table (Room schema 12)
/// byte-for-byte so the two platforms' local caches are interchangeable by inspection.
/// Strictly local, derived data: never synced (a row can always be re-measured), and every
/// row carries `algorithmVersion` so a change to the meter makes old rows stale instead of
/// wrong — a row whose version doesn't match the current meter counts as a cache miss.
public struct SourceLoudness: Codable, Equatable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "source_loudness"

    /// Version of the meter that produced the current rows. Bump whenever the measurement
    /// changes meaningfully; rows written by an older meter are treated as absent.
    public static let currentAlgorithmVersion = 1

    public var key: String
    /// ITU-R BS.1770-4 integrated loudness of the source, in LUFS.
    public var lufs: Double
    /// Sample peak of the source, in dBFS — caps any boosting gain so it never clips.
    public var peakDb: Double
    /// How many tracks of the source were sampled into this one measurement.
    public var sampledTracks: Int
    /// Version of the meter that produced this row.
    public var algorithmVersion: Int
    /// Epoch milliseconds the measurement was taken, for cache eviction decisions later.
    public var measuredAt: Int64

    enum CodingKeys: String, CodingKey {
        case key = "leveling_key"
        case lufs
        case peakDb = "peak_db"
        case sampledTracks = "sampled_tracks"
        case algorithmVersion = "algorithm_version"
        case measuredAt = "measured_at"
    }

    public init(
        key: String, lufs: Double, peakDb: Double, sampledTracks: Int,
        algorithmVersion: Int = SourceLoudness.currentAlgorithmVersion,
        measuredAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.key = key
        self.lufs = lufs
        self.peakDb = peakDb
        self.sampledTracks = sampledTracks
        self.algorithmVersion = algorithmVersion
        self.measuredAt = measuredAt
    }
}
