package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesTest {

    @Before
    fun setUp() {
        Favorites.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `toggle favorites and unfavorites an artist key`() {
        val key = ArtistRef(Backend.RELISTEN, "goose", "Goose").key

        Favorites.toggle(key)
        assertEquals(setOf(key), Favorites.keys.value)

        Favorites.toggle(key)
        assertEquals(emptySet<String>(), Favorites.keys.value)
    }

    @Test
    fun `favorites persist across a fresh init from the same context`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = PHISH.key

        Favorites.init(context)
        Favorites.toggle(key)

        Favorites.init(context)
        assertEquals(setOf(key), Favorites.keys.value)
    }

    @Test
    fun `changedSince includes tombstones and applyFromSync honors newer timestamps`() {
        val key = ArtistRef(Backend.RELISTEN, "goose", "Goose").key
        Favorites.toggle(key) // on
        val first = Favorites.changedSince(0).single()
        assertEquals(key, first.artistKey)
        assertEquals(null, first.deletedAt)

        Favorites.toggle(key) // off (tombstone)
        val second = Favorites.changedSince(first.updatedAt).single()
        assertEquals(key, second.artistKey)
        assertTrue(second.deletedAt != null)

        // Older remote write is ignored.
        Favorites.applyFromSync(
            listOf(FavoriteArtistSyncRow(artistKey = key, updatedAt = second.updatedAt - 1, deletedAt = null))
        )
        assertEquals(emptySet<String>(), Favorites.keys.value)

        // Newer remote write revives.
        Favorites.applyFromSync(
            listOf(FavoriteArtistSyncRow(artistKey = key, updatedAt = second.updatedAt + 1, deletedAt = null))
        )
        assertEquals(setOf(key), Favorites.keys.value)
    }

    @Test
    fun `legacy key migration persists rows json and preserves timestamps across init`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("favorites", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putStringSet("artist_keys", setOf(PHISH.key)).commit()

        Favorites.init(context)
        assertEquals(setOf(PHISH.key), Favorites.keys.value)
        val initialRows = Favorites.changedSince(0)
        assertEquals(1, initialRows.size)
        val initialTimestamp = initialRows.first().updatedAt

        val rawJson = prefs.getString("artist_rows_json", null)
        org.junit.Assert.assertNotNull(rawJson)

        // Subsequent init should preserve original timestamp, not re-mint fresh timestamp
        Favorites.init(context)
        val afterReinit = Favorites.changedSince(0)
        assertEquals(1, afterReinit.size)
        assertEquals(initialTimestamp, afterReinit.first().updatedAt)
    }

    @Test
    fun `legacy key migration does not revive newer remote tombstone on subsequent init`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("favorites", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putStringSet("artist_keys", setOf(PHISH.key)).commit()

        Favorites.init(context)
        val initialTimestamp = Favorites.changedSince(0).first().updatedAt

        // Newer remote tombstone arrives from sync
        val tombstoneTime = initialTimestamp + 1_000L
        Favorites.applyFromSync(
            listOf(FavoriteArtistSyncRow(artistKey = PHISH.key, updatedAt = tombstoneTime, deletedAt = tombstoneTime))
        )
        assertEquals(emptySet<String>(), Favorites.keys.value)

        // Re-init must not revive PHISH from legacy keys
        Favorites.init(context)
        assertEquals(emptySet<String>(), Favorites.keys.value)
        val rows = Favorites.changedSince(0)
        assertEquals(1, rows.size)
        assertEquals(tombstoneTime, rows.first().updatedAt)
        org.junit.Assert.assertNotNull(rows.first().deletedAt)
    }
}
