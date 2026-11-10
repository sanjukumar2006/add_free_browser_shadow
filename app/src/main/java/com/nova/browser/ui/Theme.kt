package com.nova.browser.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nova.browser.data.ThemeMode

// Brave-inspired palette: signature orange accent, purple secondary, neutral grey surfaces.
val BraveOrange = Color(0xFF8B6CF0) // accent (name kept for compatibility): Shadow violet
val BravePurple = Color(0xFFA0A5EB)
val BraveDeepPurple = Color(0xFF6B4FBB)

internal val ShadowDark = darkColorScheme(
    primary = BraveOrange, onPrimary = Color.White, secondary = BravePurple,
    background = Color(0xFF1E2029), surface = Color(0xFF1E2029),
    surfaceVariant = Color(0xFF2B2E3B), onBackground = Color(0xFFF0F1F5), onSurface = Color(0xFFF0F1F5),
    onSurfaceVariant = Color(0xFFADB1C4), outline = Color(0xFF3C4050), error = Color(0xFFFF6B6B))
private val Light = lightColorScheme(
    primary = BraveOrange, onPrimary = Color.White, secondary = BraveDeepPurple,
    background = Color.White, surface = Color.White,
    surfaceVariant = Color(0xFFF1F3F5), onBackground = Color(0xFF212529), onSurface = Color(0xFF212529),
    onSurfaceVariant = Color(0xFF5E6175), outline = Color(0xFFDDE0E6), error = Color(0xFFD93025))

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp))

@Composable
fun NovaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) ShadowDark else Light, typography = Type, content = content)
}
