package dev.mike.couchtour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic color palette for procedural artwork.
 * Curated from vintage concert posters, psychedelic light shows, and cassette tape aesthetics.
 */
data class ArtworkPalette(
    val id: Int,
    val name: String,
    val backgroundStart: Long,
    val backgroundMid: Long,
    val backgroundEnd: Long,
    val accentColor: Long,
    val tapeShellColor: Long,
    val labelColor: Long,
    val textColor: Long,
    val subtextColor: Long,
    val reelColor: Long,
) {
    val bgStartColor: Color get() = Color(backgroundStart)
    val bgMidColor: Color get() = Color(backgroundMid)
    val bgEndColor: Color get() = Color(backgroundEnd)
    val accent: Color get() = Color(accentColor)
    val tapeShell: Color get() = Color(tapeShellColor)
    val label: Color get() = Color(labelColor)
    val text: Color get() = Color(textColor)
    val subtext: Color get() = Color(subtextColor)
    val reel: Color get() = Color(reelColor)
}

val ARTWORK_PALETTES = listOf(
    // 0. Cornell 77 Amber / Rust
    ArtworkPalette(
        id = 0,
        name = "Vintage Amber",
        backgroundStart = 0xFFD97706,
        backgroundMid = 0xFF991B1B,
        backgroundEnd = 0xFF1E1B4B,
        accentColor = 0xFFFBBF24,
        tapeShellColor = 0xFF18181B,
        labelColor = 0xFFFEF3C7,
        textColor = 0xFF18181B,
        subtextColor = 0xFF78350F,
        reelColor = 0xFFF3F4F6,
    ),
    // 1. Cosmic Cobalt / Indigo
    ArtworkPalette(
        id = 1,
        name = "Cosmic Cobalt",
        backgroundStart = 0xFF2563EB,
        backgroundMid = 0xFF4338CA,
        backgroundEnd = 0xFF0F172A,
        accentColor = 0xFF38BDF8,
        tapeShellColor = 0xFF0F172A,
        labelColor = 0xFFE0E7FF,
        textColor = 0xFF1E1B4B,
        subtextColor = 0xFF3730A3,
        reelColor = 0xFFE2E8F0,
    ),
    // 2. Forest Emerald / Jade
    ArtworkPalette(
        id = 2,
        name = "Forest Emerald",
        backgroundStart = 0xFF059669,
        backgroundMid = 0xFF065F46,
        backgroundEnd = 0xFF022C22,
        accentColor = 0xFFA7F3D0,
        tapeShellColor = 0xFF022C22,
        labelColor = 0xFFECFDF5,
        textColor = 0xFF064E3B,
        subtextColor = 0xFF047857,
        reelColor = 0xFFF0FDF4,
    ),
    // 3. Psychedelic Plum / Magenta
    ArtworkPalette(
        id = 3,
        name = "Psychedelic Plum",
        backgroundStart = 0xFFC026D3,
        backgroundMid = 0xFF701A75,
        backgroundEnd = 0xFF18181B,
        accentColor = 0xFFF472B6,
        tapeShellColor = 0xFF18181B,
        labelColor = 0xFFFDF4FF,
        textColor = 0xFF4A044E,
        subtextColor = 0xFF86198F,
        reelColor = 0xFFF5F3FF,
    ),
    // 4. Sunset Coral / Ochre
    ArtworkPalette(
        id = 4,
        name = "Sunset Coral",
        backgroundStart = 0xFFEA580C,
        backgroundMid = 0xFFBE123C,
        backgroundEnd = 0xFF1C1917,
        accentColor = 0xFFFDBA74,
        tapeShellColor = 0xFF1C1917,
        labelColor = 0xFFFFEDD5,
        textColor = 0xFF7C2D12,
        subtextColor = 0xFF9A3412,
        reelColor = 0xFFFFF7ED,
    ),
    // 5. Space Violet / Cyan
    ArtworkPalette(
        id = 5,
        name = "Space Violet",
        backgroundStart = 0xFF7C3AED,
        backgroundMid = 0xFF1E1B4B,
        backgroundEnd = 0xFF030712,
        accentColor = 0xFF22D3EE,
        tapeShellColor = 0xFF030712,
        labelColor = 0xFFEDE9FE,
        textColor = 0xFF2E1065,
        subtextColor = 0xFF5B21B6,
        reelColor = 0xFFF1F5F9,
    ),
    // 6. Midnight Teal / Ocean
    ArtworkPalette(
        id = 6,
        name = "Midnight Teal",
        backgroundStart = 0xFF0D9488,
        backgroundMid = 0xFF0E7490,
        backgroundEnd = 0xFF0F172A,
        accentColor = 0xFF5EEAD4,
        tapeShellColor = 0xFF0B192C,
        labelColor = 0xFFCCFBF1,
        textColor = 0xFF134E4A,
        subtextColor = 0xFF0F766E,
        reelColor = 0xFFF8FAFC,
    ),
    // 7. Crimson Velvet / Charcoal
    ArtworkPalette(
        id = 7,
        name = "Crimson Velvet",
        backgroundStart = 0xFFE11D48,
        backgroundMid = 0xFF881337,
        backgroundEnd = 0xFF18181B,
        accentColor = 0xFFFDA4AF,
        tapeShellColor = 0xFF18181B,
        labelColor = 0xFFFFE4E6,
        textColor = 0xFF4C0519,
        subtextColor = 0xFF9F1239,
        reelColor = 0xFFFDF2F8,
    ),
    // 8. Golden Era Ochre / Bronze
    ArtworkPalette(
        id = 8,
        name = "Golden Ochre",
        backgroundStart = 0xFFCA8A04,
        backgroundMid = 0xFF854D0E,
        backgroundEnd = 0xFF1C1917,
        accentColor = 0xFFFDE047,
        tapeShellColor = 0xFF1C1917,
        labelColor = 0xFFFEF9C3,
        textColor = 0xFF422006,
        subtextColor = 0xFF713F12,
        reelColor = 0xFFFFFBEB,
    ),
    // 9. Retro Cyber Aqua / Pink
    ArtworkPalette(
        id = 9,
        name = "Retro Cyber",
        backgroundStart = 0xFF06B6D4,
        backgroundMid = 0xFF9333EA,
        backgroundEnd = 0xFF0F172A,
        accentColor = 0xFFF43F5E,
        tapeShellColor = 0xFF0F172A,
        labelColor = 0xFFF0FDF4,
        textColor = 0xFF0F172A,
        subtextColor = 0xFF334155,
        reelColor = 0xFFF8FAFC,
    ),
    // 10. Warm Terracotta / Sand
    ArtworkPalette(
        id = 10,
        name = "Warm Terracotta",
        backgroundStart = 0xFFC2410C,
        backgroundMid = 0xFF78350F,
        backgroundEnd = 0xFF1C1917,
        accentColor = 0xFFFDBA74,
        tapeShellColor = 0xFF1C1917,
        labelColor = 0xFFFFF7ED,
        textColor = 0xFF431407,
        subtextColor = 0xFF9A3412,
        reelColor = 0xFFFAFAF9,
    ),
    // 11. Electric Jam Blue / Green
    ArtworkPalette(
        id = 11,
        name = "Electric Jam",
        backgroundStart = 0xFF2563EB,
        backgroundMid = 0xFF15803D,
        backgroundEnd = 0xFF052E16,
        accentColor = 0xFFA3E635,
        tapeShellColor = 0xFF052E16,
        labelColor = 0xFFF7FEE7,
        textColor = 0xFF14532D,
        subtextColor = 0xFF166534,
        reelColor = 0xFFF0FDF4,
    ),
    // 12. Deep Mauve / Slate
    ArtworkPalette(
        id = 12,
        name = "Deep Mauve",
        backgroundStart = 0xFF9D174D,
        backgroundMid = 0xFF475569,
        backgroundEnd = 0xFF0F172A,
        accentColor = 0xFFFBCFE8,
        tapeShellColor = 0xFF0F172A,
        labelColor = 0xFFF1F5F9,
        textColor = 0xFF1E293B,
        subtextColor = 0xFF475569,
        reelColor = 0xFFF8FAFC,
    ),
    // 13. Pacific Blue / Moss
    ArtworkPalette(
        id = 13,
        name = "Pacific Moss",
        backgroundStart = 0xFF0284C7,
        backgroundMid = 0xFF047857,
        backgroundEnd = 0xFF064E3B,
        accentColor = 0xFF6EE7B7,
        tapeShellColor = 0xFF064E3B,
        labelColor = 0xFFECFDF5,
        textColor = 0xFF064E3B,
        subtextColor = 0xFF047857,
        reelColor = 0xFFF0FDF4,
    ),
    // 14. Burnt Orange / Midnight
    ArtworkPalette(
        id = 14,
        name = "Burnt Midnight",
        backgroundStart = 0xFFEA580C,
        backgroundMid = 0xFF1E293B,
        backgroundEnd = 0xFF020617,
        accentColor = 0xFFFB923C,
        tapeShellColor = 0xFF020617,
        labelColor = 0xFFF8FAFC,
        textColor = 0xFF0F172A,
        subtextColor = 0xFF334155,
        reelColor = 0xFFF1F5F9,
    ),
    // 15. Neon Grape / Noir
    ArtworkPalette(
        id = 15,
        name = "Neon Grape",
        backgroundStart = 0xFF9333EA,
        backgroundMid = 0xFF3B0764,
        backgroundEnd = 0xFF09090B,
        accentColor = 0xFFC084FC,
        tapeShellColor = 0xFF09090B,
        labelColor = 0xFFFAF5FF,
        textColor = 0xFF3B0764,
        subtextColor = 0xFF6B21A8,
        reelColor = 0xFFF5F3FF,
    ),
)

