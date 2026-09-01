package dev.mike.couchtour

/**
 * How long a cached catalog entry (periods, shows, show detail) stays fresh before the next
 * read re-fetches it (#61). Long enough that ordinary back-navigation within a session costs
 * no network round trip; short enough that a show's rating, popularity, or tags don't go
 * stale for the length of a whole sitting.
 */
internal const val CATALOG_CACHE_TTL_MS = 15 * 60 * 1000L

/**
 * A small in-memory, TTL-bounded cache shared by both [MusicSource] implementations (#61).
 * In-memory rather than on-disk was the explicit call for this pass — it's reversible and
 * needs no Room migration, unlike a persisted catalog cache would (see DECISIONS.md).
 *
 * [maxEntries] is the memory ceiling: a ~200-artist catalog with years and shows underneath
 * it means an unbounded shows/show-detail cache could grow without limit as a session goes
 * on, so the map evicts its least-recently-used entry once full rather than growing forever.
 *
 * Not private to its callers' fields, the same reasoning as
 * [RelistenCatalogSource.cachedArtists]: tests reset state between runs by calling [clear].
 */
internal class TtlCache<K, V>(
    private val ttlMillis: Long,
    private val maxEntries: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<V>(val value: V, val storedAt: Long)

    // LinkedHashMap in access order gives LRU eviction for free; synchronized because
    // MusicSource calls run from concurrent coroutines (e.g. NextStop fetching several
    // periods' shows in parallel).
    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>) =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAt >= ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, clock())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @get:Synchronized
    val size: Int
        get() = entries.size
}
