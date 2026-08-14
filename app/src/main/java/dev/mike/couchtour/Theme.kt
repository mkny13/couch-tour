package dev.mike.couchtour

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Dark-only scheme built from the launcher icon's green (`colors.xml`'s
 * `ic_launcher_background`, #1B3A2F) rather than M3's baseline purple. The player's small text
 * used to sit on hardcoded `Color.Gray` (#888888) over `surfaceVariant` (#49454F) — a 2.9:1
 * contrast ratio, well under WCAG AA's 4.5:1. `onSurfaceVariant` here is #BFCEC7 on
 * `surfaceContainer` #1B2522, ~9:1.
 */
private val CouchTourDarkColors = darkColorScheme(
    primary = Color(0xFF7FD8AE),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF14523A),
    onPrimaryContainer = Color(0xFF9BF5C9),
    secondary = Color(0xFF9FC7B3),
    onSecondary = Color(0xFF0B2E22),
    secondaryContainer = Color(0xFF243B33),
    onSecondaryContainer = Color(0xFFC4E5D5),
    tertiary = Color(0xFFE4C88C),
    onTertiary = Color(0xFF3F2E04),
    tertiaryContainer = Color(0xFF594319),
    onTertiaryContainer = Color(0xFFFDE6B9),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE7EFEA),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFE7EFEA),
    surfaceVariant = Color(0xFF3F4A45),
    onSurfaceVariant = Color(0xFFBFCEC7),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF161E1B),
    surfaceContainer = Color(0xFF1B2522),
    surfaceContainerHigh = Color(0xFF26312D),
    surfaceContainerHighest = Color(0xFF313D38),
    outline = Color(0xFF89938D),
    outlineVariant = Color(0xFF3F4A45),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val CouchTourTypography = Typography(
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold),
)

private val CouchTourShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun CouchTourTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CouchTourDarkColors,
        typography = CouchTourTypography,
        shapes = CouchTourShapes,
        content = content,
    )
}
