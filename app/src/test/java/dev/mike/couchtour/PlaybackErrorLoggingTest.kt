package dev.mike.couchtour

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackErrorLoggingTest {
    @Test
    fun errorFieldsCarryCodeCauseHostAndExtension() {
        val e = PlaybackException(
            "Source error",
            java.io.IOException("boom"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        val f = playbackErrorFields(e, "phish.in", "flac").toMap()
        assertEquals("ERROR_CODE_IO_UNSPECIFIED", f["code"])
        assertEquals("IOException: boom", f["cause"])
        assertEquals("phish.in", f["host"])
        assertEquals("flac", f["ext"])
    }

    @Test
    fun stateNames() {
        assertEquals("buffering", playbackStateName(Player.STATE_BUFFERING))
        assertEquals("unknown(9)", playbackStateName(9))
    }
}
