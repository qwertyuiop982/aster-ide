package dev.aster.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.agent.AgentAppearance
import dev.aster.ide.agent.AgentEngine
import kotlin.math.roundToInt

internal val LocalCodexAppearanceBrush = staticCompositionLocalOf<Brush?> { null }

internal fun codexAppearanceColor(value: String, fallback: String = AgentAppearance.DEFAULT_LEFT): Color =
    Color(android.graphics.Color.parseColor(AgentAppearance.color(value, fallback)))

internal fun codexAppearanceDark(left: String, right: String): Boolean {
    val a = codexAppearanceColor(left)
    val b = codexAppearanceColor(right, AgentAppearance.DEFAULT_RIGHT)
    val samples = (0..8).map { index ->
        val t = index / 8f
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t).luminance()
    }
    return samples.minOf { 1.05f / (it + .05f) } >= samples.minOf { (it + .05f) / .05f }
}

internal fun codexAppearanceShade(color: Color, amount: Float): Color =
    Color(color.red * (1f - amount), color.green * (1f - amount), color.blue * (1f - amount))

internal fun codexAppearanceLine(left: Color, right: Color): Color {
    val minimum = (0..16).minOf { index ->
        val t = index / 16f
        Color(left.red + (right.red - left.red) * t, left.green + (right.green - left.green) * t, left.blue + (right.blue - left.blue) * t).luminance()
    } * .48f
    var line = codexAppearanceShade(lerp(left, right, .5f), .42f)
    repeat(256) { if (line.luminance() > minimum) line = codexAppearanceShade(line, .08f) }
    return line
}

internal fun codexAppearanceColors(left: String, right: String): ColorScheme {
    val middle = lerp(codexAppearanceColor(left), codexAppearanceColor(right, AgentAppearance.DEFAULT_RIGHT), .5f)
    val dark = codexAppearanceDark(left, right)
    val base = if (dark) Color.Black else Color.White
    val ink = if (dark) Color.White else Color(0xFF0C0C10)
    val accent = lerp(ink, middle, .06f)
    val onAccent = lerp(middle, base, .24f)
    val container = lerp(middle, base, .12f)
    val line = codexAppearanceLine(codexAppearanceColor(left), codexAppearanceColor(right, AgentAppearance.DEFAULT_RIGHT))
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(primary = accent, onPrimary = onAccent, secondary = accent, tertiary = accent,
        onSecondary = onAccent, onTertiary = onAccent,
        primaryContainer = container, onPrimaryContainer = ink, secondaryContainer = container, onSecondaryContainer = ink,
        tertiaryContainer = container, onTertiaryContainer = ink,
        background = middle, onBackground = ink,
        surface = middle, onSurface = ink, surfaceTint = Color.Transparent,
        surfaceVariant = container, onSurfaceVariant = lerp(ink, middle, .1f),
        surfaceContainerLowest = middle, surfaceContainerLow = lerp(middle, base, .08f), surfaceContainer = container,
        surfaceContainerHigh = lerp(middle, base, .16f), surfaceContainerHighest = lerp(middle, base, .2f),
        surfaceBright = lerp(middle, ink, .12f), surfaceDim = lerp(middle, base, .12f),
        inverseSurface = ink, inverseOnSurface = middle, inversePrimary = middle,
        outline = line, outlineVariant = line)
}

