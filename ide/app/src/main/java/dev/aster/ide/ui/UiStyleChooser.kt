package dev.aster.ide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aster.ide.data.UiStyle

@Composable fun UiStyleChooser(onChoose: (UiStyle) -> Unit) {
    var selection by rememberSaveable { mutableStateOf(UiStyle.VSCODE.key) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = maxWidth >= 620.dp
        Column(Modifier.align(Alignment.Center).widthIn(max = 780.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Icon(Icons.Outlined.Code, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("选择界面风格", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("编辑、语言服务、终端、构建和 Agent 使用同一套功能。之后可在“设置 → 外观”中切换。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                UiStyle.entries.forEach { style -> StyleCard(style, selection == style.key, Modifier.weight(1f)) { selection = style.key } }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiStyle.entries.forEach { style -> StyleCard(style, selection == style.key, Modifier.fillMaxWidth()) { selection = style.key } }
            }
            Spacer(Modifier.height(20.dp))
            Text("两种风格均采用 ACS 语言服务界面", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onChoose(UiStyle.fromKey(selection) ?: UiStyle.VSCODE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("使用${if (selection == UiStyle.ACS.key) " ACS 样式" else "原 UI"}并进入")
            }
        }
    }
}

@Composable private fun StyleCard(style: UiStyle, selected: Boolean, modifier: Modifier, choose: () -> Unit) {
    Surface(modifier.selectable(selected, onClick = choose, role = Role.RadioButton), shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(style.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                RadioButton(selected, onClick = null)
            }
            Spacer(Modifier.height(8.dp))
            StylePreview(style, Modifier.fillMaxWidth().height(100.dp))
            Spacer(Modifier.height(12.dp))
            Text(if (style == UiStyle.ACS) "抽屉文件导航 · 圆角编辑区 · 底部工具面板" else "侧边活动栏 · 文件树 · 标签与状态栏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun StylePreview(style: UiStyle, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.background(colors.background, RoundedCornerShape(10.dp)).padding(8.dp)) {
        val w = size.width
        val h = size.height
        fun block(x: Float, y: Float, width: Float, height: Float, color: Color, radius: Float = 3f) = drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(radius))
        if (style == UiStyle.VSCODE) {
            block(0f, 0f, w, h * 0.13f, colors.surfaceVariant)
            block(0f, h * 0.16f, w * 0.08f, h * 0.72f, colors.surfaceVariant)
            repeat(5) { i -> block(w * 0.02f, h * (0.20f + i * 0.12f), w * 0.035f, h * 0.06f, colors.primary.copy(alpha = if (i == 0) 1f else 0.45f)) }
            block(w * 0.1f, h * 0.16f, w * 0.24f, h * 0.72f, colors.surface)
            repeat(4) { i -> block(w * 0.13f, h * (0.22f + i * 0.12f), w * 0.15f, h * 0.025f, colors.onSurfaceVariant.copy(alpha = 0.6f)) }
            block(w * 0.36f, h * 0.17f, w * 0.23f, h * 0.12f, colors.primary.copy(alpha = 0.16f))
            repeat(5) { i -> block(w * 0.40f, h * (0.37f + i * 0.09f), w * (0.34f - i % 3 * 0.06f), h * 0.024f, if (i % 2 == 0) colors.primary else colors.onSurfaceVariant) }
            block(0f, h * 0.92f, w, h * 0.06f, colors.primary.copy(alpha = 0.25f))
        } else {
            block(0f, 0f, w, h * 0.88f, colors.surface, 12f)
            block(w * 0.04f, h * 0.05f, w * 0.09f, h * 0.07f, colors.primary)
            block(w * 0.18f, h * 0.05f, w * 0.40f, h * 0.025f, colors.onSurface)
            block(w * 0.18f, h * 0.10f, w * 0.28f, h * 0.022f, colors.onSurfaceVariant)
            repeat(3) { i -> block(w * (0.70f + i * 0.09f), h * 0.05f, w * 0.04f, h * 0.06f, colors.onSurfaceVariant) }
            block(w * 0.05f, h * 0.22f, w * 0.34f, h * 0.14f, colors.primary.copy(alpha = 0.16f), 9f)
            repeat(4) { i -> block(w * 0.10f, h * (0.45f + i * 0.09f), w * (0.58f - i % 3 * 0.08f), h * 0.026f, if (i % 2 == 0) colors.primary else colors.onSurfaceVariant) }
            block(w * 0.03f, h * 0.90f, w * 0.94f, h * 0.10f, colors.surfaceVariant, 12f)
            block(w * 0.43f, h * 0.91f, w * 0.14f, h * 0.025f, colors.onSurfaceVariant)
        }
    }
}