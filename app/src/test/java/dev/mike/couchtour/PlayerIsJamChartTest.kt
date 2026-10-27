package dev.mike.couchtour

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerIsJamChartTest {
    private val info = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "Denver", art = null)

    private fun extrasFor(tags: List<Tag>) = mediaItem(
        Track(id = 1, title = "Tweezer", slug = "tweezer", mp3Url = "https://phish.in/t.mp3", tags = tags, audioStatus = "complete"),
        info
    ).mediaMetadata.extras

    @Test
    fun `reading a non-jam item's extras logs no ClassCastException`() {
        // is_jam_chart is stored as Boolean false here; a getString fallback only runs when
        // the Boolean read is false, which is what flooded logcat on every refresh.
        ShadowLog.clear()
        assertFalse(playerIsJamChart(extrasFor(emptyList())))
        val castLogs = ShadowLog.getLogs().filter { it.tag == "Bundle" && (it.throwable is ClassCastException || it.msg.contains("expected")) }
        assertTrue("Bundle logged: ${castLogs.map { it.msg }}", castLogs.isEmpty())
    }

    @Test
    fun `jam item reads true and missing extras read false`() {
        assertTrue(playerIsJamChart(extrasFor(listOf(Tag(name = "Jam Chart", notes = "Top tier version")))))
        assertFalse(playerIsJamChart(null))
    }
}
