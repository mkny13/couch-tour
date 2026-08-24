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
class FillerTracksTest {

    @Before
    fun setUp() {
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `identifies standard filler titles`() {
        // Intros
        assertTrue(isFillerTrack("Intro"))
        assertTrue(isFillerTrack("intro"))
        assertTrue(isFillerTrack("Introduction"))
        assertTrue(isFillerTrack("Band Intro"))
        assertTrue(isFillerTrack("Band Introductions"))
        assertTrue(isFillerTrack("Crowd Intro"))
        assertTrue(isFillerTrack("Intro ->"))

        // Outros
        assertTrue(isFillerTrack("Outro"))
        assertTrue(isFillerTrack("Outroduction"))
        assertTrue(isFillerTrack("Band Outro"))

        // Tuning & Dead air
        assertTrue(isFillerTrack("Tuning"))
        assertTrue(isFillerTrack("Stage Tuning"))
        assertTrue(isFillerTrack("Tuning / Dead Air"))
        assertTrue(isFillerTrack("Tuning/Dead Air"))
        assertTrue(isFillerTrack("Dead Air"))
        assertTrue(isFillerTrack("Tuning ->"))
        assertTrue(isFillerTrack("Tuning >"))

        // Banter & Talk
        assertTrue(isFillerTrack("Banter"))
        assertTrue(isFillerTrack("Stage Banter"))
        assertTrue(isFillerTrack("Chatter"))
        assertTrue(isFillerTrack("Stage Talk"))

        // Crowd & Announcements
        assertTrue(isFillerTrack("Crowd"))
        assertTrue(isFillerTrack("Crowd Noise"))
        assertTrue(isFillerTrack("Crowd / Applause"))
        assertTrue(isFillerTrack("Applause"))
        assertTrue(isFillerTrack("Take A Step Back"))
        assertTrue(isFillerTrack("Take A Step Back / Tuning"))
        assertTrue(isFillerTrack("Stage Announcement"))
        assertTrue(isFillerTrack("Encore Break"))
    }

    @Test
    fun `does not identify genuine songs as filler`() {
        assertFalse(isFillerTrack("Divided Sky"))
        assertFalse(isFillerTrack("The Curtain With"))
        assertFalse(isFillerTrack("Tweezer Reprise"))
        assertFalse(isFillerTrack("Drums"))
        assertFalse(isFillerTrack("Space"))
        assertFalse(isFillerTrack("Playing in the Band"))
        assertFalse(isFillerTrack("Estimated Prophet"))
        assertFalse(isFillerTrack("St. Stephen"))
        assertFalse(isFillerTrack("Scarlet Begonias"))
        assertFalse(isFillerTrack("Morning Dew"))
        assertFalse(isFillerTrack("Dark Star"))
    }

    private data class SimpleTrack(val id: String, val title: String)

    @Test
    fun `filterPlaybackTracks when disabled returns original`() {
        val tracks = listOf(
            SimpleTrack("1", "Intro"),
            SimpleTrack("2", "Divided Sky"),
            SimpleTrack("3", "Tuning"),
            SimpleTrack("4", "Tweezer"),
        )

        val result = filterPlaybackTracks(tracks, 0, false) { it.title }
        assertEquals(4, result.items.size)
        assertEquals(0, result.startIndex)
    }

    @Test
    fun `filterPlaybackTracks when enabled starts at first non-filler`() {
        val tracks = listOf(
            SimpleTrack("1", "Intro"),
            SimpleTrack("2", "Divided Sky"),
            SimpleTrack("3", "Tuning"),
            SimpleTrack("4", "Tweezer"),
            SimpleTrack("5", "Outro"),
        )

        val result = filterPlaybackTracks(tracks, 0, true) { it.title }
        assertEquals(listOf("Divided Sky", "Tweezer"), result.items.map { it.title })
        assertEquals(0, result.startIndex)
    }

    @Test
    fun `filterPlaybackTracks when tapped non-filler maintains correct index`() {
        val tracks = listOf(
            SimpleTrack("1", "Intro"),
            SimpleTrack("2", "Divided Sky"),
            SimpleTrack("3", "Tuning"),
            SimpleTrack("4", "Tweezer"),
        )

        // User tapped Tweezer (index 3 in original list)
        val result = filterPlaybackTracks(tracks, 3, true) { it.title }
        assertEquals(listOf("Divided Sky", "Tweezer"), result.items.map { it.title })
        assertEquals(1, result.startIndex) // Tweezer is index 1 in filtered list
    }

    @Test
    fun `filterPlaybackTracks when explicitly tapped filler plays that filler`() {
        val tracks = listOf(
            SimpleTrack("1", "Intro"),
            SimpleTrack("2", "Divided Sky"),
            SimpleTrack("3", "Take A Step Back"),
            SimpleTrack("4", "Tweezer"),
            SimpleTrack("5", "Outro"),
        )

        // User explicitly tapped "Take A Step Back" (index 2)
        val result = filterPlaybackTracks(tracks, 2, true) { it.title }
        assertEquals(listOf("Divided Sky", "Take A Step Back", "Tweezer"), result.items.map { it.title })
        assertEquals(1, result.startIndex) // "Take A Step Back" is index 1 in filtered list
    }

    @Test
    fun `playback settings persistence and toggle`() {
        PlaybackSettings.setSkipFiller(false)
        assertFalse(PlaybackSettings.skipFiller.value)

        PlaybackSettings.toggle()
        assertTrue(PlaybackSettings.skipFiller.value)

        // Re-init on same context restores value
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertTrue(PlaybackSettings.skipFiller.value)

        PlaybackSettings.setSkipFiller(false)
        assertFalse(PlaybackSettings.skipFiller.value)
    }
}
