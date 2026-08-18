package dev.mike.couchtour

import android.content.Intent
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sharing a show or track (#19), Relisten parity. [showShareUrl]/[trackShareUrl] (Catalog.kt)
 * and the text formatting here are pure and checked directly against the URL schemes
 * confirmed live against phish.in and relisten.net while grounding this issue; only the
 * actual [Intent] needs Robolectric to construct and inspect.
 */
class ShareUrlTest {

    private val dead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")

    @Test
    fun `phish in show url is just the date`() {
        assertEquals("https://phish.in/1997-11-22", showShareUrl(PHISH, "1997-11-22"))
    }

    @Test
    fun `relisten show url is the artist slug then the date`() {
        assertEquals(
            "https://relisten.net/grateful-dead/1977-05-08",
            showShareUrl(dead, "1977-05-08"),
        )
    }

    @Test
    fun `phish in track url appends the slug`() {
        assertEquals(
            "https://phish.in/1997-11-22/mikes-song",
            trackShareUrl(PHISH, "1997-11-22", "mikes-song"),
        )
    }

    @Test
    fun `phish in track url is null without a slug`() {
        assertNull(trackShareUrl(PHISH, "1997-11-22", null))
    }

    @Test
    fun `relisten has no per-track url, slug or not`() {
        assertNull(trackShareUrl(dead, "1977-05-08", null))
        assertNull(trackShareUrl(dead, "1977-05-08", "minglewood-blues"))
    }

    @Test
    fun `show share text is artist and date, then the url on its own line`() {
        assertEquals(
            "Phish · 1997-11-22\nhttps://phish.in/1997-11-22",
            showShareText(PHISH, "1997-11-22"),
        )
    }

    @Test
    fun `track share text uses the track url when one exists`() {
        assertEquals(
            "Mike's Song — Phish · 1997-11-22\nhttps://phish.in/1997-11-22/mikes-song",
            trackShareText(PHISH, "1997-11-22", "Mike's Song", "mikes-song"),
        )
    }

    @Test
    fun `track share text falls back to the show url when the backend has none`() {
        assertEquals(
            "Minglewood Blues — Grateful Dead · 1977-05-08\nhttps://relisten.net/grateful-dead/1977-05-08",
            trackShareText(dead, "1977-05-08", "Minglewood Blues", null),
        )
    }
}

/**
 * [launchShare] is only ever called with [ShareButton]'s `LocalContext.current`, which inside
 * `MainActivity`'s Compose tree is always the Activity itself — matching that here rather
 * than a bare application Context, since `startActivity` off a non-Activity Context needs
 * `FLAG_ACTIVITY_NEW_TASK` (confirmed live by this test failing without it) and adding that
 * flag defensively for a call shape that never happens would be exactly the kind of handling
 * for an impossible scenario CLAUDE.md asks not to add.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchShareTest {

    @Test
    fun `launchShare fires a plain-text ACTION_SEND wrapped in a chooser`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        launchShare(activity, "Phish · 1997-11-22\nhttps://phish.in/1997-11-22")

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val wrapped = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, wrapped.action)
        assertEquals("text/plain", wrapped.type)
        assertEquals(
            "Phish · 1997-11-22\nhttps://phish.in/1997-11-22",
            wrapped.getStringExtra(Intent.EXTRA_TEXT),
        )
    }
}
