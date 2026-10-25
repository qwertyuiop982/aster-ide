package dev.aster.ide.ui

import kotlin.math.roundToInt

data class DockGeometry(val editorWidth: Int, val editorHeight: Int, val dockWidth: Int, val dockHeight: Int, val dockX: Int, val dockY: Int)

object DockPolicy {
    fun sideBySide(widthDp: Float, landscape: Boolean) = landscape && widthDp >= 600f

    fun measure(width: Int, height: Int, density: Float, side: Boolean, fraction: Float, progress: Float): DockGeometry {
        require(width >= 0 && height >= 0 && density.isFinite() && density > 0f)
        val shown = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val ratio = fraction.takeIf { it.isFinite() } ?: 0.36f
        if (side) {
            val minimumEditor = minOf((320 * density).roundToInt(), width / 2)
            val maximumDock = (width - minimumEditor).coerceAtLeast(0)
            val requested = (width * ratio.coerceIn(0.25f, 0.55f)).roundToInt().coerceIn(0, maximumDock)
            val preferred = requested.coerceAtLeast(minOf((260 * density).roundToInt(), maximumDock))
            val dockWidth = (preferred * shown).roundToInt().coerceIn(0, width)
            return DockGeometry(width - dockWidth, height, dockWidth, height, width - dockWidth, 0)
        }
        // Reserve the tab row, breadcrumb and keyboard toolbar above a usable editor.
        val minimumEditor = minOf((180 * density).roundToInt(), height / 2)
        val maximumDock = (height - minimumEditor).coerceAtLeast(0)
        val preferred = (height * ratio.coerceIn(0.25f, 0.75f)).roundToInt().coerceIn(0, maximumDock)
        val dockHeight = (preferred * shown).roundToInt().coerceIn(0, height)
        return DockGeometry(width, height - dockHeight, width, dockHeight, 0, height - dockHeight)
    }

    fun reflow(width: Int, height: Int, density: Float, sideFraction: Float, bottomFraction: Float, progress: Float, sideProgress: Float): DockGeometry {
        val bottom = measure(width, height, density, false, bottomFraction, progress)
        val right = measure(width, height, density, true, sideFraction, progress)
        val blend = sideProgress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        fun mix(a: Int, b: Int) = (a + (b - a) * blend).roundToInt()
        val dockWidth = mix(bottom.dockWidth, right.dockWidth)
        val dockHeight = mix(bottom.dockHeight, right.dockHeight)
        return DockGeometry(mix(bottom.editorWidth, right.editorWidth), mix(bottom.editorHeight, right.editorHeight),
            dockWidth, dockHeight, mix(bottom.dockX, right.dockX).coerceIn(0, width - dockWidth), mix(bottom.dockY, right.dockY).coerceIn(0, height - dockHeight))
    }
}