/**
 * Builds a deterministic seed string from artist identifiers and show date.
 */
fun deriveArtworkSeed(
    artistKey: String? = null,
    artistName: String? = null,
    date: String? = null,
): String {
    val artistPart = (artistKey ?: artistName)?.trim()?.lowercase().orEmpty()
    val datePart = date?.trim().orEmpty()
    return when {
        artistPart.isNotEmpty() && datePart.isNotEmpty() -> "$artistPart:$datePart"
        artistPart.isNotEmpty() -> artistPart
        datePart.isNotEmpty() -> "couchtour:$datePart"
        else -> "couchtour:default"
    }
}

/**
 * 32-bit FNV-1a hash function for cross-platform deterministic hashing.
 */
fun hashArtworkSeed(seed: String): Int {
    var hash = -0x7ee3623b // 0x811c9dc5 as signed Int
    for (byte in seed.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (byte.toInt() and 0xff)
        hash = hash * 0x01000193
    }
    return hash
}

/**
 * Returns the deterministic palette mapped from seed.
 */
fun getArtworkPalette(seed: String): ArtworkPalette {
    val hash = hashArtworkSeed(seed)
    val index = ((hash % ARTWORK_PALETTES.size) + ARTWORK_PALETTES.size) % ARTWORK_PALETTES.size
    return ARTWORK_PALETTES[index]
}

