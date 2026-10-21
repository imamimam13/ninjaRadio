package com.ninjadmr.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Neon Holo-gear palette: brass hardware over a dark HUD field, cyan/magenta neon. */
object Holo {
    val bgDeep = Color(0xFF04070E)
    val bgGrid = Color(0xFF0A1322)
    val panel = Color(0xFF0B1220)
    val panelRaised = Color(0xFF101A2C)
    val panelEdge = Color(0xFF1D2B45)

    val brass = Color(0xFFC9973F)
    val brassBright = Color(0xFFE8C36A)
    val brassDim = Color(0xFF6E5526)
    val copper = Color(0xFFB87343)

    val cyan = Color(0xFF22E6FF)
    val cyanDim = Color(0xFF0E7A8C)
    val magenta = Color(0xFFFF2FD0)
    val magentaDim = Color(0xFF8A1A73)

    val green = Color(0xFF3DFF9E)
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF4D6D)
    val violet = Color(0xFF9B7BFF)

    val textPrimary = Color(0xFFE6F2FF)
    val textDim = Color(0xFF8FA3C0)
    val textFaint = Color(0xFF566884)

    val onNeon = Color(0xFF04070E)
}

private val HoloColors = darkColorScheme(
    primary = Holo.cyan,
    onPrimary = Holo.onNeon,
    primaryContainer = Holo.cyanDim,
    onPrimaryContainer = Holo.textPrimary,
    secondary = Holo.magenta,
    onSecondary = Holo.onNeon,
    secondaryContainer = Holo.magentaDim,
    onSecondaryContainer = Holo.textPrimary,
    tertiary = Holo.brassBright,
    onTertiary = Holo.onNeon,
    tertiaryContainer = Holo.brassDim,
    onTertiaryContainer = Holo.textPrimary,
    error = Holo.red,
    onError = Holo.onNeon,
    errorContainer = Color(0xFF5A1220),
    onErrorContainer = Color(0xFFFFD6DD),
    background = Holo.bgDeep,
    onBackground = Holo.textPrimary,
    surface = Holo.panel,
    onSurface = Holo.textPrimary,
    surfaceVariant = Holo.panelRaised,
    onSurfaceVariant = Holo.textDim,
    outline = Holo.panelEdge,
    outlineVariant = Holo.bgGrid,
    scrim = Color(0xCC04070E),
    inverseSurface = Holo.textPrimary,
    inverseOnSurface = Holo.bgDeep,
    inversePrimary = Holo.cyanDim,
    surfaceTint = Holo.cyan,
    surfaceBright = Holo.panelRaised,
    surfaceDim = Holo.bgDeep,
    surfaceContainer = Holo.panel,
    surfaceContainerHigh = Holo.panelRaised,
    surfaceContainerHighest = Color(0xFF16223A),
    surfaceContainerLow = Color(0xFF090F1B),
    surfaceContainerLowest = Holo.bgDeep,
)

private val HoloTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = 4.sp,
        color = Holo.textPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = 2.sp,
        color = Holo.textPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 1.sp,
        color = Holo.textPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
        color = Holo.textPrimary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        color = Holo.textDim,
    ),
)

private val LocalHolo = staticCompositionLocalOf { Holo }

object HoloTheme {
    val colors: Holo
        @Composable
        @ReadOnlyComposable
        get() = LocalHolo.current
}

@Composable
fun NinjaDmrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHolo provides Holo) {
        MaterialTheme(
            colorScheme = HoloColors,
            typography = HoloTypography,
            content = content,
        )
    }
}
