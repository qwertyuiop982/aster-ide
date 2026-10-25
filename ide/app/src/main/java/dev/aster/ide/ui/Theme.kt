package dev.aster.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.aster.ide.data.IdeSettings

val Mint = Color(0xFF70E1BD)
val Lilac = Color(0xFFBFA3F3)
val Sky = Color(0xFF7EC7EF)
val Amber = Color(0xFFEDB979)
val Coral = Color(0xFFF38B91)
val LocalDark = staticCompositionLocalOf { true }
val LocalAcs = staticCompositionLocalOf { false }

@Composable fun AsterTheme(settings: IdeSettings, content: @Composable () -> Unit) {
    val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
    val accent = when (settings.accent) { "blue" -> if (dark) Sky else Color(0xFF276FAC); "lilac" -> if (dark) Lilac else Color(0xFF7350B5); "amber" -> if (dark) Amber else Color(0xFF9A5D20); else -> if (dark) Mint else Color(0xFF087D5F) }
    val scheme = if (settings.uiStyle == dev.aster.ide.data.UiStyle.ACS) acsColorScheme(dark) else if (dark) darkColorScheme(
        primary = accent, onPrimary = Color(0xFF112820), primaryContainer = Color(0xFF243936),
        secondary = Lilac, background = Color(0xFF11141D), surface = Color(0xFF171B25),
        surfaceVariant = Color(0xFF202633), onBackground = Color(0xFFDDE3F0), onSurface = Color(0xFFDDE3F0),
        onSurfaceVariant = Color(0xFF929DB4), outline = Color(0xFF333C4C), outlineVariant = Color(0xFF272E3A),
        error = Coral, tertiary = Sky
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, primaryContainer = Color(0xFFD6EEE7), secondary = Color(0xFF7350B5),
        background = Color(0xFFFAFBFF), surface = Color(0xFFF2F4FA), surfaceVariant = Color(0xFFE7EBF3),
        onSurface = Color(0xFF273249), onBackground = Color(0xFF273249), onSurfaceVariant = Color(0xFF66728A),
        outline = Color(0xFFBAC3D4), outlineVariant = Color(0xFFD9DFEA), error = Color(0xFFBC3E4E)
    )
    val typography = Typography(
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
    )
    CompositionLocalProvider(LocalAcs provides (settings.uiStyle == dev.aster.ide.data.UiStyle.ACS), LocalDark provides dark, LocalMotion provides UiMotion(settings.animations, settings.reducedMotion)) { MaterialTheme(colorScheme = scheme, typography = typography, content = content) }
}
val Mono = FontFamily.Monospace
