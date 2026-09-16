package dev.mike.couchtour

import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest

/**
 * The extracted normalized envelope (0.0 - 1.0) of an audio waveform.
 * Used by DesignComponents.kt to render the continuous solid silhouette scrubber.
 */
data class WaveformHeights(val top: FloatArray, val bottom: FloatArray)

/**
 * Extracts and caches peak envelopes from waveform images.
 * Supports phish.in (dark signal on transparent) and archive.org (transparent cutout on dark)
 * by auto-detecting polarity.
 */
object WaveformExtractor {
    private val cache = android.util.LruCache<String, WaveformHeights>(50)

    fun extractHeights(bitmap: android.graphics.Bitmap, sampleCount: Int = 400): WaveformHeights? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || sampleCount <= 0) return null

        // Auto-detect polarity: if corner pixel is opaque, signal is transparent cutout (archive.org)
        val cornerColor = bitmap.getPixel(0, 0)
        val isInverted = android.graphics.Color.alpha(cornerColor) > 128

        val topHeights = FloatArray(sampleCount)
        val bottomHeights = FloatArray(sampleCount)
        val step = width.toFloat() / sampleCount
        val centerY = height / 2

        for (i in 0 until sampleCount) {
            val startX = (i * step).toInt()
            val endX = minOf(((i + 1) * step).toInt(), width)
            var maxTop = 0
            var maxBottom = 0

            for (x in startX until maxOf(startX + 1, endX)) {
                // Find topmost signal pixel
                for (y in 0 until centerY) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = android.graphics.Color.alpha(pixel)
                    val isSignal = if (isInverted) alpha < 128 else alpha > 128
                    if (isSignal) {
                        val dist = centerY - y
                        if (dist > maxTop) maxTop = dist
                        break
                    }
                }
                // Find bottommost signal pixel
                for (y in height - 1 downTo centerY) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = android.graphics.Color.alpha(pixel)
                    val isSignal = if (isInverted) alpha < 128 else alpha > 128
                    if (isSignal) {
                        val dist = y - centerY
                        if (dist > maxBottom) maxBottom = dist
                        break
                    }
                }
            }
            topHeights[i] = maxTop.toFloat() / maxOf(1, centerY).toFloat()
            bottomHeights[i] = maxBottom.toFloat() / maxOf(1, centerY).toFloat()
        }

        val maxPeak = maxOf(topHeights.maxOrNull() ?: 1f, bottomHeights.maxOrNull() ?: 1f)
        val targetScale = if (maxPeak > 0.01f) (0.95f / maxPeak) else 1f
        for (i in 0 until sampleCount) {
            topHeights[i] = (topHeights[i] * targetScale).coerceIn(0.04f, 0.96f)
            bottomHeights[i] = (bottomHeights[i] * targetScale).coerceIn(0.04f, 0.96f)
        }
        return WaveformHeights(topHeights, bottomHeights)
    }

    suspend fun loadHeights(context: android.content.Context, url: String, sampleCount: Int = 400): WaveformHeights? {
        cache.get(url)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        val drawable = context.imageLoader.execute(request).drawable as? BitmapDrawable
        val bitmap = drawable?.bitmap ?: return null
        val extracted = extractHeights(bitmap, sampleCount) ?: return null
        cache.put(url, extracted)
        return extracted
    }
}
