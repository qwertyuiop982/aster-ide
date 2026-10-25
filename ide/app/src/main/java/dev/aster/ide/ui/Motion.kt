package dev.aster.ide.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class UiMotion(val enabled: Boolean = true, val reduced: Boolean = false) {
    val quick get() = if (!enabled) 0 else if (reduced) 90 else 120
    val standard get() = if (!enabled) 0 else if (reduced) 90 else 180
    val resize get() = if (!enabled || reduced) 0 else 220
    fun offset(pixels: Int) = if (enabled && !reduced) pixels else 0
}

val LocalMotion = staticCompositionLocalOf { UiMotion() }