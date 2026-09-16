package dev.mike.couchtour

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalReleaseHelperTest {

    @Test
    fun `spotify web url becomes deep link`() {
        val release = ExternalRelease(ExternalReleasePlatform.SPOTIFY, "https://open.spotify.com/album/3VNWJg...")
        val intent = ExternalReleaseHelper.buildIntent(release)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("spotify:album:3VNWJg...", intent.dataString)
    }

    @Test
    fun `tidal web url becomes deep link`() {
        val release = ExternalRelease(ExternalReleasePlatform.TIDAL, "https://tidal.com/browse/album/123456")
        val intent = ExternalReleaseHelper.buildIntent(release)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("tidal://album/123456", intent.dataString)
    }

    @Test
    fun `unrecognized url falls back to web`() {
        val release = ExternalRelease(ExternalReleasePlatform.SPOTIFY, "https://example.com/album/123")
        val intent = ExternalReleaseHelper.buildIntent(release)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/album/123", intent.dataString)
    }
}
