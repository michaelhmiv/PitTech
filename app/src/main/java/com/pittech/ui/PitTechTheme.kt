package com.pittech.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * TURN-IN theme foundation (design pass 2026-10-26, BUILD-SPEC §3).
 *
 * Supersedes the #172 Night Service dark-only appearance: PitTech now ships
 * the TURN-IN token system in dark (the default) and light, selected by
 * [PitTechThemeMode]. The Material schemes below are valued from the
 * semantic tokens in [PitTechTokens.kt]; screens consume the tokens
 * directly via [pitTechTokens] as the translation slices land. Container
 * step values between the named tokens (surfaceDim, the mid containers,
 * error containers) are interpolated from the token steps and are the only
 * scheme values not taken verbatim from BUILD-SPEC §3.1.
 */
private val TurnInDarkColors = darkColorScheme(
    primary = Color(PitTechPalette.DARK_EMBER),
    onPrimary = Color(PitTechPalette.DARK_ON_ACCENT),
    primaryContainer = Color(PitTechPalette.DARK_SURFACE_RAISED),
    onPrimaryContainer = Color(PitTechPalette.DARK_INK),
    secondary = Color(PitTechPalette.DARK_COBALT),
    onSecondary = Color(PitTechPalette.DARK_ON_ACCENT),
    secondaryContainer = Color(PitTechPalette.DARK_SURFACE_RAISED),
    onSecondaryContainer = Color(PitTechPalette.DARK_INK),
    tertiary = Color(PitTechPalette.DARK_MARIGOLD),
    onTertiary = Color(PitTechPalette.DARK_ON_ACCENT),
    tertiaryContainer = Color(PitTechPalette.DARK_SURFACE_RAISED),
    onTertiaryContainer = Color(PitTechPalette.DARK_INK),
    background = Color(PitTechPalette.DARK_GROUND),
    onBackground = Color(PitTechPalette.DARK_INK),
    surface = Color(PitTechPalette.DARK_SURFACE),
    onSurface = Color(PitTechPalette.DARK_INK),
    surfaceVariant = Color(PitTechPalette.DARK_SURFACE_RAISED),
    onSurfaceVariant = Color(PitTechPalette.DARK_INK_MUTED),
    surfaceDim = Color(0xFF0B0F14),
    surfaceBright = Color(PitTechPalette.DARK_RULING),
    surfaceContainerLowest = Color(0xFF0B0F14),
    surfaceContainerLow = Color(PitTechPalette.DARK_GROUND),
    surfaceContainer = Color(PitTechPalette.DARK_SURFACE),
    surfaceContainerHigh = Color(PitTechPalette.DARK_SURFACE_RAISED),
    surfaceContainerHighest = Color(0xFF2E3A48),
    surfaceTint = Color(PitTechPalette.DARK_EMBER),
    outline = Color(PitTechPalette.DARK_RULING),
    outlineVariant = Color(PitTechPalette.DARK_SURFACE_RAISED),
    error = Color(PitTechPalette.DARK_FLAG),
    onError = Color(PitTechPalette.DARK_GROUND),
    errorContainer = Color(0xFF57221B),
    onErrorContainer = Color(PitTechPalette.DARK_INK),
)

private val TurnInLightColors = lightColorScheme(
    // BUILD-SPEC §3.1 fix: light primary actions are marigold fill + navy
    // text (the Area 06 f01 pattern), never ember fill + white text.
    primary = Color(PitTechPalette.LIGHT_MARIGOLD),
    onPrimary = Color(PitTechPalette.LIGHT_ON_ACCENT),
    primaryContainer = Color(PitTechPalette.LIGHT_SURFACE_RAISED),
    onPrimaryContainer = Color(PitTechPalette.LIGHT_INK),
    secondary = Color(PitTechPalette.LIGHT_COBALT),
    onSecondary = Color.White,
    secondaryContainer = Color(PitTechPalette.LIGHT_SURFACE_RAISED),
    onSecondaryContainer = Color(PitTechPalette.LIGHT_INK),
    tertiary = Color(PitTechPalette.LIGHT_EMBER_TEXT),
    onTertiary = Color.White,
    tertiaryContainer = Color(PitTechPalette.LIGHT_SURFACE_RAISED),
    onTertiaryContainer = Color(PitTechPalette.LIGHT_INK),
    background = Color(PitTechPalette.LIGHT_GROUND),
    onBackground = Color(PitTechPalette.LIGHT_INK),
    surface = Color(PitTechPalette.LIGHT_SURFACE),
    onSurface = Color(PitTechPalette.LIGHT_INK),
    surfaceVariant = Color(PitTechPalette.LIGHT_SURFACE_RAISED),
    onSurfaceVariant = Color(PitTechPalette.LIGHT_INK_MUTED),
    surfaceDim = Color(0xFFE9E2D2),
    surfaceBright = Color(PitTechPalette.LIGHT_SURFACE),
    surfaceContainerLowest = Color(PitTechPalette.LIGHT_SURFACE),
    surfaceContainerLow = Color(PitTechPalette.LIGHT_GROUND),
    surfaceContainer = Color(0xFFF7F3EA),
    surfaceContainerHigh = Color(PitTechPalette.LIGHT_SURFACE_RAISED),
    surfaceContainerHighest = Color(0xFFEAE3D0),
    surfaceTint = Color(PitTechPalette.LIGHT_MARIGOLD),
    outline = Color(PitTechPalette.LIGHT_RULING),
    outlineVariant = Color(PitTechPalette.LIGHT_RULING),
    error = Color(PitTechPalette.LIGHT_FLAG),
    onError = Color.White,
    errorContainer = Color(0xFFF8E4E0),
    onErrorContainer = Color(0xFF6E1D13),
)

private val PitTechTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = PitTechBannerFamily,
        fontWeight = FontWeight.Black,
        fontSize = 45.sp,
        lineHeight = 50.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PitTechTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PitTechTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PitTechTextFamily,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PitTechTextFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
)

@Composable
fun PitTechTheme(
    themeMode: PitTechThemeMode = PitTechThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = themeMode.usesDarkTheme(systemDarkTheme)
    val colorScheme = if (darkTheme) TurnInDarkColors else TurnInLightColors
    val tokens = if (darkTheme) PitTechDarkTokens else PitTechLightTokens
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalPitTechTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PitTechTypography,
            content = content,
        )
    }
}
