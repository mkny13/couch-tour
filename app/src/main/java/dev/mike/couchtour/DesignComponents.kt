package dev.mike.couchtour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fixed-width 44dp type badge (LIST / SHOW / TRACK) for uniform horizontal alignment.
 */
@Composable
fun TypeBadge(
    type: String,
    modifier: Modifier = Modifier
) {
    val ledger = LocalLedgerColors.current
    val (textColor, borderColor) = when (type.uppercase()) {
        "LIST" -> Pair(
            ledger.accentTintText,
            if (ledger.isDark) Color(0x73B5ABFC) else Color(0x736F62C7)
        )
        "SHOW" -> Pair(
            ledger.ratingAmber,
            if (ledger.isDark) Color(0x73F2A93B) else Color(0x73A06615)
        )
        else -> Pair(
            ledger.textMuted,
            ledger.controlOutline
        )
    }

    Box(
        modifier = modifier
            .width(44.dp)
            .height(20.dp)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = type.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Couch Tour Cover Art placeholder gradient tile (amber -> red -> indigo).
 */
@Composable
fun CoverArtPlaceholder(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(CoverArtBrush)
    )
}

/**
 * 1dp or 2dp gradient hairline rule matching the stagelight spectrum.
 */
@Composable
fun GradientHairline(
    modifier: Modifier = Modifier,
    height: Dp = 1.dp,
    brush: Brush = LocalLedgerColors.current.specGradient
) {
    Box(
        modifier = modifier
            .height(height)
            .background(brush)
    )
}

/**
 * Circular play button with accent outline matching the Ledger design.
 */
@Composable
fun CircularPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    iconSize: Dp = 15.dp
) {
    val ledger = LocalLedgerColors.current
    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, ledger.accentIcon, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = ledger.accentTintText,
            modifier = Modifier.size(iconSize)
        )
    }
}

private val WAVEFORM_TOP = floatArrayOf(
    22.3f, 22.2f, 15.2f, 20.8f, 20.5f, 24.8f, 23.6f, 15.6f, 23.5f, 29.6f,
    26.1f, 28.4f, 15.2f, 17.0f, 14.0f, 13.2f, 15.6f, 14.8f, 26.1f, 20.2f,
    17.6f, 17.4f, 15.7f, 15.3f, 28.5f, 30.0f, 29.3f, 17.9f, 17.0f, 30.0f,
    24.2f, 21.4f, 29.3f, 17.2f, 20.7f, 18.6f, 21.1f, 21.5f, 15.2f, 18.2f,
    17.2f, 15.9f, 24.8f, 18.7f, 15.1f, 14.5f, 18.6f, 18.9f, 21.5f, 21.8f,
    21.0f, 21.0f, 25.1f, 13.3f, 21.9f, 16.9f, 12.9f, 12.7f, 19.5f, 13.0f,
    14.8f, 13.1f, 12.1f, 10.4f, 7.5f, 11.9f, 9.6f, 12.2f, 15.1f, 11.8f,
    17.4f, 15.7f, 10.7f, 17.5f, 13.0f, 21.9f, 18.2f, 13.5f, 11.4f, 8.1f,
    6.4f, 12.7f, 13.5f, 12.3f, 8.5f, 7.8f, 9.0f, 6.9f, 6.5f, 9.1f,
    9.9f, 15.0f, 20.5f, 22.1f, 10.6f, 11.4f, 18.3f, 16.7f, 22.3f, 18.1f,
    20.9f, 21.8f, 16.6f, 9.7f, 10.5f, 15.1f, 11.8f, 17.3f, 14.5f, 11.2f,
    10.7f, 13.6f, 14.8f, 16.6f, 21.3f, 23.6f, 24.3f, 19.7f, 18.2f, 15.5f,
    22.4f, 14.2f, 14.5f, 20.7f, 22.3f, 18.5f, 23.0f, 23.7f, 19.4f, 11.8f,
    13.7f, 13.1f, 17.9f, 17.8f, 19.7f, 19.5f, 24.9f, 22.6f, 20.3f, 21.8f,
    19.9f, 25.9f, 17.2f, 22.4f, 26.7f, 23.5f, 17.1f, 20.0f, 14.8f, 21.4f,
    12.6f, 20.9f, 12.6f, 9.9f, 14.2f, 18.3f, 16.0f, 23.0f, 23.0f, 22.5f,
    20.0f, 14.8f, 17.9f, 19.3f, 26.2f, 18.8f, 19.9f, 21.3f, 11.1f, 11.4f,
    16.3f, 12.0f, 10.9f, 16.3f, 11.7f, 13.9f, 6.5f, 9.6f, 8.2f, 9.7f,
    10.0f, 16.2f, 17.9f, 10.8f, 15.2f, 17.5f, 12.1f, 14.0f, 19.8f, 14.6f
)

