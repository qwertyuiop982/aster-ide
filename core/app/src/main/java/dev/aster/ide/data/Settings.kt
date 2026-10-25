package dev.aster.ide.data

import android.content.Context
import dev.aster.ide.agent.AgentEngine
import dev.aster.ide.agent.AgentProfile
import dev.aster.ide.agent.AgentContextPolicy
import dev.aster.ide.agent.AgentThinkingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ServerPreference(val enabled: Boolean = true, val command: String = "", val initializationOptions: String = "{}")
data class IdeSettings(
    val uiStyle: UiStyle = UiStyle.VSCODE,
    val uiStyleChosen: Boolean = false,
    val theme: String = "dark",
    val accent: String = "mint",
    val animations: Boolean = true,
    val reducedMotion: Boolean = false,
    val fontSize: Int = 15,
    val tabWidth: Int = 4,
    val useTabs: Boolean = false,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val whitespace: Boolean = false,
    val autoComplete: Boolean = true,
    val autoSave: Boolean = true,
    val autoSaveDelay: Int = 1200,
    val formatOnSave: Boolean = false,
    val lspEnabled: Boolean = true,
    val diagnostics: Boolean = true,
    val lspDebounce: Int = 250,
    val requestTimeout: Int = 8,
    val maxServers: Int = 3,
    val maxTabs: Int = 12,
    val largeFileKB: Int = 512,
    val maxFileMB: Int = 8,
    val scrollback: Int = 2000,
    val port: Int = 8768,
    val npmRegistry: String = "https://registry.npmmirror.com",
    val termuxMirror: String = "ustc",
    val autoRuntime: Boolean = true,
    val editorCacheSize: Int = 3,
    val githubProxy: Boolean = true,
    val sdkPath: String = "/data/data/com.termux/files/home/android-sdk",
    val sdkBuildTools: String = "35.0.1",
    val sdkJdk: String = JdkVersions.DEFAULT,
    val sdkNdk: String = "29.0.14206865",
    val sdkCmdlineTools: Boolean = true,
    val buildMemory: Int = 1024,
    val buildWorkers: Int = 2,
    val buildCommand: String = "",
    val excludedDirs: String = ".git,.gradle,.idea,node_modules,build,target,dist,__pycache__,.aster-build",
    val agentEngine: String = "pi",
    val agentUseFree: Boolean = true,
    val agentFreeTier: String = "free",
    val agentProfiles: Map<String, AgentProfile> = emptyMap(),
    val agentEndpoint: String = "https://api.openai.com/v1",
    val agentModel: String = "",
    val agentMaxSteps: Int = 16,
    val agentMode: String = "execute",
    val agentStreaming: Boolean = true,
    val agentMaxTokens: Int = 4096,
    val agentContextWindow: Int = 32768,
    val agentDockFraction: Float = 0.36f,
    val agentDockExpanded: Boolean = false,
    val servers: Map<String, ServerPreference> = emptyMap()
) {
    fun agentProfile(engine: AgentEngine): AgentProfile = agentProfiles[engine.id] ?: if (engine == AgentEngine.PI)
        AgentProfile(agentEndpoint, agentModel, agentContextWindow, agentMaxTokens) else AgentProfile(engine.endpoint, contextWindow = AgentContextPolicy.defaultWindow(engine))
}

class SettingsStore(context: Context, preferenceName: String = "aster-settings", initial: IdeSettings? = null) {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val flow = mutable.asStateFlow()
    val value get() = mutable.value
    init {
        val version = runCatching { JSONObject(prefs.getString("json", "{}") ?: "{}").optInt("settingsVersion") }.getOrDefault(0)
        if (!prefs.contains("json") && initial != null) update { initial }
        else if (version < 6) update { it }
    }

