package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
}
