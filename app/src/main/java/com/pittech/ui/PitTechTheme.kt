package com.pittech.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

private val PitTechLightColors = lightColorScheme(
    primary = Color(0xFFB04D2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2D9CC),
    onPrimaryContainer = Color(0xFF3D2015),
    secondary = Color(0xFF58665A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3DA),
    onSecondaryContainer = Color(0xFF1E2B20),
    tertiary = Color(0xFF416C8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8E7EF),
    onTertiaryContainer = Color(0xFF102A3A),
    background = Color(0xFFF5F0E7),
    onBackground = Color(0xFF242622),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF242622),
    surfaceVariant = Color(0xFFEAE2D6),
    onSurfaceVariant = Color(0xFF514C45),
    surfaceDim = Color(0xFFE8E1D6),
    surfaceBright = Color(0xFFFFFCF6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF8F1),
    surfaceContainer = Color(0xFFF5F0E7),
    surfaceContainerHigh = Color(0xFFEFE8DE),
    surfaceContainerHighest = Color(0xFFE7DED1),
    surfaceTint = Color(0xFFB04D2E),
    outline = Color(0xFF716A61),
    outlineVariant = Color(0xFFE2D9CD),
    error = Color(0xFF9B332B),
    onError = Color.White,
    errorContainer = Color(0xFFF4DAD7),
    onErrorContainer = Color(0xFF410E0B),
)

private val PitTechDarkColors = darkColorScheme(
    primary = Color(0xFFD77954),
    onPrimary = Color(0xFF24150F),
    primaryContainer = Color(0xFF65341F),
    onPrimaryContainer = Color(0xFFF8DFD1),
    secondary = Color(0xFF9BAC98),
    onSecondary = Color(0xFF1B241D),
    secondaryContainer = Color(0xFF37443A),
    onSecondaryContainer = Color(0xFFDCE8D9),
    tertiary = Color(0xFF83B6CE),
    onTertiary = Color(0xFF12232D),
    tertiaryContainer = Color(0xFF294958),
    onTertiaryContainer = Color(0xFFD6ECF6),
    background = Color(0xFF1B1E1B),
    onBackground = Color(0xFFF5F0E7),
    surface = Color(0xFF242824),
    onSurface = Color(0xFFF5F0E7),
    surfaceVariant = Color(0xFF292D28),
    onSurfaceVariant = Color(0xFFB9B8B0),
    surfaceDim = Color(0xFF151815),
    surfaceBright = Color(0xFF393E38),
    surfaceContainerLowest = Color(0xFF111411),
    surfaceContainerLow = Color(0xFF1B1E1B),
    surfaceContainer = Color(0xFF242824),
    surfaceContainerHigh = Color(0xFF2B302B),
    surfaceContainerHighest = Color(0xFF353A34),
    surfaceTint = Color(0xFFD77954),
    outline = Color(0xFF899186),
    outlineVariant = Color(0xFF424840),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val PitTechTypography = Typography(
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
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = themeMode.usesDarkTheme(systemDarkTheme)
    val colorScheme = if (darkTheme) PitTechDarkColors else PitTechLightColors
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PitTechTypography,
        content = content,
    )
}