private val WAVEFORM_BOTTOM = floatArrayOf(
    19.8f, 12.8f, 13.5f, 19.1f, 21.5f, 15.3f, 15.7f, 18.4f, 24.8f, 29.7f,
    29.7f, 25.5f, 14.0f, 11.9f, 17.6f, 15.4f, 20.5f, 14.1f, 24.5f, 17.1f,
    12.6f, 16.7f, 25.1f, 14.8f, 24.4f, 29.9f, 30.0f, 28.2f, 30.0f, 24.6f,
    22.8f, 26.5f, 21.2f, 21.4f, 27.8f, 15.2f, 19.6f, 22.9f, 19.9f, 11.9f,
    15.6f, 21.1f, 19.1f, 18.2f, 18.5f, 23.4f, 11.9f, 21.0f, 17.4f, 18.9f,
    27.6f, 19.5f, 27.8f, 19.0f, 20.3f, 18.3f, 18.0f, 18.9f, 13.9f, 12.9f,
    15.7f, 13.4f, 7.4f, 8.7f, 6.9f, 11.5f, 12.6f, 17.7f, 18.0f, 18.2f,
    14.0f, 12.8f, 14.9f, 16.6f, 20.6f, 13.5f, 18.4f, 19.0f, 13.1f, 10.2f,
    10.4f, 8.1f, 8.9f, 8.4f, 11.7f, 9.3f, 5.9f, 6.9f, 10.2f, 12.2f,
    9.5f, 15.7f, 16.1f, 14.7f, 11.6f, 17.8f, 14.0f, 16.0f, 19.6f, 23.7f,
    20.0f, 19.2f, 10.2f, 17.9f, 10.8f, 16.3f, 10.4f, 17.2f, 10.6f, 12.7f,
    16.4f, 20.1f, 14.1f, 14.4f, 16.0f, 16.3f, 15.5f, 29.9f, 30.0f, 29.0f,
    19.1f, 17.0f, 14.5f, 20.8f, 29.8f, 17.6f, 15.0f, 17.0f, 15.5f, 17.3f,
    20.6f, 14.5f, 18.3f, 15.7f, 17.4f, 22.3f, 22.5f, 16.5f, 21.1f, 29.7f,
    23.7f, 30.0f, 24.0f, 30.0f, 14.9f, 25.1f, 20.5f, 25.7f, 14.6f, 26.5f,
    14.3f, 18.7f, 9.7f, 12.0f, 17.9f, 15.6f, 21.0f, 14.7f, 16.6f, 12.8f,
    17.7f, 22.1f, 17.5f, 22.0f, 18.7f, 20.5f, 24.4f, 22.5f, 20.2f, 11.7f,
    14.4f, 14.5f, 9.9f, 15.3f, 13.9f, 10.4f, 7.6f, 8.9f, 8.5f, 11.1f,
    11.1f, 14.1f, 17.1f, 11.1f, 13.1f, 12.2f, 15.4f, 15.6f, 16.6f, 13.5f
)

/**
 * Interactive, seekable waveform scrubber mapped to the handoff waveform SVG vectors.
 */
