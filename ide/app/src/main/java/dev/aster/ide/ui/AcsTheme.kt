package dev.aster.ide.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** ACS surfaces and actions are monochrome; diagnostics retain semantic colors. */
fun acsColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF292929), onPrimaryContainer = Color.White,
    secondary = Color(0xFFE5E5E5), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF242424), onSecondaryContainer = Color.White,
    tertiary = Color(0xFFD4D4D4), onTertiary = Color.Black,
    background = Color.Black, onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF0C0C0C), onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent, outline = Color(0xFF717171), outlineVariant = Color(0xFF333333),
    inverseSurface = Color.White, inverseOnSurface = Color.Black, inversePrimary = Color.Black,
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
) else lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E9E9), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF252525), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0), onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF404040), onTertiary = Color.White,
    background = Color.White, onBackground = Color(0xFF171717),
    surface = Color.White, onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF3F3F3), onSurfaceVariant = Color(0xFF626262),
    surfaceTint = Color.Transparent, outline = Color(0xFF929292), outlineVariant = Color(0xFFDEDEDE),
    inverseSurface = Color.Black, inverseOnSurface = Color.White, inversePrimary = Color.White,
    error = Color(0xFFB3261E), onError = Color.White
)
