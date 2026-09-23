package com.pittech.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val PitTechColors = lightColorScheme(
    primary = Color(0xFF7B4024),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEED8CA),
    onPrimaryContainer = Color(0xFF2A160D),
    secondary = Color(0xFF58645A),
    onSecondary = Color.White,
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF24231F),
    surface = Color(0xFFFFFEFB),
    onSurface = Color(0xFF24231F),
    surfaceVariant = Color(0xFFE9E4DA),
    onSurfaceVariant = Color(0xFF514C45),
    outline = Color(0xFF766F66),
    error = Color(0xFF9B332B),
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
fun PitTechTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PitTechColors,
        typography = PitTechTypography,
        content = content,
    )
}
