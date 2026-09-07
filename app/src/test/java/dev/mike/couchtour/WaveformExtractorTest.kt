package dev.mike.couchtour

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WaveformExtractorTest {

    @Test
    fun `extractHeights handles phish-in style transparent background`() {
        val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        // Corner (0,0) is transparent by default (alpha=0)
        // Draw signal in the middle columns
        for (x in 20..80) {
            for (y in 15..35) {
                bitmap.setPixel(x, y, Color.argb(255, 100, 100, 100))
            }
        }

        val heights = WaveformExtractor.extractHeights(bitmap, sampleCount = 10)
        assertNotNull(heights)
        assertEquals(10, heights!!.top.size)
        assertEquals(10, heights.bottom.size)
        // Middle samples should have higher amplitude than ends
        assertTrue(heights.top[5] >= heights.top[0])
    }

    @Test
    fun `extractHeights handles archive-org style inverted opaque background`() {
        val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        // Fill entire bitmap with opaque black
        for (x in 0 until 100) {
            for (y in 0 until 50) {
                bitmap.setPixel(x, y, Color.BLACK)
            }
        }
        // Carve transparent cutout in the middle
        for (x in 20..80) {
            for (y in 15..35) {
                bitmap.setPixel(x, y, Color.TRANSPARENT)
            }
        }

        val heights = WaveformExtractor.extractHeights(bitmap, sampleCount = 10)
        assertNotNull(heights)
        assertEquals(10, heights!!.top.size)
        assertEquals(10, heights.bottom.size)
        assertTrue(heights.top[5] >= heights.top[0])
    }
}
