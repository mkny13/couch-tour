package dev.mike.couchtour

import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers QR pairing (D144's follow-up): generation round-trips through a real zxing decode,
 *  and the scan-side matching rule is exercised directly rather than through ML Kit's
 *  `Barcode`, which isn't something a plain unit test can construct. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrTest {

    @Test
    fun `qrCodeBitmap round-trips the encoded text`() {
        val size = 128
        val pixels = IntArray(size * size)
        qrCodeBitmap("XBU3L7T7", sizePx = size).asAndroidBitmap()
            .getPixels(pixels, 0, size, 0, 0, size, size)

        val source = RGBLuminanceSource(size, size, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals("XBU3L7T7", decoded.text)
    }

    @Test
    fun `looksLikePairingCode accepts a valid code, case-insensitively`() {
        assertEquals("XBU3L7T7", looksLikePairingCode("xbu3l7t7"))
    }

    @Test
    fun `looksLikePairingCode rejects the wrong length`() {
        assertNull(looksLikePairingCode("XBU3L7T"))
    }

    @Test
    fun `looksLikePairingCode rejects characters outside the pairing alphabet`() {
        // '0' and '1' are deliberately excluded from BASE32_NO_AMBIGUOUS (crypto.ts) —
        // a scan that somehow produced one isn't a code this server could have issued.
        assertNull(looksLikePairingCode("XBU3L7T0"))
        assertNull(looksLikePairingCode("https://example.com"))
    }

    @Test
    fun `looksLikePairingCode rejects null`() {
        assertNull(looksLikePairingCode(null))
    }
}
