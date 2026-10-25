package dev.aster.core

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import dev.aster.ide.data.SettingsStore
import dev.aster.ide.data.WorkspaceRepository
import dev.aster.ide.runtime.BridgeClient
import dev.aster.ide.runtime.LogBuffer
import dev.aster.ide.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WorkMode(val id: String, val title: String) {
    IDE("ide", "IDE 模式"), AGENT("agent", "Agent 模式");
    companion object { fun fromId(value: String?) = entries.firstOrNull { it.id == value } }
}

class WorkModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("work-mode", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(WorkMode.fromId(preferences.getString("selected", null)))
    val flow = selected.asStateFlow()
    fun clear() {
        preferences.edit().remove("selected").apply()
        selected.value = null
    }
    fun select(mode: WorkMode) {
        preferences.edit().putString("selected", mode.id).apply()
        selected.value = mode
    }
}

/** The launcher provides this native mode chooser to either presentation module. */
val LocalWorkModeChooser = staticCompositionLocalOf<() -> Unit> { {} }

/** One runtime, bridge and workspace repository shared by both modes. */
class CoreRuntime private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val modes = WorkModeStore(context)
    val repo = WorkspaceRepository(context, settings)
    val bridge = BridgeClient(context, settings)
    val output = LogBuffer(scope)
    val logs = LogBuffer(scope, 60000)
    val termux = TermuxRuntime(context, settings, bridge) { output.append(it); logs.append(it) }
    companion object {
        @Volatile private var instance: CoreRuntime? = null
        fun get(context: Context): CoreRuntime = instance ?: synchronized(this) {
            instance ?: CoreRuntime(context.applicationContext).also { instance = it }
        }
    }
}
