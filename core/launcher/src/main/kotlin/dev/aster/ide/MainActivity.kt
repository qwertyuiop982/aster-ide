package dev.aster.ide

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Home
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.agent.AgentViewModel
import dev.aster.core.CoreRuntime
import dev.aster.core.LocalWorkModeChooser
import dev.aster.core.WorkMode
import dev.aster.ide.ui.AsterApp
import dev.aster.ide.ui.AsterTheme
import dev.aster.ide.ui.LocalDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val modeModels by viewModels<ModeViewModels>()
    private var activeIde: IdeViewModel? = null
    private var activeAgent: AgentViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val core = CoreRuntime.get(this)
        setContent {
            val settings by core.settings.flow.collectAsStateWithLifecycle()
            val mode by core.modes.flow.collectAsStateWithLifecycle()
            var choosing by rememberSaveable { mutableStateOf(false) }
            var switching by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            val select: (WorkMode?) -> Unit = { next ->
                if (!switching) scope.launch {
                    switching = true
                    try {
                        if (next != mode) {
                            activeIde?.let { vm ->
                                check(!vm.buildRunning && !vm.agentPreparing && !vm.agentStopping && !vm.agent.running && !vm.applyingEdits && !vm.connecting && !vm.busy) { "请先停止或等待 IDE 当前任务完成" }
                                vm.saveAll(); vm.agent.persistNow()
                            }
                            activeAgent?.prepareExit()
                            modeModels.release(mode)
                            activeIde = null; activeAgent = null
                            if (next == null) core.modes.clear() else core.modes.select(next)
                        }
                        choosing = false
                    } catch (e: Exception) { error = e.message ?: "切换失败" }
                    finally { switching = false }
                }
            }
            AsterTheme(settings) {
                val dark = LocalDark.current
                SideEffect { if (mode != WorkMode.AGENT) bars(mode == WorkMode.IDE && dark) }
                CompositionLocalProvider(LocalWorkModeChooser provides { choosing = true }) {
                    if (mode == null) {
                        ModeChooser(select, switching)
                    } else key(mode) {
                        val owner = remember { modeModels.owner(checkNotNull(mode)) }
                        val provider = remember { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(application)) }
                        DisposableEffect(owner) {
                            onDispose { activeIde = null; activeAgent = null }
                        }
                        if (mode == WorkMode.IDE) {
                            val vm = remember { provider[IdeViewModel::class.java] }
                            SideEffect { activeIde = vm }
                            AsterApp(vm, settings)
                        } else {
                            val vm = remember { provider[AgentViewModel::class.java] }
                            val engine by vm.agent.selectedEngine.collectAsStateWithLifecycle()
                            SideEffect { activeAgent = vm; bars(engine.id == "pi") }
                            dev.aster.agent.AgentScreen(vm)
                        }
                    }
                    if (choosing && mode != null) {
                        BackHandler { choosing = false }
                        AlertDialog(onDismissRequest = { if (!switching) choosing = false }, title = { Text("切换工作模式") }, text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("当前：${mode?.title}", style = MaterialTheme.typography.bodyMedium)
                                WorkMode.entries.filter { it != mode }.forEach { item -> OutlinedButton(onClick = { select(item) }, enabled = !switching, modifier = Modifier.fillMaxWidth()) { Text(item.title) } }
                                HorizontalDivider()
                                OutlinedButton(onClick = { select(null) }, enabled = !switching, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(8.dp)); Text("返回首页")
                                }
                                dev.aster.ide.account.AccountEntryButton()
                            }
                        }, confirmButton = { TextButton(onClick = { choosing = false }, enabled = !switching) { Text("取消") } })
                    }
                    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("暂时无法切换") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        val preferences = getSharedPreferences("account-introduction", MODE_PRIVATE)
        if (!preferences.getBoolean("shown", false)) {
            preferences.edit().putBoolean("shown", true).apply()
            startActivity(android.content.Intent(this, dev.aster.ide.account.AccountActivity::class.java))
        }
    }
    private fun bars(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val model = activeIde ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_S -> { model.save(); return true }
                KeyEvent.KEYCODE_F -> { model.showFind = true; return true }
                KeyEvent.KEYCODE_P -> { model.commandPalette = true; return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_F12) {
            model.languageAction(if (event.isShiftPressed) "references" else "definition")
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onStop() {
        activeIde?.let { if (it.settings.value.autoSave) it.persist() }
        activeAgent?.persist()
        super.onStop()
    }
}

@Composable private fun ModeChooser(onSelect: (WorkMode) -> Unit, busy: Boolean) {
    val ink = androidx.compose.ui.graphics.Color(0xFF202927)
    val teal = androidx.compose.ui.graphics.Color(0xFF087E78)
    MaterialTheme(colorScheme = lightColorScheme(primary = teal, onPrimary = androidx.compose.ui.graphics.Color.White, background = androidx.compose.ui.graphics.Color(0xFFF7F9F8), surface = androidx.compose.ui.graphics.Color(0xFFF7F9F8), onSurface = ink)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Aster", style = MaterialTheme.typography.titleLarge, color = ink)
                    Spacer(Modifier.weight(1f))
                    dev.aster.ide.account.AccountEntryButton()
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("工作空间", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedCard(onClick = { onSelect(WorkMode.IDE) }, enabled = !busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Code, null, tint = teal, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("IDE", style = MaterialTheme.typography.titleMedium)
                                    Text("编辑 · 构建 · 终端 · 辅助 Agent", style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                        }
                        OutlinedCard(onClick = { onSelect(WorkMode.AGENT) }, enabled = !busy, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color(0xFFB26628), modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Agent", style = MaterialTheme.typography.titleMedium)
                                    Text("Pi · OpenClaude · Codex · Gemini", style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                        }
                    }
                }
            }
        }
    }
}