    @Synchronized fun update(change: (IdeSettings) -> IdeSettings) {
        val next = change(value)
        mutable.value = next
        val j = JSONObject().apply {
            put("settingsVersion", 6)
            UiPreferences(next.uiStyle, next.uiStyleChosen).writeTo(this)
            put("animations", next.animations); put("reducedMotion", next.reducedMotion)
            put("autoRuntime", next.autoRuntime); put("editorCacheSize", next.editorCacheSize)
            put("theme", next.theme); put("accent", next.accent)
            put("fontSize", next.fontSize); put("tabWidth", next.tabWidth); put("useTabs", next.useTabs)
            put("wordWrap", next.wordWrap); put("lineNumbers", next.lineNumbers); put("whitespace", next.whitespace)
            put("autoComplete", next.autoComplete); put("autoSave", next.autoSave); put("autoSaveDelay", next.autoSaveDelay)
            put("formatOnSave", next.formatOnSave); put("lspEnabled", next.lspEnabled); put("diagnostics", next.diagnostics)
            put("lspDebounce", next.lspDebounce); put("requestTimeout", next.requestTimeout); put("maxServers", next.maxServers)
            put("maxTabs", next.maxTabs); put("largeFileKB", next.largeFileKB); put("maxFileMB", next.maxFileMB)
            put("scrollback", next.scrollback); put("port", next.port); put("npmRegistry", next.npmRegistry)
            put("termuxMirror", next.termuxMirror); put("githubProxy", next.githubProxy); put("sdkPath", next.sdkPath)
            put("sdkBuildTools", next.sdkBuildTools); put("sdkJdk", next.sdkJdk); put("sdkNdk", next.sdkNdk); put("sdkCmdlineTools", next.sdkCmdlineTools)
            put("buildMemory", next.buildMemory); put("buildWorkers", next.buildWorkers); put("buildCommand", next.buildCommand)
            put("excludedDirs", next.excludedDirs)
            put("agentEngine", next.agentEngine); put("agentUseFree", next.agentUseFree); put("agentFreeTier", next.agentFreeTier)
            put("agentProfiles", JSONObject().apply {
                next.agentProfiles.forEach { (id, p) -> put(id, JSONObject().put("endpoint", p.endpoint).put("model", p.model).put("contextWindow", p.contextWindow).put("maxTokens", p.maxTokens).put("thinkingLevel", p.thinkingLevel).put("thinkingMode", p.thinkingMode).put("thinkingBudget", p.thinkingBudget).put("protocol", p.protocol).put("reviewEnabled", p.reviewEnabled).put("reviewModel", p.reviewModel).put("enabledSkills", p.enabledSkills).put("mcpServers", p.mcpServers)) }
            })
            put("agentEndpoint", next.agentEndpoint); put("agentModel", next.agentModel)
            put("agentMode", next.agentMode); put("agentStreaming", next.agentStreaming)
            put("agentMaxSteps", next.agentMaxSteps); put("agentMaxTokens", next.agentMaxTokens); put("agentContextWindow", next.agentContextWindow)
            put("agentDockFraction", next.agentDockFraction); put("agentDockExpanded", next.agentDockExpanded)
            put("servers", JSONObject().apply {
                next.servers.forEach { (id, s) -> put(id, JSONObject().put("enabled", s.enabled).put("command", s.command).put("initializationOptions", s.initializationOptions)) }
            })
        }
        prefs.edit().putString("json", j.toString()).apply()
    }

    fun updateAgentProfile(engine: AgentEngine, change: (AgentProfile) -> AgentProfile) = update {
        it.copy(agentProfiles = it.agentProfiles + (engine.id to change(it.agentProfile(engine))))
    }

    fun chooseUiStyle(style: UiStyle) = update { it.copy(uiStyle = style, uiStyleChosen = true) }
    fun reset() = update { IdeSettings(uiStyle = it.uiStyle, uiStyleChosen = it.uiStyleChosen) }
    fun server(id: String, change: (ServerPreference) -> ServerPreference) = update {
        it.copy(servers = it.servers + (id to change(it.servers[id] ?: ServerPreference())))
    }

