package com.pittech.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Night Service — the single PitTech appearance (#172, owner-approved
 * 2026-10-24; dark-only, DD-7 rejected). Near-black green-charcoal grounds,
 * ember-orange primary, amber secondary, cream ink. There is deliberately
 * no light scheme: the tokens below are the whole system.
 */
private val NightServiceColors = darkColorScheme(
    primary = Color(0xFFFF7A45),
    onPrimary = Color(0xFF1D0E05),
    primaryContainer = Color(0xFF4A2A18),
    onPrimaryContainer = Color(0xFFFFD9C7),
    secondary = Color(0xFFFFC24B),
    onSecondary = Color(0xFF241A02),
    secondaryContainer = Color(0xFF3A2E12),
    onSecondaryContainer = Color(0xFFFFE3A3),
    tertiary = Color(0xFFA8B09E),
    onTertiary = Color(0xFF161A14),
    tertiaryContainer = Color(0xFF2C362A),
    onTertiaryContainer = Color(0xFFDCE8D9),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFF2EEE3),
    surface = Color(0xFF1A201A),
    onSurface = Color(0xFFF2EEE3),
    surfaceVariant = Color(0xFF242C24),
    onSurfaceVariant = Color(0xFFA8B09E),
    surfaceDim = Color(0xFF0B0E0A),
    surfaceBright = Color(0xFF2E362C),
    surfaceContainerLowest = Color(0xFF0B0E0A),
    surfaceContainerLow = Color(0xFF141814),
    surfaceContainer = Color(0xFF1A201A),
    surfaceContainerHigh = Color(0xFF242C24),
    surfaceContainerHighest = Color(0xFF2E362C),
    surfaceTint = Color(0xFFFF7A45),
    outline = Color(0xFF5A6656),
    outlineVariant = Color(0xFF333D31),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val PitTechTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 50.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
)

@Composable
fun PitTechTheme(
    themeMode: PitTechThemeMode = PitTechThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    // Night Service is dark-only (owner ruling 2026-10-24); themeMode is kept
    // in the signature for preference/restore plumbing and always resolves dark.
    @Suppress("UNUSED_VARIABLE") val darkTheme = themeMode.usesDarkTheme(systemIsDark = true)
    val colorScheme = NightServiceColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PitTechTypography,
        content = content,
    )
}