@Composable internal fun AgentAppearanceSettings(vm: AgentViewModel) {
    val saved by vm.settings.flow.collectAsStateWithLifecycle()
    val engine by vm.agent.selectedEngine.collectAsStateWithLifecycle()
    val codex = engine == AgentEngine.CODEX
    val motionSettings by vm.core.settings.flow.collectAsStateWithLifecycle()
    var left by remember { mutableStateOf(saved.agentCodexNebulaLeft) }
    var right by remember { mutableStateOf(saved.agentCodexNebulaRight) }
    var all by remember { mutableStateOf(saved.agentCodexNebulaAll) }
    var selected by remember { mutableStateOf(0) }
    var layout by remember { mutableStateOf(saved.agentUiLayout) }
    var error by remember { mutableStateOf<String?>(null) }
    val leftColor = codexAppearanceColor(left)
    val rightColor = codexAppearanceColor(right, AgentAppearance.DEFAULT_RIGHT)
    val motion = motionSettings.animations && !motionSettings.reducedMotion
    ModelSettingsPage("界面配置", { vm.showAppearance = false }, {
        runCatching { vm.saveCodexAppearance(left, right, all, layout) }.onFailure { error = it.message }
    }, message = error) {
        ModelSettingsSection("界面样式", "布局切换保留所有功能。") {
            listOf("chat" to "对话式 · 默认", "classic" to "原样式").forEach { (id, title) ->
                Surface(onClick = { layout = id }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleLarge)
                                Text(if (id == "chat") "简洁对话，选项收纳于侧边栏" else "工作信息与快捷操作", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = layout == id, onClick = null)
                        }
                        AgentLayoutPreview(id)
                    }
                }
            }
            Text("对话式：顶部模型与思考气泡，底部简洁输入；选项和会话记录统一放在侧边栏。", style = MaterialTheme.typography.bodySmall)
        }
        if (codex) {
        ModelSettingsSection("Codex · 双色星云", "从左到右，缓慢流动。") {
            NebulaPreview(leftColor, rightColor, motion)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = selected == 0, onClick = { selected = 0 }, label = { Text("左侧颜色") },
                    leadingIcon = { Box(Modifier.size(16.dp).clip(RoundedCornerShape(6.dp)).background(leftColor)) }, modifier = Modifier.weight(1f))
                FilterChip(selected = selected == 1, onClick = { selected = 1 }, label = { Text("右侧颜色") },
                    leadingIcon = { Box(Modifier.size(16.dp).clip(RoundedCornerShape(6.dp)).background(rightColor)) }, modifier = Modifier.weight(1f))
            }
            val text = if (selected == 0) left else right
            val color = if (selected == 0) leftColor else rightColor
            val update: (String) -> Unit = { value -> if (selected == 0) left = value else right = value; error = null }
            OutlinedTextField(text, { update(it.take(7)) }, label = { Text(if (selected == 0) "左侧 · #RRGGBB" else "右侧 · #RRGGBB") },
                isError = !AgentAppearance.isColor(text), singleLine = true, modifier = Modifier.fillMaxWidth())
            listOf("R" to color.red, "G" to color.green, "B" to color.blue).forEachIndexed { index, (label, value) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.width(16.dp), style = MaterialTheme.typography.labelSmall)
                    Slider(value = value, onValueChange = { next ->
                        val channels = mutableListOf((color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt())
                        channels[index] = (next * 255).roundToInt()
                        update("#" + channels.joinToString("") { it.toString(16).padStart(2, '0') }.uppercase())
                    }, modifier = Modifier.weight(1f), colors = if (saved.agentCodexNebulaAll) SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.outline, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant, thumbColor = MaterialTheme.colorScheme.onSurface) else SliderDefaults.colors())
                    Text((value * 255).roundToInt().toString(), Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { val previous = left; left = right; right = previous; error = null }) { Text("交换左右") }
                TextButton(onClick = { left = AgentAppearance.DEFAULT_LEFT; right = AgentAppearance.DEFAULT_RIGHT; error = null }) { Text("恢复默认") }
            }
        }
        ModelSettingsSection("应用范围", "保存后立即生效。") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("应用到整个 Codex", style = MaterialTheme.typography.titleSmall)
                    Text(if (all) "整屏连续渐变，面板继承所在位置的底色" else "仅 Ultra 气泡使用双色星云", style = MaterialTheme.typography.bodySmall)
                }
                Switch(all, { all = it })
            }
        }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun NebulaPreview(left: Color, right: Color, motion: Boolean) {
    val ink = if (lerp(left, right, .5f).luminance() < .42f) Color.White else Color(0xFF181322)
    val line = codexAppearanceLine(left, right)
    val rail = Brush.horizontalGradient(listOf(codexAppearanceShade(left, .58f), codexAppearanceShade(right, .58f)))
    val transition = rememberInfiniteTransition(label = "nebula-preview")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart), label = "left-to-right")
    val progress = if (motion) phase else 0f
    Box(Modifier.fillMaxWidth().height(154.dp).clip(RoundedCornerShape(24.dp))
        .background(Brush.horizontalGradient(listOf(left, right)))
        .border(1.dp, line, RoundedCornerShape(24.dp))) {
        Canvas(Modifier.matchParentSize()) {
            for (i in 0 until 24) {
                val x = ((i * .618034f + progress) % 1f) * size.width
                val y = ((i * .381966f + .13f) % 1f) * size.height
                drawCircle(ink.copy(alpha = if (i % 5 == 0) .32f else .12f), if (i % 5 == 0) 1.2f else .65f, Offset(x, y))
            }
            drawLine(line, Offset(18.dp.toPx(), 1f), Offset(size.width - 18.dp.toPx(), 1f), 1f)
        }
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("Codex", color = ink.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
            Text("Ultra", color = ink, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)).background(rail))
        }
    }
}

@Composable private fun AgentLayoutPreview(layout: String) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val blocks = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val w = size.width * 0.4f
        val h = size.height * 0.92f
        val x = (size.width - w) / 2
        val y = (size.height - h) / 2
        drawRoundRect(outline, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        val pad = w * 0.08f
        drawRoundRect(accent, Offset(x + pad, y + pad), androidx.compose.ui.geometry.Size(w * 0.55f, h * 0.035f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        val sidebar = if (layout == "classic") w * 0.23f else 0f
        if (sidebar > 0) drawRoundRect(blocks, Offset(x + pad, y + h * 0.18f), androidx.compose.ui.geometry.Size(sidebar, h * 0.59f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        repeat(4) { row ->
            drawRoundRect(blocks, Offset(x + pad + sidebar, y + h * (0.23f + row * 0.12f)), androidx.compose.ui.geometry.Size(w - pad * 2 - sidebar, h * 0.075f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
        drawRoundRect(blocks, Offset(x + pad, y + h * 0.83f), androidx.compose.ui.geometry.Size(w - pad * 2, h * 0.1f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    }
}
