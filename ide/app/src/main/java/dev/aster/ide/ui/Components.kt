package dev.aster.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ToolButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Int = 40, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(size.dp).background(if (selected) { if (LocalAcs.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) } else Color.Transparent, RoundedCornerShape(6.dp))) {
            Icon(icon, label, modifier = Modifier.size(20.dp), tint = if (!enabled) tint.copy(alpha = 0.3f) else if (selected) { if (LocalAcs.current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary } else tint)
        }
    }
}

@Composable fun StatusLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(color, RoundedCornerShape(2.dp)))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp))
}
@Composable fun Line() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp) }

@Composable fun InputDialog(title: String, label: String, initial: String = "", confirm: String = "确定", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, shape = RoundedCornerShape(8.dp),
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()); onDismiss() }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable fun ConfirmDialog(title: String, text: String, confirm: String = "确定", destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) }, shape = RoundedCornerShape(8.dp),
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

fun fileColor(path: String): Color = when (path.substringAfterLast('.').lowercase()) {
    "py" -> Amber; "kt", "kts", "rs" -> Lilac; "ts", "tsx", "js", "jsx", "cpp", "c", "h" -> Sky
    "json", "yaml", "yml" -> Mint; "html", "css", "java" -> Coral; else -> Color(0xFF929DB4)
}
