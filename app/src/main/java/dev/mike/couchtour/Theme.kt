package dev.mike.couchtour

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Couch Tour "Ledger" Design Tokens — high fidelity recreation of design/handoff/README.md.
 */
@Immutable
data class LedgerColors(
    val appBackground: Color,
    val elevatedBackground: Color,
    val cardSurface: Color,
    val listDivider: Color,
    val panelBorder: Color,
    val controlOutline: Color,
    val textPrimary: Color,
    val textHeadline: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val accentBase: Color,
    val accentIcon: Color,
    val accentTintText: Color,
    val ratingAmber: Color,
    val specGradient: Brush,
    val coverGradient: Brush,
    val isDark: Boolean,
)

private val SpecGradientBrush = Brush.horizontalGradient(
    listOf(
        Color(0xFF5B8CFF), // Blue
        Color(0xFF9184D9), // Purple
        Color(0xFFF06BB0), // Pink
        Color(0xFFF2A93B), // Amber
    )
)

val CoverArtBrush = Brush.linearGradient(
    listOf(
        Color(0xFFD97706), // Amber
        Color(0xFF991B1B), // Red
        Color(0xFF1E1B4B), // Indigo
    )
)

val DarkLedgerColors = LedgerColors(
    appBackground = Color(0xFF161826),
    elevatedBackground = Color(0xFF12141F),
    cardSurface = Color(0xFF1C1E2C),
    listDivider = Color(0xFF232532),
    panelBorder = Color(0xFF292B31),
    controlOutline = Color(0xFF3F424D),
    textPrimary = Color(0xFFE9E9ED),
    textHeadline = Color(0xFFF3F5FE),
    textSecondary = Color(0xFFCFD3E5),
    textMuted = Color(0xFF9397AB),
    textSubtle = Color(0xFF75798C),
    accentBase = Color(0xFF9184D9),
    accentIcon = Color(0xFFB5ABFC),
    accentTintText = Color(0xFFD2CEFD),
    ratingAmber = Color(0xFFF2A93B),
    specGradient = SpecGradientBrush,
    coverGradient = CoverArtBrush,
    isDark = true,
)

val LightLedgerColors = LedgerColors(
    appBackground = Color(0xFFFFFFFF),
    elevatedBackground = Color(0xFFF7F7FB),
    cardSurface = Color(0xFFF0F1F7),
    listDivider = Color(0xFFE4E7F5),
    panelBorder = Color(0xFFD7DAE8),
    controlOutline = Color(0xFFD7DAE8),
    textPrimary = Color(0xFF20222C),
    textHeadline = Color(0xFF20222C),
    textSecondary = Color(0xFF3F424D),
    textMuted = Color(0xFF767A8C),
    textSubtle = Color(0xFF9397AB),
    accentBase = Color(0xFF6F62C7),
    accentIcon = Color(0xFF5D5294),
    accentTintText = Color(0xFF5D5294),
    ratingAmber = Color(0xFFA06615),
    specGradient = SpecGradientBrush,
    coverGradient = CoverArtBrush,
    isDark = false,
)

val LocalLedgerColors = staticCompositionLocalOf { DarkLedgerColors }

private val CouchTourDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB5ABFC),
    onPrimary = Color(0xFF12141F),
    primaryContainer = Color(0xFF262447),
    onPrimaryContainer = Color(0xFFD2CEFD),
    secondary = Color(0xFF9184D9),
    onSecondary = Color(0xFF161826),
    secondaryContainer = Color(0xFF1C1E2C),
    onSecondaryContainer = Color(0xFFCFD3E5),
    tertiary = Color(0xFFF2A93B),
    onTertiary = Color(0xFF161826),
    background = Color(0xFF161826),
    onBackground = Color(0xFFE9E9ED),
    surface = Color(0xFF161826),
    onSurface = Color(0xFFE9E9ED),
    surfaceVariant = Color(0xFF1C1E2C),
    onSurfaceVariant = Color(0xFF9397AB),
    outline = Color(0xFF3F424D),
    outlineVariant = Color(0xFF232532),
)

private val CouchTourLightColorScheme = lightColorScheme(
    primary = Color(0xFF6F62C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECE9FC),
    onPrimaryContainer = Color(0xFF5D5294),
    secondary = Color(0xFF5D5294),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F1F7),
    onSecondaryContainer = Color(0xFF20222C),
    tertiary = Color(0xFFA06615),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF20222C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF20222C),
    surfaceVariant = Color(0xFFF0F1F7),
    onSurfaceVariant = Color(0xFF767A8C),
    outline = Color(0xFFD7DAE8),
    outlineVariant = Color(0xFFE4E7F5),
)

private val CouchTourTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).sp),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold),
)

private val CouchTourShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CouchTourTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val ledger = if (darkTheme) DarkLedgerColors else LightLedgerColors
    val colorScheme = if (darkTheme) CouchTourDarkColorScheme else CouchTourLightColorScheme

    CompositionLocalProvider(LocalLedgerColors provides ledger) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CouchTourTypography,
            shapes = CouchTourShapes,
            content = content,
        )
    }
}
