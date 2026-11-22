package dev.aster.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** Regular screen surface; consumes background taps without a modal window or scrim. */
@Composable internal fun AgentConfigurationPage(
    title: String, close: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = close)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.clearFocus(force = true); keyboard?.hide() }
    val brush = LocalCodexAppearanceBrush.current
    Surface(if (brush != null) Modifier.fillMaxSize().background(brush) else Modifier.fillMaxSize(), color = if (brush != null) Color.Transparent else MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(dev.aster.core.R.string.action_back)) }
                        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        actions()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}
