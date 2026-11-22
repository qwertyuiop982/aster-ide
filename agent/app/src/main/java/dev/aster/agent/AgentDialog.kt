package dev.aster.agent

import androidx.compose.runtime.Composable
import dev.aster.core.AsterDialog

@Composable
internal fun AgentDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) = AsterDialog(title, onDismissRequest, confirmButton, dismissButton, content)
