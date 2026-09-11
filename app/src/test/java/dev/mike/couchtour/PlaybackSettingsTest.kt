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

/**
 * The persisted playback preferences behind the Settings screen's PLAYBACK rows (#141).
 * Same trap [ThemeSettingsTest] guards against: the object is a process-wide singleton, so
 * every test re-reads its SharedPreferences through [PlaybackSettings.init] rather than
 * trusting whatever the previous test left in the flows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSettingsTest {

    @Before
    fun setUp() {
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `defaults are gapless-on and lossless`() {
        assertEquals(AudioQuality.LOSSLESS, PlaybackSettings.audioQuality.value)
        assertTrue(PlaybackSettings.gapless.value)
        assertEquals(false, PlaybackSettings.skipFiller.value)
    }

    @Test
    fun `audio quality updates and persists across init`() {
        PlaybackSettings.setAudioQuality(AudioQuality.COMPRESSED)
        assertEquals(AudioQuality.COMPRESSED, PlaybackSettings.audioQuality.value)

        // Re-init on same context restores value
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertEquals(AudioQuality.COMPRESSED, PlaybackSettings.audioQuality.value)

        PlaybackSettings.setAudioQuality(AudioQuality.LOSSLESS)
        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertEquals(AudioQuality.LOSSLESS, PlaybackSettings.audioQuality.value)
    }

    @Test
    fun `gapless updates and persists across init`() {
        PlaybackSettings.setGapless(false)
        assertFalse(PlaybackSettings.gapless.value)

        PlaybackSettings.init(ApplicationProvider.getApplicationContext())
        assertFalse(PlaybackSettings.gapless.value)
    }

    @Test
    fun `unknown stored quality falls back to lossless, like ThemeMode`() {
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.fromStorage(null))
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.fromStorage("flac-hi-fi-2027"))
    }
}