package dev.mike.couchtour

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompareSourcesTest {

    @Test
    fun `CompareSourcesState round trips the original queue and play state`() {
        val q1 = MediaItem.Builder().setMediaId("q1").build()
        val q2 = MediaItem.Builder().setMediaId("q2").build()
        val originalQueue = listOf(q1, q2)

        val detail = ShowDetail(
            summary = ShowSummary(artist = PHISH, date = "1997-11-17", venue = "McNichols", location = null, tourName = null),
            recording = RecordingRef("r1", "SBD")
        )

        val state = CompareSourcesState(
            detail = detail,
            activeRecordingId = "r1",
            isComparing = true,
            originalQueue = originalQueue,
            originalTrackIndex = 1,
            originalPositionMs = 15000L,
            originalPlayWhenReady = true,
            comparisonItems = mapOf("r1" to q1, "r2" to q2)
        )

        assertEquals("r1", state.activeRecordingId)
        assertTrue(state.isComparing)
        assertEquals(2, state.originalQueue.size)
        assertEquals(1, state.originalTrackIndex)
        assertEquals(15000L, state.originalPositionMs)
        assertTrue(state.originalPlayWhenReady)
        assertEquals(2, state.comparisonItems.size)
    }

    @Test
    fun `SNIPPET_MS is exactly 15 seconds`() {
        assertEquals(15_000L, SNIPPET_MS)
    }
}