@Composable
fun WaveformScrubber(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val ledger = LocalLedgerColors.current
    val specBrush = ledger.specGradient
    val unplayedColor = ledger.textMuted.copy(alpha = 0.45f)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val effectiveProgress = dragFraction ?: progress

    Canvas(
        modifier = modifier
            .height(64.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek(it) }
                        dragFraction = null
                    },
                    onDragCancel = {
                        dragFraction = null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            }
    ) {
        val totalBars = WAVEFORM_TOP.size
        val w = size.width
        val h = size.height
        val barPitch = w / totalBars
        val barWidth = (barPitch * 0.65f).coerceAtLeast(1.5f)
        val centerY = h / 2f
        val centerBarHeight = (h * (8f / 70f)).coerceAtLeast(2f)
        val centerTop = centerY - centerBarHeight / 2f
        val scaleY = (h / 70f)

        // 1. Draw unplayed full waveform
        drawRect(
            color = unplayedColor,
            topLeft = Offset(0f, centerTop),
            size = Size(w, centerBarHeight)
        )
        for (i in 0 until totalBars) {
            val x = i * barPitch
            val topH = WAVEFORM_TOP[i] * scaleY
            drawRect(
                color = unplayedColor,
                topLeft = Offset(x, centerTop - topH),
                size = Size(barWidth, topH)
            )
            val botH = WAVEFORM_BOTTOM[i] * scaleY
            drawRect(
                color = unplayedColor,
                topLeft = Offset(x, centerTop + centerBarHeight),
                size = Size(barWidth, botH)
            )
        }

        // 2. Draw played waveform with specGradient clipped to progress
        val playedWidth = (w * effectiveProgress.coerceIn(0f, 1f))
        if (playedWidth > 0f) {
            clipRect(left = 0f, top = 0f, right = playedWidth, bottom = h) {
                drawRect(
                    brush = specBrush,
                    topLeft = Offset(0f, centerTop),
                    size = Size(w, centerBarHeight)
                )
                for (i in 0 until totalBars) {
                    val x = i * barPitch
                    if (x > playedWidth) break
                    val topH = WAVEFORM_TOP[i] * scaleY
                    drawRect(
                        brush = specBrush,
                        topLeft = Offset(x, centerTop - topH),
                        size = Size(barWidth, topH)
                    )
                    val botH = WAVEFORM_BOTTOM[i] * scaleY
                    drawRect(
                        brush = specBrush,
                        topLeft = Offset(x, centerTop + centerBarHeight),
                        size = Size(barWidth, botH)
                    )
                }
            }
        }
    }
}

/**
 * 2dp bottom progress bar overlay for In Progress rows and mini-player.
 */
@Composable
fun ProgressBarOverlay(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 2.dp,
    useGradient: Boolean = true
) {
    val ledger = LocalLedgerColors.current
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(ledger.textPrimary.copy(alpha = 0.10f))
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(height)
                    .background(if (useGradient) ledger.specGradient else Brush.linearGradient(listOf(ledger.accentBase, ledger.accentBase)))
            )
        }
    }
}

/**
 * Jam Chart Note card with dismiss control matching the design handoff.
 */
@Composable
fun JamChartNoteCard(
    noteText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ledger = LocalLedgerColors.current
    val bgColor = if (ledger.isDark) Color(0xB8232532) else Color(0xD9E6E7F0)
    val borderColor = if (ledger.isDark) ledger.panelBorder else ledger.listDivider

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JAM CHART NOTE · PHISH.IN",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = ledger.textSubtle
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hide note",
                        tint = ledger.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = noteText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = ledger.textSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 40x22 toggle switch matching Ledger design handoff.
 */
@Composable
fun LedgerToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val ledger = LocalLedgerColors.current
    val trackColor = if (checked) {
        if (ledger.isDark) ledger.accentBase else Color(0xFF6F62C7)
    } else {
        if (ledger.isDark) ledger.controlOutline else ledger.panelBorder
    }
    val thumbColor = if (checked) Color(0xFFF3F5FE) else ledger.textMuted

    Box(
        modifier = modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