/**
 * Derives a 2-character monogram from artist name (e.g. "Grateful Dead" -> "GD", "Phish" -> "PH").
 */
fun deriveArtistMonogram(artistName: String?): String {
    if (artistName.isNullOrBlank()) return "CT"
    val clean = artistName.trim()
    val words = clean.split(Regex("""[\s\-_·/]+""")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> {
            val first = words[0].first().uppercaseChar()
            val second = words[1].first().uppercaseChar()
            "$first$second"
        }
        clean.length >= 2 -> clean.take(2).uppercase()
        clean.isNotEmpty() -> clean.take(1).uppercase()
        else -> "CT"
    }
}

data class ArtworkDateComponents(
    val year: String,
    val monthDay: String,
    val fullBadge: String,
)

fun parseArtworkDateComponents(date: String?): ArtworkDateComponents {
    if (date.isNullOrBlank()) return ArtworkDateComponents("LIVE", "", "LIVE")
    val parts = date.trim().split("-")
    if (parts.size == 3 && parts[0].length == 4) {
        val y = parts[0]
        val m = parts[1].toIntOrNull() ?: 0
        val d = parts[2]
        val months = arrayOf("", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        val monthStr = if (m in 1..12) months[m] else parts[1]
        return ArtworkDateComponents(
            year = y,
            monthDay = "$monthStr $d",
            fullBadge = "$y · $monthStr $d"
        )
    }
    return ArtworkDateComponents(year = date, monthDay = "", fullBadge = date)
}

/**
 * Main artwork entry point.
 * Loads remote artwork image via Coil when [artUrl] is present.
 * Seamlessly falls back to [ProceduralShowArtwork] when [artUrl] is null, loading, or fails.
 */
@Composable
fun ShowArtwork(
    artUrl: String? = null,
    show: ShowSummary? = null,
    artistName: String? = null,
    date: String? = null,
    venue: String? = null,
    size: Dp? = null,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val resolvedArtUrl = artUrl ?: show?.artUrl
    val resolvedArtist = artistName ?: show?.artist?.name
    val resolvedDate = date ?: show?.date
    val resolvedVenue = venue ?: show?.venue

    val sizeModifier = if (size != null && size > 0.dp) Modifier.size(size) else Modifier

    if (!resolvedArtUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = resolvedArtUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.then(sizeModifier),
            loading = {
                ProceduralShowArtwork(
                    show = show,
                    artistName = resolvedArtist,
                    venue = resolvedVenue,
                    date = resolvedDate,
                    size = size,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            error = {
                ProceduralShowArtwork(
                    show = show,
                    artistName = resolvedArtist,
                    venue = resolvedVenue,
                    date = resolvedDate,
                    size = size,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    } else {
        ProceduralShowArtwork(
            show = show,
            artistName = resolvedArtist,
            venue = resolvedVenue,
            date = resolvedDate,
            size = size,
            modifier = modifier.then(sizeModifier),
        )
    }
}

/**
 * Overload for [ShowArtwork] taking a non-null [ShowSummary].
 */
@Composable
fun ShowArtwork(
    show: ShowSummary,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    contentDescription: String? = null,
) = ShowArtwork(
    artUrl = show.artUrl,
    show = show,
    artistName = show.artist.name,
    date = show.date,
    venue = show.venue,
    size = size,
    modifier = modifier,
    contentDescription = contentDescription,
)

/**
 * Deterministic procedural artwork generator composable.
 * Renders multi-stop gradient background with vintage tape reel accents, cassette shell graphics,
 * date badge, artist monogram, and venue typography.
 */
@Composable
fun ProceduralShowArtwork(
    show: ShowSummary? = null,
    artistName: String? = null,
    venue: String? = null,
    date: String? = null,
    size: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val artistKey = show?.artist?.key
    val resolvedArtist = artistName ?: show?.artist?.name
    val resolvedDate = date ?: show?.date
    val resolvedVenue = venue ?: show?.where?.ifBlank { null } ?: show?.venue

    val seed = remember(artistKey, resolvedArtist, resolvedDate) {
        deriveArtworkSeed(artistKey = artistKey, artistName = resolvedArtist, date = resolvedDate)
    }
    val palette = remember(seed) { getArtworkPalette(seed) }
    val dateInfo = remember(resolvedDate) { parseArtworkDateComponents(resolvedDate) }
    val monogram = remember(resolvedArtist) { deriveArtistMonogram(resolvedArtist) }

    val sizeModifier = if (size != null && size > 0.dp) Modifier.size(size) else Modifier

    BoxWithConstraints(
        modifier = modifier
            .then(sizeModifier)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val widthDp = maxWidth
        val isLarge = widthDp >= 180.dp
        val isMedium = widthDp in 70.dp..179.dp
        val isCompact = widthDp < 70.dp

        // 1. Procedural Vector Canvas (Gradient, Cassette Shell, Tape Window, Reels)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawProceduralCassette(
                palette = palette,
                isCompact = isCompact,
                isMedium = isMedium,
                isLarge = isLarge,
            )
        }

        // 2. Scaled Typography Overlay
        when {
            isLarge -> {
                LargeArtworkOverlay(
                    palette = palette,
                    dateInfo = dateInfo,
                    artistName = resolvedArtist ?: "LIVE ARCHIVE",
                    venue = resolvedVenue,
                    monogram = monogram,
                )
            }
            isMedium -> {
                MediumArtworkOverlay(
                    palette = palette,
                    dateInfo = dateInfo,
                    artistName = resolvedArtist ?: monogram,
                    venue = resolvedVenue,
                    monogram = monogram,
                )
            }
            else -> {
                CompactArtworkOverlay(
                    palette = palette,
                    dateInfo = dateInfo,
                    monogram = monogram,
                )
            }
        }
    }
}

/**
 * Overload for [ProceduralShowArtwork] taking a non-null [ShowSummary].
 */
@Composable
fun ProceduralShowArtwork(
    show: ShowSummary,
    modifier: Modifier = Modifier,
    size: Dp? = null,
) = ProceduralShowArtwork(
    show = show,
    artistName = show.artist.name,
    venue = show.venue,
    date = show.date,
    size = size,
    modifier = modifier,
)

/**
 * Canvas drawing routine for the cassette tape geometry and reels.
 */
private fun DrawScope.drawProceduralCassette(
    palette: ArtworkPalette,
    isCompact: Boolean,
    isMedium: Boolean,
    isLarge: Boolean,
) {
    val w = size.width
    val h = size.height
    val minDim = minOf(w, h)

    // Multi-stop gradient background
    val gradientBrush = Brush.linearGradient(
        colors = listOf(palette.bgStartColor, palette.bgMidColor, palette.bgEndColor),
        start = Offset(0f, 0f),
        end = Offset(w, h),
    )
    drawRect(brush = gradientBrush)

    // Subtle diagonal scanlines for vintage texture
    val scanlineCount = (minDim / 8f).toInt().coerceIn(12, 48)
    for (i in 0 until scanlineCount) {
        val y = i * (h / scanlineCount)
        drawLine(
            color = Color.White.copy(alpha = 0.035f),
            start = Offset(0f, y),
            end = Offset(w, y + (h / scanlineCount)),
            strokeWidth = 1f,
        )
    }

    if (isCompact) {
        // Compact mode: Mini cassette silhouette + dual reel spools
        val shellInsetX = w * 0.10f
        val shellInsetY = h * 0.16f
        val shellW = w - 2 * shellInsetX
        val shellH = h - 2 * shellInsetY
        val corner = minDim * 0.12f

        // Outer cassette body
        drawRoundRect(
            color = palette.tapeShell.copy(alpha = 0.88f),
            topLeft = Offset(shellInsetX, shellInsetY),
            size = Size(shellW, shellH),
            cornerRadius = CornerRadius(corner, corner),
        )
        drawRoundRect(
            color = palette.accent.copy(alpha = 0.40f),
            topLeft = Offset(shellInsetX, shellInsetY),
            size = Size(shellW, shellH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = (minDim * 0.025f).coerceAtLeast(1.5f)),
        )

        // Mini reels in center
        val reelRadius = shellH * 0.22f
        val centerY = shellInsetY + shellH * 0.5f
        val leftReelX = shellInsetX + shellW * 0.28f
        val rightReelX = shellInsetX + shellW * 0.72f

        // Connecting tape ribbon
        drawLine(
            color = Color(0xFF451A03),
            start = Offset(leftReelX, centerY),
            end = Offset(rightReelX, centerY),
            strokeWidth = reelRadius * 0.6f,
        )

        // Left & Right spools
        listOf(leftReelX, rightReelX).forEach { rx ->
            drawCircle(
                color = palette.reel,
                radius = reelRadius,
                center = Offset(rx, centerY),
            )
            drawCircle(
                color = palette.tapeShell,
                radius = reelRadius * 0.45f,
                center = Offset(rx, centerY),
            )
        }
        return
    }

    // Medium & Large layout: Detailed vintage cassette tape geometry
    val shellMarginX = w * 0.07f
    val shellMarginY = h * 0.12f
    val shellW = w - 2 * shellMarginX
    val shellH = h - 2 * shellMarginY
    val cornerRadius = minDim * 0.07f

    // Cassette shell body
    drawRoundRect(
        color = palette.tapeShell.copy(alpha = 0.92f),
        topLeft = Offset(shellMarginX, shellMarginY),
        size = Size(shellW, shellH),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.35f),
        topLeft = Offset(shellMarginX, shellMarginY),
        size = Size(shellW, shellH),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(width = (minDim * 0.015f).coerceAtLeast(1.5f)),
    )

    // Corner screws
    val screwOffset = minDim * 0.045f
    val screwRadius = (minDim * 0.018f).coerceAtLeast(1.5f)
    val screwColor = Color(0xFF94A3B8)
    val corners = listOf(
        Offset(shellMarginX + screwOffset, shellMarginY + screwOffset),
        Offset(shellMarginX + shellW - screwOffset, shellMarginY + screwOffset),
        Offset(shellMarginX + screwOffset, shellMarginY + shellH - screwOffset),
        Offset(shellMarginX + shellW - screwOffset, shellMarginY + shellH - screwOffset),
    )
    corners.forEach { c ->
        drawCircle(color = screwColor, radius = screwRadius, center = c)
        drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = screwRadius * 0.4f, center = c)
    }

    // Printed label sticker area
    val labelMarginX = shellMarginX + shellW * 0.04f
    val labelMarginY = shellMarginY + shellH * 0.06f
    val labelW = shellW * 0.92f
    val labelH = shellH * 0.42f
    val labelCorner = minDim * 0.035f

    drawRoundRect(
        color = palette.label,
        topLeft = Offset(labelMarginX, labelMarginY),
        size = Size(labelW, labelH),
        cornerRadius = CornerRadius(labelCorner, labelCorner),
    )

    // Label accent header stripe
    val stripeH = labelH * 0.12f
    drawRoundRect(
        color = palette.accent,
        topLeft = Offset(labelMarginX, labelMarginY),
        size = Size(labelW, stripeH),
        cornerRadius = CornerRadius(labelCorner, labelCorner),
    )

    // Lower tape window cutout
    val windowW = shellW * 0.68f
    val windowH = shellH * 0.34f
    val windowX = shellMarginX + (shellW - windowW) / 2f
    val windowY = shellMarginY + shellH * 0.55f
    val windowCorner = minDim * 0.04f

    drawRoundRect(
        color = Color(0xFF090A0F),
        topLeft = Offset(windowX, windowY),
        size = Size(windowW, windowH),
        cornerRadius = CornerRadius(windowCorner, windowCorner),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.15f),
        topLeft = Offset(windowX, windowY),
        size = Size(windowW, windowH),
        cornerRadius = CornerRadius(windowCorner, windowCorner),
        style = Stroke(width = 1f),
    )

    // Tape ribbon bridge behind reels
    val reelCenterY = windowY + windowH * 0.5f
    val leftReelCenterX = windowX + windowW * 0.26f
    val rightReelCenterX = windowX + windowW * 0.74f
    val tapeRibbonH = windowH * 0.65f

    drawRect(
        color = Color(0xFF3B1A0A),
        topLeft = Offset(leftReelCenterX, reelCenterY - tapeRibbonH / 2f),
        size = Size(rightReelCenterX - leftReelCenterX, tapeRibbonH),
    )

    // Tape Reel Spools with gear teeth
    val reelRadius = windowH * 0.38f
    listOf(leftReelCenterX, rightReelCenterX).forEach { rcx ->
        // Outer white reel hub
        drawCircle(
            color = palette.reel,
            radius = reelRadius,
            center = Offset(rcx, reelCenterY),
        )
        // Inner dark spindle
        drawCircle(
            color = Color(0xFF0F172A),
            radius = reelRadius * 0.50f,
            center = Offset(rcx, reelCenterY),
        )
        // Spool teeth/cogs (6 spokes)
        val spokeCount = 6
        for (i in 0 until spokeCount) {
            val angle = i * (2 * Math.PI / spokeCount)
            val toothInner = reelRadius * 0.45f
            val toothOuter = reelRadius * 0.78f
            val startOffset = Offset(
                (rcx + toothInner * cos(angle)).toFloat(),
                (reelCenterY + toothInner * sin(angle)).toFloat(),
            )
            val endOffset = Offset(
                (rcx + toothOuter * cos(angle)).toFloat(),
                (reelCenterY + toothOuter * sin(angle)).toFloat(),
            )
            drawLine(
                color = Color(0xFF334155),
                start = startOffset,
                end = endOffset,
                strokeWidth = (minDim * 0.012f).coerceAtLeast(1.2f),
            )
        }
        // Center spindle hole
        drawCircle(
            color = Color(0xFFF8FAFC),
            radius = reelRadius * 0.22f,
            center = Offset(rcx, reelCenterY),
        )
    }

    // Bottom trapezoid guide notch
    val trapW1 = shellW * 0.48f
    val trapW2 = shellW * 0.38f
    val trapH = shellH * 0.08f
    val trapTop = shellMarginY + shellH - trapH - minDim * 0.01f
    val trapCenterX = w / 2f

    val trapPath = Path().apply {
        moveTo(trapCenterX - trapW1 / 2f, trapTop + trapH)
        lineTo(trapCenterX - trapW2 / 2f, trapTop)
        lineTo(trapCenterX + trapW2 / 2f, trapTop)
        lineTo(trapCenterX + trapW1 / 2f, trapTop + trapH)
        close()
    }
    drawPath(path = trapPath, color = Color.Black.copy(alpha = 0.45f))
    drawPath(path = trapPath, color = palette.accent.copy(alpha = 0.25f), style = Stroke(1f))
}

