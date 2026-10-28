package io.github.alamo3.openblue.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alamo3.openblue.R

/*
 * Design tokens.
 * The interface is neutral; colour is reserved for meaning: heat, cooling, charging, alerts.
 * One type family (Barlow) with its semi-condensed cut for large numerals.
 */

@Immutable
data class AppColors(
    val heat: Color,
    val cool: Color,
    val charge: Color,
    val alert: Color,
    val muted: Color,
    val hairline: Color,
    val raised: Color,
)

private val LightApp = AppColors(
    heat = Color(0xFFE85A2E),
    cool = Color(0xFF1F8FCE),
    charge = Color(0xFF1AA866),
    alert = Color(0xFFD63B3B),
    muted = Color(0xFF6A7178),
    hairline = Color(0xFFE1E4E7),
    raised = Color(0xFFECEEF0),
)

private val DarkApp = AppColors(
    heat = Color(0xFFFF7A4D),
    cool = Color(0xFF55B6EE),
    charge = Color(0xFF44D58C),
    alert = Color(0xFFFF6464),
    muted = Color(0xFF959CA3),
    hairline = Color(0xFF2B3035),
    raised = Color(0xFF262A2F),
)

val LocalAppColors = staticCompositionLocalOf { LightApp }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A1D21),
    onPrimary = Color(0xFFF5F6F7),
    secondary = Color(0xFF3C4249),
    onSecondary = Color.White,
    background = Color(0xFFF5F6F7),
    onBackground = Color(0xFF1A1D21),
    surface = Color(0xFFF5F6F7),
    onSurface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFFECEEF0),
    onSurfaceVariant = Color(0xFF6A7178),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFC9CED3),
    outlineVariant = Color(0xFFE1E4E7),
    error = Color(0xFFD63B3B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF1F3F5),
    onPrimary = Color(0xFF16181B),
    secondary = Color(0xFFC6CBD0),
    onSecondary = Color(0xFF16181B),
    background = Color(0xFF16181B),
    onBackground = Color(0xFFF1F3F5),
    surface = Color(0xFF16181B),
    onSurface = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFF262A2F),
    onSurfaceVariant = Color(0xFF959CA3),
    surfaceContainer = Color(0xFF1F2226),
    surfaceContainerHigh = Color(0xFF262A2F),
    outline = Color(0xFF3A4046),
    outlineVariant = Color(0xFF2B3035),
    error = Color(0xFFFF6464),
)

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

/** Semi-condensed cut, for big numerals (battery, temperature). */
val BarlowNumerals = FontFamily(
    Font(R.font.barlow_sc_medium, FontWeight.Medium),
    Font(R.font.barlow_sc_semibold, FontWeight.SemiBold),
)

// Scale steps of ~1.25 from a 16sp body.
private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = BarlowNumerals, fontWeight = FontWeight.SemiBold, fontSize = 72.sp, lineHeight = 72.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = BarlowNumerals, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = BarlowNumerals, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun OpenBlueTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkApp else LightApp) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}