    private fun load(): IdeSettings = runCatching {
        val j = JSONObject(prefs.getString("json", "{}") ?: "{}")
        val ui = UiPreferences.read(j)
        val serverPrefs = mutableMapOf<String, ServerPreference>()
        j.optJSONObject("servers")?.let { values -> values.keys().forEach { id ->
            values.optJSONObject(id)?.let { s -> serverPrefs[id] = ServerPreference(s.optBoolean("enabled", true), s.optString("command"), s.optString("initializationOptions", "{}")) }
        } }
        val profiles = mutableMapOf<String, AgentProfile>()
        val storedProfiles = j.optJSONObject("agentProfiles")
        AgentEngine.entries.forEach { engine -> storedProfiles?.optJSONObject(engine.id)?.let { p ->
            profiles[engine.id] = AgentProfile(p.optString("endpoint", engine.endpoint), p.optString("model"),
                p.optInt("contextWindow", AgentContextPolicy.defaultWindow(engine)).coerceIn(8192, AgentContextPolicy.MAX_WINDOW), p.optInt("maxTokens", 4096).coerceIn(64, 65536), AgentThinkingPolicy.normalize(p.optString("thinkingLevel", "auto")), p.optString("thinkingMode", "legacy"), p.optInt("thinkingBudget", 1024), p.optString("protocol", "gemini").takeIf { it in setOf("gemini", "openai") } ?: "gemini", p.optBoolean("reviewEnabled", false), p.optString("reviewModel", ""), p.optString("enabledSkills", ""), p.optString("mcpServers", ""))
        } }
        IdeSettings(
            agentEngine = AgentEngine.fromId(j.optString("agentEngine", "pi")).id, agentUseFree = j.optBoolean("agentUseFree", true), agentFreeTier = j.optString("agentFreeTier", "free").takeIf { it in setOf("free", "go") } ?: "free", agentProfiles = profiles,
            uiStyle = ui.style, uiStyleChosen = ui.chosen,
            theme = j.optString("theme", "dark"), accent = j.optString("accent", "mint"),
            animations = j.optBoolean("animations", true), reducedMotion = j.optBoolean("reducedMotion"),
            fontSize = j.optInt("fontSize", 15).coerceIn(10, 28), tabWidth = j.optInt("tabWidth", 4).coerceIn(2, 8),
            useTabs = j.optBoolean("useTabs"), wordWrap = j.optBoolean("wordWrap"), lineNumbers = j.optBoolean("lineNumbers", true),
            whitespace = j.optBoolean("whitespace"), autoComplete = j.optBoolean("autoComplete", true),
            autoSave = j.optBoolean("autoSave", true), autoSaveDelay = j.optInt("autoSaveDelay", 1200).coerceIn(500, 5000),
            formatOnSave = j.optBoolean("formatOnSave"), lspEnabled = j.optBoolean("lspEnabled", true), diagnostics = j.optBoolean("diagnostics", true),
            lspDebounce = j.optInt("lspDebounce", 250).coerceIn(100, 1000), requestTimeout = j.optInt("requestTimeout", 8).coerceIn(2, 30),
            maxServers = j.optInt("maxServers", 3).coerceIn(1, 5), maxTabs = j.optInt("maxTabs", 12).coerceIn(4, 24),
            largeFileKB = j.optInt("largeFileKB", 512).coerceIn(128, 2048), maxFileMB = j.optInt("maxFileMB", 8).coerceIn(1, 12),
            scrollback = j.optInt("scrollback", 2000).coerceIn(500, 10000), port = j.optInt("port", 8768).coerceIn(1024, 65535),
            npmRegistry = j.optString("npmRegistry", "https://registry.npmmirror.com"),
            termuxMirror = if (j.optInt("settingsVersion") < 2) "ustc" else j.optString("termuxMirror", "ustc").let { if (it == "tsinghua") "ustc" else it },
            autoRuntime = j.optBoolean("autoRuntime", true), editorCacheSize = j.optInt("editorCacheSize", 3).coerceIn(1, 4),
            githubProxy = j.optBoolean("githubProxy", true), sdkPath = j.optString("sdkPath", "/data/data/com.termux/files/home/android-sdk"),
            sdkBuildTools = j.optString("sdkBuildTools", "35.0.1").takeIf { it in listOf("35.0.1", "34.0.4") } ?: "35.0.1",
            sdkJdk = JdkVersions.normalize(j.optString("sdkJdk", JdkVersions.DEFAULT)),
            sdkNdk = j.optString("sdkNdk", "29.0.14206865").let { if (it == "29.0.14033849") "29.0.14206865" else it }.takeIf { it in listOf("29.0.14206865", "28.2.13676358", "27.3.13750724", "0") } ?: "29.0.14206865",
            sdkCmdlineTools = j.optBoolean("sdkCmdlineTools", true),
            buildMemory = j.optInt("buildMemory", 1024).coerceIn(256, 4096), buildWorkers = j.optInt("buildWorkers", 2).coerceIn(1, 8),
            agentEndpoint = j.optString("agentEndpoint", IdeSettings().agentEndpoint), agentModel = j.optString("agentModel"),
            agentMode = j.optString("agentMode", "execute").takeIf { it in listOf("ask", "plan", "execute") } ?: "execute",
            agentStreaming = j.optBoolean("agentStreaming", true),
            agentMaxSteps = j.optInt("agentMaxSteps", 16).coerceIn(4, 64), agentMaxTokens = j.optInt("agentMaxTokens", 4096).coerceIn(512, 8192),
            agentContextWindow = j.optInt("agentContextWindow", 32768).coerceIn(8192, AgentContextPolicy.MAX_WINDOW),
            agentDockFraction = j.optDouble("agentDockFraction", 0.36).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0.28f, 0.5f) ?: 0.36f,
            agentDockExpanded = j.optBoolean("agentDockExpanded"),
            buildCommand = j.optString("buildCommand"), excludedDirs = j.optString("excludedDirs", IdeSettings().excludedDirs), servers = serverPrefs
        )
    }.getOrDefault(IdeSettings())
}
