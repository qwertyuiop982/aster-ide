package dev.aster.agent

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.core.LocalAsterDialogMotion
import dev.aster.ide.agent.AgentContextPolicy
import dev.aster.ide.agent.AgentEngine
import dev.aster.ide.agent.AgentThinkingPolicy
import dev.aster.ide.agent.AgentApiConfiguration
import dev.aster.ide.agent.AgentModelCatalog
import dev.aster.ide.agent.parseAgentModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.aster.core.AppLanguages
import dev.aster.core.R as CoreR

@Composable internal fun ModelSettings(vm: AgentViewModel, engine: AgentEngine) {
    val settings by vm.settings.flow.collectAsStateWithLifecycle()
    var section by rememberSaveable(engine.id) { mutableStateOf<Int?>(null) }
    val navigation = SettingsSectionNavigation(section) { section = it }
    key(engine, settings.agentProfile(engine).activeApiId) { ModelSettingsContent(vm, engine, navigation) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ModelSettingsContent(vm: AgentViewModel, engine: AgentEngine, navigation: SettingsSectionNavigation) {
    val uiContext = LocalContext.current
    val accountSettings by vm.core.settings.flow.collectAsStateWithLifecycle()
    val permissionSettings by vm.settings.flow.collectAsStateWithLifecycle()
    if (engine == AgentEngine.PI && accountSettings.agentUseFree) {
        ModelSettingsPage(if (accountSettings.agentFreeTier == "go") "Aster-free go" else "Aster-free",
            { vm.showSettings = false }, { vm.showSettings = false }, stringResource(CoreR.string.action_done), sections = navigation) {
            ModelSettingsSection(R.string.agent_settings_account, R.string.agent_settings_free_detail) {
                dev.aster.ide.account.AccountSourceControls(engine)
                Text(stringResource(R.string.agent_settings_free_limits))
                Text(if (accountSettings.agentFreeTier == "go") stringResource(R.string.agent_settings_go_reasoning) else stringResource(R.string.agent_settings_free_reasoning))
            }
            ModelSettingsSection(R.string.agent_settings_tools, R.string.agent_settings_tools_detail) {
                dev.aster.ide.agent.AgentPermissionControls(permissionSettings, !vm.locked,
                    { mode -> vm.settings.update { it.copy(agentPermissionMode = mode) } },
                    { unchecked -> vm.settings.update { it.copy(agentNativeCommandsUnchecked = unchecked) } }, vm::openNativeWorkspace)
                dev.aster.ide.agent.AgentToolConfigurationControls(permissionSettings, permissionSettings.agentProfile(engine), !vm.locked,
                    { commands -> vm.settings.update { it.copy(terminalAllowedCommands = commands) } },
                    { names, definitions -> vm.settings.updateAgentProfile(engine) { it.copy(mcpServers = names, mcpDefinitions = definitions) } })
            }
            AgentSettingsLinks(vm, navigation)
        }
        return
    }
    val profile = permissionSettings.agentProfile(engine)
    val connectionId = profile.activeApiId.ifBlank { "default" }
    val apiChoices = profile.apiConfigurations.ifEmpty { listOf(AgentApiConfiguration("default", stringResource(R.string.agent_settings_default_api), profile.endpoint, profile.model, profile.models, profile.protocol)) }
    var apiName by remember(engine) { mutableStateOf(apiChoices.firstOrNull { it.id == connectionId }?.name ?: "API") }
    var confirmDeleteApi by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    val requests = rememberCoroutineScope()
    LaunchedEffect(engine, connectionId) { vm.agent.secrets.select(engine) }
    var subagentModels by remember(engine) { mutableStateOf(profile.subagentModels) }
    var enabledSkills by remember(engine) { mutableStateOf(profile.enabledSkills) }
    var reviewEnabled by remember(engine) { mutableStateOf(profile.reviewEnabled) }
    var reviewModel by remember(engine) { mutableStateOf(profile.reviewModel) }
    var protocol by remember(engine) { mutableStateOf(profile.protocol) }
    var endpoint by remember(engine) { mutableStateOf(profile.endpoint) }
    var model by remember(engine) { mutableStateOf(profile.availableModels().joinToString(",")) }
    var key by remember(engine) { mutableStateOf("") }
    var context by remember(engine) { mutableStateOf(profile.contextWindow.toString()) }
    var tokens by remember(engine) { mutableStateOf(profile.maxTokens.toString()) }
    var thinking by remember(engine) { mutableStateOf(profile.thinkingLevel) }
    var thinkingMode by remember(engine) { mutableStateOf(AgentThinkingPolicy.effectiveMode(engine, profile)) }
    var thinkingBudget by remember(engine) { mutableStateOf((if (profile.thinkingMode == "legacy") AgentThinkingPolicy.budget(engine, profile, profile.thinkingLevel) ?: profile.thinkingBudget else profile.thinkingBudget).toString()) }
    var streaming by remember { mutableStateOf(vm.settings.value.agentStreaming) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val hasKey by vm.agent.secrets.hasKey.collectAsStateWithLifecycle()
    val outputConfigurable = AgentContextPolicy.outputConfigurable(engine)
    val minimum = AgentContextPolicy.minimum(engine)
    val persist: () -> Boolean = {
        runCatching {
            check(!vm.locked) { uiContext.getString(R.string.agent_settings_api_busy) }
            val uri = java.net.URI(endpoint.trim())
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null && uri.rawQuery == null) { uiContext.getString(R.string.agent_settings_invalid_url) }
            val window = context.trim().toIntOrNull() ?: error(uiContext.getString(R.string.agent_settings_invalid_window))
            val output = if (outputConfigurable) tokens.trim().toIntOrNull() ?: error(uiContext.getString(R.string.agent_settings_invalid_output)) else profile.maxTokens
            val modelIds = parseAgentModels(model)
            AgentContextPolicy.validate(engine, window, output)
            val nextProfile = profile.copy(subagentModels = subagentModels.trim(), mcpServers = vm.settings.value.agentProfile(engine).mcpServers, mcpDefinitions = vm.settings.value.agentProfile(engine).mcpDefinitions, enabledSkills = enabledSkills.trim(), reviewEnabled = reviewEnabled, reviewModel = reviewModel.trim(), protocol = protocol, endpoint = endpoint.trim(), model = modelIds.firstOrNull().orEmpty(), models = modelIds.joinToString(","), activeApiId = connectionId, apiConfigurations = apiChoices.map { if (it.id == connectionId) it.copy(name = apiName.trim().ifBlank { "API" }) else it }, contextWindow = window, maxTokens = output, thinkingLevel = thinking.trim(), thinkingMode = if (engine in setOf(AgentEngine.GEMINI, AgentEngine.OPENCLAUDE)) thinkingMode else if (thinking in setOf("auto", "off")) thinking else "strength", thinkingBudget = if (thinkingMode == "budget") thinkingBudget.toIntOrNull() ?: error(uiContext.getString(R.string.agent_settings_invalid_budget)) else thinkingBudget.toIntOrNull() ?: profile.thinkingBudget)
            AgentThinkingPolicy.validate(engine, nextProfile)
            if (key.isNotBlank()) vm.agent.secrets.save(key.trim(), engine, connectionId)
            vm.settings.updateAgentProfile(engine) { nextProfile }
            vm.settings.update { it.copy(agentStreaming = streaming) }
            vm.agent.secrets.select(engine)
            key = ""; validationError = null
        }.fold({ true }, { validationError = it.message ?: uiContext.getString(R.string.agent_settings_invalid); false })
    }
    val save: () -> Unit = { if (persist()) vm.showSettings = false }
    if (confirmDeleteApi) AgentDialog(
        onDismissRequest = { confirmDeleteApi = false },
        title = stringResource(R.string.agent_settings_remove_api_prompt),
        confirmButton = { TextButton(onClick = {
            vm.settings.removeAgentApi(engine, connectionId)
            vm.agent.secrets.save("", engine, connectionId)
            vm.agent.secrets.select(engine)
            confirmDeleteApi = false
        }) { Text(stringResource(CoreR.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDeleteApi = false }) { Text(stringResource(CoreR.string.action_cancel)) } }
    ) { Text(stringResource(R.string.agent_settings_remove_api_detail, apiName)) }
    ModelSettingsPage("${engine.label} · ${stringResource(CoreR.string.action_settings)}", { key = ""; vm.showSettings = false }, save,
        message = validationError, sections = navigation) {
        ModelSettingsSection(R.string.agent_settings_api, R.string.agent_settings_provider_detail) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apiChoices.forEach { api ->
                    FilterChip(selected = api.id == connectionId, enabled = !vm.locked && !loadingModels, onClick = {
                        if (api.id != connectionId && persist()) {
                            vm.settings.selectAgentApi(engine, api.id)
                            vm.agent.secrets.select(engine)
                        }
                    }, label = { Text(if (api.id == connectionId) stringResource(R.string.agent_settings_api_active, api.name) else api.name) })
                }
            }
            OutlinedTextField(apiName, { apiName = it }, label = { Text(stringResource(R.string.agent_settings_api_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !vm.locked && !loadingModels, onClick = { if (persist()) { vm.settings.addAgentApi(engine); vm.agent.secrets.select(engine) } }) { Text(stringResource(R.string.agent_settings_add_api)) }
                TextButton(enabled = !vm.locked && !loadingModels && apiChoices.size > 1, onClick = { confirmDeleteApi = true }) { Text(stringResource(R.string.agent_settings_remove_api)) }
            }
            Text(stringResource(R.string.agent_settings_key_storage), style = MaterialTheme.typography.bodySmall)
        }
        ModelSettingsSection(R.string.agent_settings_connect_model, R.string.agent_settings_connect_model_detail) {
            Text("${engine.displayName} · ${engine.apiLabel}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (engine == AgentEngine.GEMINI) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gemini" to "Gemini API", "openai" to "OpenAI API").forEach { (id, label) ->
                        FilterChip(selected = protocol == id, onClick = { protocol = id; if (id == "openai" && thinkingMode == "budget") thinkingMode = "auto"; validationError = null }, label = { Text(label) })
                    }
                }
                Text(if (protocol == "openai") stringResource(R.string.agent_settings_chat_completions) else stringResource(R.string.agent_settings_generate_content), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.agent_settings_api_url)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.agent_settings_model_list)) }, supportingText = { Text(stringResource(R.string.agent_settings_model_list_hint)) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !loadingModels && !vm.locked && endpoint.isNotBlank(), onClick = {
                val requestedEndpoint = endpoint.trim()
                val requestedProtocol = protocol
                val enteredKey = key.trim()
                loadingModels = true; validationError = null
                requests.launch {
                    var usedKey = enteredKey
                    try {
                        val found = withContext(Dispatchers.IO) {
                            if (usedKey.isBlank()) usedKey = vm.agent.secrets.read(engine, connectionId)
                            AgentModelCatalog.fetch(engine, requestedEndpoint, requestedProtocol, usedKey)
                        }
                        if (endpoint.trim() == requestedEndpoint && protocol == requestedProtocol)
                            model = (parseAgentModels(model) + found).distinct().joinToString(",")
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { validationError = AgentDiagnostics.redact(error.message ?: uiContext.getString(R.string.agent_settings_fetch_failed), listOf(usedKey)) }
                    finally { usedKey = ""; loadingModels = false }
                }
            }) { Text(if (loadingModels) stringResource(R.string.agent_settings_fetching_models) else stringResource(R.string.agent_settings_fetch_models)) }
            OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) stringResource(R.string.agent_settings_keep_key) else "API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (hasKey) TextButton(onClick = { vm.agent.secrets.save("", engine) }) { Text(stringResource(R.string.agent_settings_clear_key)) }
        }
        ModelSettingsSection(R.string.agent_settings_context_output, R.string.agent_settings_context_output_detail) {
            OutlinedTextField(context, { context = it; validationError = null }, label = { Text(stringResource(R.string.agent_settings_context_window)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text(stringResource(R.string.agent_settings_window_range, minimum, AgentContextPolicy.MAX_WINDOW)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(32768 to "32K", 65536 to "64K", 131072 to "128K", 262144 to "256K", 1048576 to "1M").filter { it.first >= minimum }.forEach { (value, label) ->
                    FilterChip(selected = context == value.toString(), onClick = { context = value.toString(); validationError = null }, label = { Text(label) })
                }
            }
            Text(AgentContextPolicy.description(engine), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.agent_settings_window_detail), style = MaterialTheme.typography.bodySmall)
            if (outputConfigurable) {
                OutlinedTextField(tokens, { tokens = it; validationError = null }, label = { Text(stringResource(R.string.agent_settings_output_limit)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (engine == AgentEngine.OPENCLAUDE) Text(stringResource(R.string.agent_settings_openclaude_output), style = MaterialTheme.typography.bodySmall)
            } else Text(stringResource(R.string.agent_settings_codex_output), style = MaterialTheme.typography.bodySmall)
        }
        ModelSettingsSection(R.string.agent_settings_reasoning, R.string.agent_settings_reasoning_detail) {
            val dualThinking = engine in setOf(AgentEngine.GEMINI, AgentEngine.OPENCLAUDE)
            if (dualThinking) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("auto" to stringResource(R.string.agent_settings_reasoning_auto), "budget" to stringResource(R.string.agent_settings_reasoning_budget), "strength" to stringResource(R.string.agent_settings_reasoning_level), "off" to stringResource(R.string.agent_settings_reasoning_off)) + if (engine == AgentEngine.GEMINI) listOf("manual" to stringResource(R.string.agent_settings_reasoning_manual)) else emptyList()).filter { (id, _) -> val candidate = profile.copy(protocol = protocol, model = parseAgentModels(model).firstOrNull().orEmpty(), maxTokens = tokens.toIntOrNull() ?: profile.maxTokens); when (id) { "budget" -> AgentThinkingPolicy.supportsBudget(engine, candidate); "strength" -> AgentThinkingPolicy.supportsStrength(engine, candidate); "off" -> "off" in AgentThinkingPolicy.available(engine, candidate); else -> true } }.forEach { (id, label) ->
                        FilterChip(selected = thinkingMode == id, onClick = { thinkingMode = id; validationError = null }, label = { Text(label) })
                    }
                }
                if (thinkingMode == "budget") OutlinedTextField(thinkingBudget, { thinkingBudget = it; validationError = null }, label = { Text(stringResource(R.string.agent_settings_reasoning_tokens)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (engine == AgentEngine.GEMINI && thinkingMode == "manual") {
                OutlinedTextField(thinking, { thinking = it; validationError = null }, label = { Text(stringResource(R.string.agent_settings_reasoning_manual_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(if (protocol == "openai") stringResource(R.string.agent_settings_reasoning_openai) else stringResource(R.string.agent_settings_reasoning_gemini), style = MaterialTheme.typography.bodySmall)
            }
            val currentProfile = profile.copy(
                protocol = protocol, model = parseAgentModels(model).firstOrNull().orEmpty(),
                maxTokens = tokens.toIntOrNull() ?: profile.maxTokens,
                thinkingLevel = thinking.trim(), thinkingMode = thinkingMode,
                thinkingBudget = thinkingBudget.toIntOrNull() ?: profile.thinkingBudget
            )
            val levels = AgentThinkingPolicy.available(engine, currentProfile).filter { !dualThinking || thinkingMode == "strength" && it !in setOf("auto", "off") }
            if (levels.isNotEmpty()) {
                val selected = levels.indexOf(thinking).coerceAtLeast(0)
                Text(AgentThinkingPolicy.label(engine, levels[selected]), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Slider(value = selected.toFloat(), onValueChange = { thinking = levels[it.roundToInt().coerceIn(0, levels.lastIndex)]; validationError = null }, valueRange = 0f..levels.lastIndex.coerceAtLeast(1).toFloat(), steps = (levels.size - 2).coerceAtLeast(0), enabled = levels.size > 1, colors = if (engine == AgentEngine.CODEX && !permissionSettings.agentCodexNebulaAll) SliderDefaults.colors(thumbColor = if (thinking == "ultra") Color(0xFF9259E8) else Color(0xFF3976E8), activeTrackColor = if (thinking == "ultra") Color(0xFF9259E8) else Color(0xFF3976E8)) else if (engine == AgentEngine.CODEX && permissionSettings.agentCodexNebulaAll) SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.outline, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant) else SliderDefaults.colors())
                if (engine == AgentEngine.CODEX) levels.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { level ->
                            TextButton(onClick = { thinking = level; validationError = null }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                                Text(AgentThinkingPolicy.label(engine, level), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(AgentThinkingPolicy.label(engine, levels.first())); Text(AgentThinkingPolicy.label(engine, levels.last())) }
            }
            Text(stringResource(R.string.agent_settings_reasoning_constraint), style = MaterialTheme.typography.bodySmall)
            Text(AgentThinkingPolicy.note(engine, currentProfile), style = MaterialTheme.typography.bodySmall)
        }
        ModelSettingsSection(R.string.agent_settings_collaboration, R.string.agent_settings_collaboration_detail) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.agent_settings_auto_review), Modifier.weight(1f))
                Switch(reviewEnabled, { reviewEnabled = it })
            }
            if (reviewEnabled) {
                OutlinedTextField(reviewModel, { reviewModel = it }, label = { Text(stringResource(R.string.agent_settings_review_model)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(stringResource(R.string.agent_settings_review_detail), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(subagentModels, { subagentModels = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_settings_child_models)) }, supportingText = { Text(stringResource(R.string.agent_settings_child_models_detail)) })
            OutlinedTextField(enabledSkills, { enabledSkills = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_settings_skills)) }, supportingText = { Text(stringResource(R.string.agent_settings_skills_detail)) })
        }
        ModelSettingsSection(R.string.agent_settings_account, R.string.agent_settings_account_detail) {
            dev.aster.ide.account.AccountSourceControls(engine)
        }
        ModelSettingsSection(R.string.agent_settings_tools, R.string.agent_settings_tools_detail) {
            dev.aster.ide.agent.AgentPermissionControls(permissionSettings, !vm.locked,
                { mode -> vm.settings.update { it.copy(agentPermissionMode = mode) } },
                { unchecked -> vm.settings.update { it.copy(agentNativeCommandsUnchecked = unchecked) } }, vm::openNativeWorkspace)
            dev.aster.ide.agent.AgentToolConfigurationControls(permissionSettings, permissionSettings.agentProfile(engine), !vm.locked,
                { commands -> vm.settings.update { it.copy(terminalAllowedCommands = commands) } },
                { names, definitions -> vm.settings.updateAgentProfile(engine) { it.copy(mcpServers = names, mcpDefinitions = definitions) } })
        }
        ModelSettingsSection(R.string.agent_settings_task, R.string.agent_settings_task_detail) {
            Text(stringResource(R.string.agent_settings_task_limits), style = MaterialTheme.typography.bodyMedium)
            if (engine == AgentEngine.PI) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.agent_settings_streaming)); Switch(streaming, { streaming = it }) }
            else Text(stringResource(R.string.agent_settings_native_events), style = MaterialTheme.typography.bodySmall)
            if (model.isBlank()) Text(stringResource(R.string.agent_settings_model_required), style = MaterialTheme.typography.bodySmall)
        }
        AgentSettingsLinks(vm, navigation)
    }
}

internal data class SettingsSectionNavigation(val selected: Int?, val open: (Int?) -> Unit)
private val LocalSettingsSectionNavigation = staticCompositionLocalOf { SettingsSectionNavigation(null, {}) }

@Composable internal fun ModelSettingsPage(
    title: String, close: () -> Unit, save: () -> Unit, action: String = stringResource(CoreR.string.action_save), message: String? = null,
    sections: SettingsSectionNavigation? = null, content: @Composable ColumnScope.() -> Unit,
) {
    var local by rememberSaveable(title) { mutableStateOf<Int?>(null) }
    val navigation = sections ?: SettingsSectionNavigation(local) { local = it }
    val selected = navigation.selected
    val back = { if (selected != null) navigation.open(null) else close() }
    val sectionState = rememberSaveableStateHolder()
    val motion = if (LocalAsterDialogMotion.current) 160 else 0
    AgentConfigurationPage(selected?.let { stringResource(it) } ?: title, back, actions = { TextButton(onClick = save) { Text(action) } }) {
        message?.let { Text(it, Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Crossfade(selected, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(motion), label = "settings-section") { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CompositionLocalProvider(LocalSettingsSectionNavigation provides navigation.copy(selected = page)) {
                    sectionState.SaveableStateProvider(page ?: "settings-index") {
                        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = if (page == null) 12.dp else 20.dp),
                            content = content)
                    }
                }
            }
        }
    }
}

@Composable internal fun ModelSettingsSection(@androidx.annotation.StringRes title: Int, @androidx.annotation.StringRes description: Int, content: @Composable ColumnScope.() -> Unit) {
    val navigation = LocalSettingsSectionNavigation.current
    if (navigation.selected == null) {
        val symbol = when (title) {
            R.string.agent_settings_api -> Icons.Outlined.Lan
            R.string.agent_settings_connect_model -> Icons.Outlined.Cloud
            R.string.agent_settings_context_output -> Icons.Outlined.Storage
            R.string.agent_settings_reasoning -> Icons.Outlined.Psychology
            R.string.agent_settings_collaboration -> Icons.Outlined.Groups
            R.string.agent_settings_tools -> Icons.Outlined.Security
            R.string.agent_settings_account -> Icons.Outlined.AccountCircle
            R.string.agent_settings_task -> Icons.Outlined.Timer
            else -> Icons.Outlined.Palette
        }
        SettingsLink(stringResource(title), stringResource(description), symbol) { navigation.open(title) }
    } else if (navigation.selected == title) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(description), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable private fun AgentSettingsLinks(vm: AgentViewModel, navigation: SettingsSectionNavigation) {
    if (navigation.selected != null) return
    SettingsLink(stringResource(R.string.agent_settings_build), stringResource(R.string.agent_settings_build_detail), Icons.Outlined.Terminal, vm::openEnvironmentSettings)
    SettingsLink(stringResource(R.string.agent_settings_appearance), stringResource(R.string.agent_settings_appearance_detail), Icons.Outlined.Palette) { vm.showAppearance = true }
    AppLanguages.Picker(Modifier.padding(vertical = 12.dp))
}

@Composable private fun SettingsLink(
    title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable(onClick = open).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
