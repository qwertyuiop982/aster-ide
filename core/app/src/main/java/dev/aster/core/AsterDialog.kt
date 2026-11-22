package dev.aster.core

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalAsterDialogMotion = staticCompositionLocalOf { true }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsterDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var presented by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { presented = true }
    val motion = LocalAsterDialogMotion.current
    val opacity by animateFloatAsState(if (presented || !motion) 1f else 0f, tween(if (motion) 160 else 0), label = "dialog")
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 620.dp).alpha(opacity),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 12.dp,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}
