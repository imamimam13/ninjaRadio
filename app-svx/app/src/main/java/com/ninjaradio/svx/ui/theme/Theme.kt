package com.ninjaradio.svx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NinjaBackground = Color(0xFF121418)
val NinjaSurface = Color(0xFF1B1E26)
val NinjaCardBorder = Color(0xFF2C313E)
val NinjaPrimaryBlue = Color(0xFF4C9EEB)
val NinjaRxGreen = Color(0xFF4CAF50)
val NinjaTxBlue = Color(0xFF42A5F5)
val NinjaTxRed = Color(0xFFE53935)
val NinjaTextPrimary = Color(0xFFFFFFFF)
val NinjaTextSecondary = Color(0xFF9CA3AF)
val NinjaTextMuted = Color(0xFF6B7280)
val NinjaNavBg = Color(0xFF171A21)
val NinjaNavActive = Color(0xFF283141)

private val DarkColorScheme = darkColorScheme(
    primary = NinjaPrimaryBlue,
    secondary = NinjaRxGreen,
    tertiary = NinjaTxBlue,
    background = NinjaBackground,
    surface = NinjaSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = NinjaTextPrimary,
    onSurface = NinjaTextPrimary
)

@Composable
fun NinjaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
