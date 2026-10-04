package com.hunternav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.app.Activity
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Cobalt,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = CobaltTint,
    onPrimaryContainer = CobaltDeep,
    secondary = Ink,
    onSecondary = Ivory,
    secondaryContainer = PaperShade,
    onSecondaryContainer = Ink,
    background = Ivory,
    onBackground = Ink,
    surface = IvoryElevated,
    onSurface = Ink,
    surfaceVariant = PaperShade,
    onSurfaceVariant = InkSecondary,
    error = AlertRed,
    onError = androidx.compose.ui.graphics.Color.White,
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

/** HunterNav light theme: bright, high-contrast, navigation-first. */
@Composable
fun HunterNavTheme(content: @Composable () -> Unit) {
    // The app is deliberately light-only (daylight motorcycle use).
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}
