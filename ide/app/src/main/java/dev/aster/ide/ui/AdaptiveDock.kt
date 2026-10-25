package dev.aster.ide.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints

@Composable fun AdaptiveDock(side: Boolean, visible: Boolean, expanded: Boolean, agent: Boolean, agentFraction: Float = 0.36f, modifier: Modifier = Modifier, editor: @Composable () -> Unit, dock: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val progress by animateFloatAsState(if (visible) 1f else 0f, tween(motion.resize), label = "dockVisibility")
    val axis by animateFloatAsState(if (side) 1f else 0f, tween(motion.resize), label = "dockReflow")
    val sideFraction by animateFloatAsState(if (expanded) 0.5f else if (agent) agentFraction else 0.36f, tween(motion.resize), label = "dockWidth")
    val bottomFraction by animateFloatAsState(if (expanded) 0.7f else if (agent) 0.55f else 1f / 3f, tween(motion.resize), label = "dockHeight")
    // Both slots retain their composition and AndroidView when the layout changes axis.
    Layout(content = {
        Box(Modifier.fillMaxSize().clipToBounds()) { editor() }
        Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = progress }.then(if (!visible) Modifier.clearAndSetSemantics { } else Modifier)) {
            if (visible || progress > 0.001f) dock()
        }
    }, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val geometry = DockPolicy.reflow(width, height, density, sideFraction, bottomFraction, progress, axis)
        val editing = measurables[0].measure(Constraints.fixed(geometry.editorWidth, geometry.editorHeight))
        val assistant = measurables[1].measure(Constraints.fixed(geometry.dockWidth, geometry.dockHeight))
        layout(width, height) {
            editing.placeRelative(0, 0)
            assistant.placeRelative(geometry.dockX, geometry.dockY)
        }
    }
}
