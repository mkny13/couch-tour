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
class LikedTracksTest {

    @Before
    fun setUp() {
        LikedTracks.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `toggle likes and unlikes a track id`() {
        val id = "track-uuid-1"

        LikedTracks.toggle(id)
        assertEquals(setOf(id), LikedTracks.ids.value)

        LikedTracks.toggle(id)
        assertEquals(emptySet<String>(), LikedTracks.ids.value)
    }

    @Test
    fun `likes persist across a fresh init from the same context`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = "track-uuid-2"

        LikedTracks.init(context)
        LikedTracks.toggle(id)

        LikedTracks.init(context)
        assertEquals(setOf(id), LikedTracks.ids.value)
    }
}
