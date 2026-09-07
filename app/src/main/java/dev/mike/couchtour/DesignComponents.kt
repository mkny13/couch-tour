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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
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
    0.22f, 0.28f, 0.42f, 0.35f, 0.58f, 0.72f, 0.85f, 0.65f, 0.45f, 0.38f,
    0.52f, 0.68f, 0.90f, 0.95f, 0.80f, 0.60f, 0.42f, 0.30f, 0.48f, 0.62f,
    0.75f, 0.88f, 0.70f, 0.55f, 0.40f, 0.35f, 0.50f, 0.65f, 0.82f, 0.92f,
    0.85f, 0.68f, 0.52f, 0.38f, 0.45f, 0.60f, 0.78f, 0.86f, 0.74f, 0.58f,
    0.42f, 0.32f, 0.50f, 0.68f, 0.85f, 0.94f, 0.88f, 0.72f, 0.54f, 0.40f,
    0.48f, 0.64f, 0.80f, 0.89f, 0.76f, 0.60f, 0.45f, 0.35f, 0.52f, 0.70f,
    0.86f, 0.95f, 0.82f, 0.65f, 0.48f, 0.36f, 0.50f, 0.66f, 0.84f, 0.90f,
    0.78f, 0.62f, 0.44f, 0.32f, 0.46f, 0.62f, 0.79f, 0.88f, 0.75f, 0.58f,
    0.40f, 0.30f, 0.45f, 0.60f, 0.76f, 0.85f, 0.72f, 0.55f, 0.38f, 0.28f,
    0.40f, 0.52f, 0.65f, 0.48f, 0.32f
)

private val WAVEFORM_BOTTOM = floatArrayOf(
    0.20f, 0.26f, 0.38f, 0.32f, 0.54f, 0.68f, 0.80f, 0.60f, 0.42f, 0.35f,
    0.48f, 0.64f, 0.84f, 0.90f, 0.75f, 0.56f, 0.38f, 0.28f, 0.44f, 0.58f,
    0.70f, 0.82f, 0.65f, 0.50f, 0.36f, 0.32f, 0.46f, 0.60f, 0.76f, 0.86f,
    0.80f, 0.62f, 0.48f, 0.35f, 0.40f, 0.55f, 0.72f, 0.80f, 0.68f, 0.54f,
    0.38f, 0.28f, 0.46f, 0.62f, 0.79f, 0.88f, 0.82f, 0.66f, 0.50f, 0.36f,
    0.44f, 0.58f, 0.74f, 0.82f, 0.70f, 0.55f, 0.40f, 0.32f, 0.48f, 0.64f,
    0.80f, 0.88f, 0.76f, 0.60f, 0.44f, 0.32f, 0.46f, 0.60f, 0.78f, 0.84f,
    0.72f, 0.56f, 0.40f, 0.28f, 0.42f, 0.56f, 0.72f, 0.82f, 0.70f, 0.54f,
    0.36f, 0.26f, 0.40f, 0.55f, 0.70f, 0.79f, 0.66f, 0.50f, 0.35f, 0.25f,
    0.36f, 0.48f, 0.60f, 0.44f, 0.28f
)

/**
 * Interactive, seekable waveform scrubber rendering an organic continuous solid silhouette.
 */
@Composable
fun WaveformScrubber(
    progress: Float,
    onSeek: (Float) -> Unit,
    waveformUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var dynamicHeights by remember(waveformUrl) {
        mutableStateOf<WaveformHeights?>(null)
    }

    LaunchedEffect(waveformUrl) {
        dynamicHeights = if (waveformUrl != null) {
            WaveformExtractor.loadHeights(context, waveformUrl, 400)
        } else {
            null
        }
    }

    val topEnvelope = dynamicHeights?.top ?: WAVEFORM_TOP
    val bottomEnvelope = dynamicHeights?.bottom ?: WAVEFORM_BOTTOM

    val ledger = LocalLedgerColors.current
    val specBrush = ledger.specGradient
    val unplayedColor = ledger.textPrimary.copy(alpha = 0.20f)
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
        val count = topEnvelope.size
        if (count == 0) return@Canvas

        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val maxAmplitude = h * 0.44f
        val step = w / maxOf(1, count - 1).toFloat()

        // Build continuous silhouette path
        val path = Path().apply {
            moveTo(0f, centerY - topEnvelope[0] * maxAmplitude)
            for (i in 1 until count) {
                val x = i * step
                val y = centerY - topEnvelope[i] * maxAmplitude
                lineTo(x, y)
            }
            lineTo(w, centerY)
            for (i in (count - 1) downTo 0) {
                val x = i * step
                val bFrac = if (i < bottomEnvelope.size) bottomEnvelope[i] else topEnvelope[i]
                val y = centerY + bFrac * maxAmplitude
                lineTo(x, y)
            }
            close()
        }

        // 1. Draw unplayed full waveform
        drawPath(path = path, color = unplayedColor)

        // Center hairline
        val hairlineHeight = 1.5.dp.toPx()
        drawRect(
            color = unplayedColor,
            topLeft = Offset(0f, centerY - hairlineHeight / 2f),
            size = Size(w, hairlineHeight)
        )

        // 2. Draw played waveform with specGradient clipped to progress
        val playedWidth = (w * effectiveProgress.coerceIn(0f, 1f))
        if (playedWidth > 0f) {
            clipRect(left = 0f, top = 0f, right = playedWidth, bottom = h) {
                drawPath(path = path, brush = specBrush)
                val playedHairlineHeight = 2.dp.toPx()
                drawRect(
                    brush = specBrush,
                    topLeft = Offset(0f, centerY - playedHairlineHeight / 2f),
                    size = Size(w, playedHairlineHeight)
                )
            }
        }

        // 3. Draw playhead needle cursor matching design spec (#f3f5fe / 2px wide)
        if (playedWidth > 0f) {
            val needleWidth = 2.dp.toPx()
            val needleTop = 0f
            val needleHeight = h
            val needleX = (playedWidth - needleWidth / 2f).coerceIn(0f, w - needleWidth)
            drawRoundRect(
                color = Color(0xFFF3F5FE),
                topLeft = Offset(needleX, needleTop),
                size = Size(needleWidth, needleHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )
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