/**
 * Large artwork overlay (NowPlayingScreen).
 */
@Composable
private fun LargeArtworkOverlay(
    palette: ArtworkPalette,
    dateInfo: ArtworkDateComponents,
    artistName: String,
    venue: String?,
    monogram: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top row: Date Badge + Monogram pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = palette.accent,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = dateInfo.fullBadge,
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Surface(
                color = palette.tapeShell.copy(alpha = 0.85f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = monogram,
                    color = palette.accent,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // Label typography area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = artistName.uppercase(),
                color = palette.text,
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!venue.isNullOrBlank()) {
                Text(
                    text = venue,
                    color = palette.subtext,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        // Bottom footer: Audio Source Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = "RELISTEN · MASTER TAPE",
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Medium artwork overlay (AnniversaryCard, ResumeCard, RecordingHeader).
 */
@Composable
private fun MediumArtworkOverlay(
    palette: ArtworkPalette,
    dateInfo: ArtworkDateComponents,
    artistName: String,
    venue: String?,
    monogram: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header: Date badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = palette.accent,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = dateInfo.year,
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            if (dateInfo.monthDay.isNotBlank()) {
                Text(
                    text = dateInfo.monthDay,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                )
            }
        }

        // Label typography
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = artistName,
                color = palette.text,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!venue.isNullOrBlank()) {
                Text(
                    text = venue,
                    color = palette.subtext,
                    fontWeight = FontWeight.Medium,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Compact artwork overlay (MiniPlayer, RowItem).
 */
@Composable
private fun CompactArtworkOverlay(
    palette: ArtworkPalette,
    dateInfo: ArtworkDateComponents,
    monogram: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.accent,
            shape = RoundedCornerShape(3.dp),
        ) {
            Text(
                text = if (dateInfo.year.length == 4) dateInfo.year.takeLast(2) else monogram,
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
