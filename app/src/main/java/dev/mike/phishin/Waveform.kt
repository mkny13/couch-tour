package dev.mike.phishin

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Scrubber that draws the track's waveform PNG from phish.in.
 *
 * The images are 1100x70 greyscale-plus-alpha, so the shape lives entirely in the alpha
 * channel. That lets us tint the same bitmap twice — once for the unplayed portion, once
 * clipped to the play position — instead of compositing a fixed-colour image.
 */
@Composable
fun WaveformScrubber(
    waveformUrl: String?,
    positionMs: Long,
    durationMs: Long,
    playedColor: Color,
    unplayedColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(waveformUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(waveformUrl) {
        bitmap = waveformUrl?.let { url ->
            // allowHardware(false): hardware bitmaps cannot be read back for Canvas drawing.
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            (context.imageLoader.execute(request).drawable as? BitmapDrawable)
                ?.bitmap?.asImageBitmap()
        }
    }

    val fraction =
        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) onSeek(positionAt(offset.x, size.width, durationMs))
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, _ ->
                    if (durationMs > 0) onSeek(positionAt(change.position.x, size.width, durationMs))
                }
            }
    ) {
        val image = bitmap ?: return@Canvas
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        drawImage(image, dstSize = dst, colorFilter = ColorFilter.tint(unplayedColor))
        clipRect(right = size.width * fraction) {
            drawImage(image, dstSize = dst, colorFilter = ColorFilter.tint(playedColor))
        }
    }
}
