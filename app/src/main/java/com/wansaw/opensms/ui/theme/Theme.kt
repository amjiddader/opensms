package com.wansaw.opensms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Blue = Color(0xFF0A84FF)
val Indigo = Color(0xFF5E5CE6)
val Purple = Color(0xFFBF5AF2)
val Pink = Color(0xFFFF375F)
val Green = Color(0xFF30D158)
val Orange = Color(0xFFFF9F0A)
val Red = Color(0xFFFF453A)
val Teal = Color(0xFF40C8E0)

/** Colours that Material's scheme has no slot for: the glass layers and the animated backdrop. */
@Immutable
class GlassColors(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val blobs: List<Color>,
    val fill: Color,
    val fillStrong: Color,
    val borderHi: Color,
    val borderLo: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
)

private val DarkGlass = GlassColors(
    dark = true,
    bgTop = Color(0xFF080B18),
    bgBottom = Color(0xFF160F2E),
    blobs = listOf(Color(0xFF3B6BFF), Color(0xFFA43BFF), Color(0xFF00C2A8), Color(0xFFFF4F8B)),
    fill = Color.White.copy(alpha = 0.085f),
    fillStrong = Color.White.copy(alpha = 0.16f),
    borderHi = Color.White.copy(alpha = 0.38f),
    borderLo = Color.White.copy(alpha = 0.04f),
    textPrimary = Color(0xFFF5F6FA),
    textSecondary = Color(0xFFA9AFC4),
    divider = Color.White.copy(alpha = 0.09f),
)

private val LightGlass = GlassColors(
    dark = false,
    bgTop = Color(0xFFE9EEFF),
    bgBottom = Color(0xFFFBEFF7),
    blobs = listOf(Color(0xFF8FB0FF), Color(0xFFD9A6FF), Color(0xFF8FE8D8), Color(0xFFFFB3CC)),
    fill = Color.White.copy(alpha = 0.52f),
    fillStrong = Color.White.copy(alpha = 0.78f),
    borderHi = Color.White.copy(alpha = 0.95f),
    borderLo = Color.White.copy(alpha = 0.25f),
    textPrimary = Color(0xFF14172B),
    textSecondary = Color(0xFF5E647C),
    divider = Color(0x14000000),
)

val LocalGlass = staticCompositionLocalOf { DarkGlass }

private val sans = FontFamily.SansSerif
private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

@Composable
fun OpenSmsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val g = if (dark) DarkGlass else LightGlass
    val scheme = if (dark) {
        darkColorScheme(
            primary = Blue, onPrimary = Color.White, secondary = Indigo, tertiary = Purple,
            background = g.bgTop, onBackground = g.textPrimary, surface = g.bgTop, onSurface = g.textPrimary,
            onSurfaceVariant = g.textSecondary, error = Red, outline = g.borderHi, outlineVariant = g.divider,
        )
    } else {
        lightColorScheme(
            primary = Blue, onPrimary = Color.White, secondary = Indigo, tertiary = Purple,
            background = g.bgTop, onBackground = g.textPrimary, surface = Color.White, onSurface = g.textPrimary,
            onSurfaceVariant = g.textSecondary, error = Red, outline = g.borderHi, outlineVariant = g.divider,
        )
    }
    CompositionLocalProvider(LocalGlass provides g) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
