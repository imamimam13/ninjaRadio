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

/** Tactical Ninja Stealth palette matching NinjaRadio DV Platform. */
object Holo {
    val bgDeep = Color(0xFF090D16)
    val bgGrid = Color(0xFF101726)
    val panel = Color(0xCC111A2E)
    val panelRaised = Color(0xE618243E)
    val panelEdge = Color(0x387098D0)

    val brass = Color(0xFF38BDF8)
    val brassBright = Color(0xFF34D399)
    val brassDim = Color(0xFF059669)
    val copper = Color(0xFFFBBF24)

    val cyan = Color(0xFF38BDF8)
    val cyanDim = Color(0xFF0284C7)
    val magenta = Color(0xFFF43F5E)
    val magentaDim = Color(0xFFBE123C)

    val green = Color(0xFF34D399)
    val amber = Color(0xFFFBBF24)
    val red = Color(0xFFF43F5E)
    val violet = Color(0xFFA78BFA)

    val textPrimary = Color(0xFFF8FAFC)
    val textDim = Color(0xFF94A3B8)
    val textFaint = Color(0xFF64748B)

    val onNeon = Color(0xFF080C14)
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
