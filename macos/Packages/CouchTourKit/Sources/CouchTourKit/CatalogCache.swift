import Foundation

/// How long a cached catalog entry (periods, shows, show detail) stays fresh before the next
/// read re-fetches it (#61). Long enough that ordinary back-navigation within a session costs
/// no network round trip; short enough that a show's rating, popularity, or tags don't go
/// stale for the length of a whole sitting. Port of `CatalogCache.kt`'s
/// `CATALOG_CACHE_TTL_MS`.
let catalogCacheTTL: TimeInterval = 15 * 60

/// A small in-memory, TTL-bounded cache shared by both `MusicSource` implementations (#61).
/// In-memory rather than on-disk was the explicit call for this pass — it's reversible and
/// needs no GRDB migration, unlike a persisted catalog cache would (see DECISIONS.md).
///
/// `maxEntries` is the memory ceiling: a ~200-artist catalog with years and shows underneath
/// it means an unbounded shows/show-detail cache could grow without limit as a session goes
/// on, so the store evicts its least-recently-used entry once full rather than growing
/// forever. An `actor` because, unlike `RelistenCatalogSource` (itself already an actor),
/// `PhishInSource` is a plain struct recreated on every `sourceFor` call — its cache has to
/// live somewhere shared and thread-safe on its own.
actor TTLCache<Key: Hashable & Sendable, Value: Sendable> {
    private struct Entry {
        let value: Value
        let storedAt: Date
    }

    private var entries: [Key: Entry] = [:]
    /// Access order, oldest first, for LRU eviction — the same shape as Android's
    /// `LinkedHashMap(accessOrder = true)`.
    private var order: [Key] = []
    private let ttl: TimeInterval
    private let maxEntries: Int
    private let now: () -> Date

    init(ttl: TimeInterval, maxEntries: Int, now: @escaping () -> Date = Date.init) {
        self.ttl = ttl
        self.maxEntries = maxEntries
        self.now = now
    }

    func get(_ key: Key) -> Value? {
        guard let entry = entries[key] else { return nil }
        if now().timeIntervalSince(entry.storedAt) >= ttl {
            entries.removeValue(forKey: key)
            order.removeAll { $0 == key }
            return nil
        }
        order.removeAll { $0 == key }
        order.append(key)
        return entry.value
    }

    func put(_ key: Key, _ value: Value) {
        if entries[key] == nil {
            order.append(key)
        } else {
            order.removeAll { $0 == key }
            order.append(key)
        }
        entries[key] = Entry(value: value, storedAt: now())
        while order.count > maxEntries {
            let evicted = order.removeFirst()
            entries.removeValue(forKey: evicted)
        }
    }

    func clear() {
        entries.removeAll()
        order.removeAll()
    }

    var count: Int { entries.count }
}
