package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedShowsTest {

    @Before
    fun setUp() {
        SavedShows.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `toggle saves and unsaves a show key`() {
        val key = "1997-11-17"

        SavedShows.toggle(key)
        assertTrue(SavedShows.contains(key))
        assertEquals(setOf(key), SavedShows.keys.value)

        SavedShows.toggle(key)
        assertFalse(SavedShows.contains(key))
        assertEquals(emptySet<String>(), SavedShows.keys.value)
    }

    @Test
    fun `saved shows persist across a fresh init from the same context`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "1994-06-18"

        SavedShows.init(context)
        SavedShows.toggle(key)

        SavedShows.init(context)
        assertTrue(SavedShows.contains(key))
        assertEquals(setOf(key), SavedShows.keys.value)
    }
}
