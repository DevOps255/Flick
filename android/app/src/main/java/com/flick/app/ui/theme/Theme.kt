package com.flick.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val colors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    background = Lavender,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    outline = Outline,
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp),
    labelMedium = TextStyle(fontSize = 11.sp, color = Muted),
)

@Composable
fun FlickTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
