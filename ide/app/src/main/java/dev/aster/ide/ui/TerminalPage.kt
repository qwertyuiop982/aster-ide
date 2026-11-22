package dev.aster.ide.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardTab
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.data.UiStyle
import dev.aster.ide.feature.R
import dev.aster.ide.runtime.TerminalPalette

@Composable internal fun TerminalPage(vm: IdeViewModel, settings: IdeSettings, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.terminal_page_back)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            TerminalPanel(vm, settings, Modifier.fillMaxSize(), showInDock = false, viewportEndPadding = 64.dp)
            SmallFloatingActionButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backLabel)
            }
        }
    }
}

@Composable internal fun TerminalPanel(
    vm: IdeViewModel,
    settings: IdeSettings,
    modifier: Modifier,
    showInDock: Boolean = true,
    viewportEndPadding: Dp = 0.dp,
) {
    val session by vm.terminal.session.collectAsState()
    val state by vm.terminal.state.collectAsState()
    val palette = TerminalPalette.forStyle(settings.uiStyle == UiStyle.ACS, LocalDark.current)
    val status = when {
        vm.terminalOpening || state == "连接中" -> stringResource(R.string.terminal_connecting)
        state == "未连接" -> stringResource(R.string.terminal_not_connected)
        state == "已断开" -> stringResource(R.string.terminal_disconnected)
        state == "已结束" -> stringResource(R.string.terminal_ended)
        else -> state
    }
    var ctrl by remember { mutableStateOf(vm.terminal.control) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                StatusLabel(
                    status,
                    if (session != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session != null) {
                TextButton(
                    onClick = { ctrl = !ctrl; vm.terminal.control = ctrl },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(
                        "Ctrl",
                        color = if (ctrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ToolButton(Icons.Outlined.KeyboardTab, stringResource(R.string.terminal_tab_key), size = 28) {
                    vm.terminal.input("\t")
                }
                ToolButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.terminal_previous_command), size = 28) {
                    vm.terminal.input("\u001b[A")
                }
                ToolButton(Icons.Outlined.Close, stringResource(R.string.terminal_close_session), enabled = !vm.terminalOpening, size = 28) {
                    vm.terminal.close()
                }
            }
            ToolButton(
                Icons.Outlined.Add,
                stringResource(R.string.terminal_new_session),
                size = 28,
                enabled = vm.connected && !vm.terminalOpening,
            ) { vm.openTerminal(showInDock) }
        }
        val viewport = Modifier.weight(1f).fillMaxWidth()
        if (session == null) {
            Box(viewport.padding(end = viewportEndPadding), contentAlignment = Alignment.Center) {
                TextButton(
                    onClick = { if (vm.connected) vm.openTerminal(showInDock) else vm.settingsPage = "runtime" },
                    enabled = !vm.terminalOpening,
                ) {
                    Icon(if (vm.connected) Icons.Outlined.Terminal else Icons.Outlined.Link, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (vm.connected) R.string.terminal_page_open else R.string.terminal_connect_termux))
                }
            }
        } else {
            val context = LocalContext.current
            val terminalView = remember(context) {
                TerminalView(context, null).apply {
                    setTextSize((12 * resources.displayMetrics.scaledDensity).toInt())
                    setTypeface(Typeface.MONOSPACE)
                    isFocusableInTouchMode = true
                }
            }
            DisposableEffect(terminalView, session) {
                vm.terminal.attach(terminalView)
                onDispose { vm.terminal.detach(terminalView) }
            }
            AndroidView(
                factory = { terminalView },
                update = {
                    vm.terminal.setPalette(palette)
                    it.setBackgroundColor(palette.background)
                },
                modifier = viewport.background(Color(palette.background)).padding(end = viewportEndPadding),
            )
        }
    }
}
