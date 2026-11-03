package com.kbs.tvremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Calm graphite palette: neutral surfaces, colour only where it carries meaning. */
object Palette {
    val Background = Color(0xFF0B0B0E)
    val Surface = Color(0xFF17171C)
    val SurfaceRaised = Color(0xFF212128)
    val SurfacePressed = Color(0xFF2C2C35)
    val Outline = Color(0xFF26262E)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xFF8E8E96)
    val Accent = Color(0xFF0A84FF)
    val Power = Color(0xFFFF453A)
    val Ready = Color(0xFF32D74B)
    val Waiting = Color(0xFFFFB340)
    val YouTube = Color(0xFFFF2D2D)
    val VideoApp = Color(0xFF4C7DFF)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceRaised,
    surfaceContainerHighest = Palette.SurfaceRaised,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

/**
 * The app's text is already large, so the phone's font scale is capped: at the largest system
 * settings, labels would otherwise overflow their buttons.
 */
private const val MAX_FONT_SCALE = 1.3f

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_FONT_SCALE))
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
