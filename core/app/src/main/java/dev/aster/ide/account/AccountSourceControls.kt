package dev.aster.ide.account

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aster.core.CoreRuntime
import dev.aster.ide.agent.AgentEngine
import dev.aster.ide.agent.AgentProfile

object FreeAgentProfile {
    const val MAX_TOOL_CALLS = 64
    fun value(tier: String = "free"): AgentProfile = if (tier == "go")
        AgentProfile(AccountRepository.GO_ENDPOINT, "Aster-free go", contextWindow = 128000, maxTokens = 2048, thinkingLevel = "off", thinkingMode = "off")
    else AgentProfile(AccountRepository.FREE_ENDPOINT, "Aster-free", contextWindow = 128000, maxTokens = 2048, thinkingLevel = "off", thinkingMode = "off")
}

@Composable fun AccountEntryButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(onClick = { context.startActivity(Intent().setClassName(context, "dev.aster.ide.account.AccountActivity")) }, modifier = modifier) { Text("账户 / 登录") }
}

@Composable fun AccountSourceControls(engine: AgentEngine) {
    val store = CoreRuntime.get(LocalContext.current).settings
    val settings by store.flow.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (engine == AgentEngine.PI) {
            Text("模型来源", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.agentUseFree, onClick = { store.update { it.copy(agentUseFree = true) } }, label = { Text("账户模型") })
                FilterChip(selected = !settings.agentUseFree, onClick = { store.update { it.copy(agentUseFree = false) } }, label = { Text("自定义 API") })
            }
            if (settings.agentUseFree) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.agentFreeTier == "free", onClick = { store.update { it.copy(agentFreeTier = "free") } }, label = { Text("free") })
                    FilterChip(selected = settings.agentFreeTier == "go", onClick = { store.update { it.copy(agentFreeTier = "go") } }, label = { Text("free go") })
                }
                Text(if (settings.agentFreeTier == "go") "固定推理 · 算力充足 · 1000 token = 50 积分（含推理输出）" else "无思考 · 算力正常 · 1000 token = 1 积分", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (engine == AgentEngine.PI && settings.agentUseFree) Text("每轮任务最多 64 次工具调用，工具返回后可连续执行。", style = MaterialTheme.typography.bodySmall)
        AccountEntryButton()
    }
}