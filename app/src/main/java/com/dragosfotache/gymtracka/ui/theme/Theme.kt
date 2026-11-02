package com.dragosfotache.gymtracka.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val GymDarkColorScheme = darkColorScheme(
    primary = GymAccent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF33446B),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = GymAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF33446B),
    onSecondaryContainer = Color(0xFFD9E4FF),
    tertiary = Color(0xFF9ECE6A),
    onTertiary = Color(0xFF182718),
    tertiaryContainer = Color(0xFF263E30),
    onTertiaryContainer = Color(0xFFC7E8CA),
    background = GymBackground,
    onBackground = Color.White,
    surface = GymBackground,
    onSurface = Color.White,
    surfaceVariant = GymSurface,
    onSurfaceVariant = Color(0xFFA7B1D9),
    surfaceDim = GymBackground,
    surfaceBright = Color(0xFF343B58),
    surfaceContainerLowest = Color(0xFF16161E),
    surfaceContainerLow = GymSurface,
    surfaceContainer = GymSurface,
    surfaceContainerHigh = Color(0xFF292E42),
    surfaceContainerHighest = Color(0xFF343B58),
    outline = GymBorder,
    outlineVariant = Color(0xFF414868),
    error = Color(0xFFE66367),
    onError = Color(0xFF360D1A),
    errorContainer = Color(0xFF542636),
    onErrorContainer = Color(0xFFFFD9DF),
    inverseSurface = Color(0xFFC0CAF5),
    inverseOnSurface = GymBackground,
    inversePrimary = Color(0xFF3458A0),
    surfaceTint = GymAccent,
)

@Composable
fun GymTrackaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GymDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
