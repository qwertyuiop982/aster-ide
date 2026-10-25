package dev.aster.ide.account

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.core.CoreRuntime
import dev.aster.ide.ui.AsterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AccountActivity : ComponentActivity() {
    private var callback by mutableStateOf<Uri?>(null)
    private var resumed by mutableIntStateOf(0)
    private val network = Mutex()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        callback = intent.data
        val core = CoreRuntime.get(this)
        val repository = AccountRepository.get(this)
        setContent {
            val settings by core.settings.flow.collectAsStateWithLifecycle()
            var account by remember { mutableStateOf(JSONObject()) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var email by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            var challenge by remember { mutableStateOf("") }
            var ledger by remember { mutableStateOf<JSONArray?>(null) }
            val scope = rememberCoroutineScope()
            fun run(action: () -> JSONObject, accept: (JSONObject) -> Unit = { account = it }) {
                scope.launch {
                    network.withLock {
                        busy = true
                        try { accept(withContext(Dispatchers.IO) { action() }) }
                        catch (e: Exception) { error = e.message ?: "操作未完成，请重试" }
                        finally { busy = false }
                    }
                }
            }
            LaunchedEffect(resumed) { run({ repository.state() }) }
            LaunchedEffect(callback) {
                val uri = callback ?: return@LaunchedEffect
                callback = null; intent.data = null
                run({ repository.exchange(uri); repository.state() })
            }
            AsterTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                            Text("Aster 账户", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                        }
                        HorizontalDivider()
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Aster", style = MaterialTheme.typography.headlineLarge)
                            val capabilities = account.optJSONObject("capabilities") ?: JSONObject()
                            val loggedIn = account.optBoolean("logged_in")
                            if (!loggedIn) {
                                Text("登录后使用 Aster-free", style = MaterialTheme.typography.titleLarge)
                                Text("注册赠送 1000 积分，每日签到增加 100 积分。项目编辑、自定义 API 与原有 Agent 功能都可继续使用。")
                                Button(onClick = { run({ JSONObject().put("url", repository.githubStart()) }, { reply -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reply.getString("url")))) }) }, enabled = !busy && capabilities.optBoolean("github_login"), modifier = Modifier.fillMaxWidth()) { Text("使用 GitHub 登录") }
                                if (capabilities.optBoolean("email_login")) {
                                    OutlinedTextField(email, { email = it }, label = { Text("邮箱地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                                    OutlinedButton(onClick = { run({ repository.emailStart(email) }, { challenge = it.getString("challenge_id"); error = "验证码已发送，5 分钟内有效" }) }, enabled = !busy && email.isNotBlank()) { Text("发送验证码") }
                                    OutlinedTextField(code, { code = it }, label = { Text("6 位验证码") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                    Button(onClick = { run({ repository.emailVerify(challenge, code); repository.state() }) }, enabled = !busy && challenge.isNotBlank() && code.length == 6) { Text("邮箱登录") }
                                } else Text("邮箱登录尚未启用，可先使用 GitHub 登录。", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { finish() }) { Text("返回工作空间") }
                            } else {
                                val wallet = account.optJSONObject("wallet") ?: JSONObject()
                                Text(account.optJSONObject("identity")?.optString("display_name") ?: "已登录", style = MaterialTheme.typography.titleLarge)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("可用积分", style = MaterialTheme.typography.labelLarge)
                                        Text(wallet.optString("available", "—"), style = MaterialTheme.typography.displaySmall)
                                        Text("总积分 ${wallet.optString("balance", "—")} · 预占 ${wallet.optString("reserved", "0.000")}", style = MaterialTheme.typography.bodySmall)
                                        Text("北京时间 ${wallet.optString("beijing_date")}，每日 00:00 更新", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Button(onClick = { run({ repository.checkin(); repository.state() }) }, enabled = !busy && !wallet.optBoolean("checked_in"), modifier = Modifier.fillMaxWidth()) { Text(if (wallet.optBoolean("checked_in")) "今日已签到" else "签到 +100 积分") }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { run({ repository.state() }) }, enabled = !busy) { Text("刷新账户") }
                                    OutlinedButton(onClick = { run({ repository.ledger() }, { ledger = it.optJSONArray("items") }) }, enabled = !busy) { Text("积分明细") }
                                }
                                TextButton(onClick = { run({ repository.logout(); repository.state() }) }, enabled = !busy) { Text("退出登录") }
                            }
                            HorizontalDivider()
                            Text("Pi Agent 模型来源", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("账户模型")
                                    Text("free 无思考 · Go 固定推理，仅显示最终内容\n128000 token 上下文上限 · 每轮任务最多 64 次工具调用", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(settings.agentUseFree, { enabled -> core.settings.update { it.copy(agentUseFree = enabled) } })
                            }
                            if (settings.agentUseFree) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = settings.agentFreeTier == "free", onClick = { core.settings.update { it.copy(agentFreeTier = "free") } }, label = { Text("free") })
                                    FilterChip(selected = settings.agentFreeTier == "go", onClick = { core.settings.update { it.copy(agentFreeTier = "go") } }, label = { Text("free go") })
                                }
                            }
                            Text(if (settings.agentUseFree) "Pi 使用 ${if (settings.agentFreeTier == "go") "Aster-free go" else "Aster-free"}；真实模型与上游 Key 仅保存在服务端。" else "Pi 使用原来的自定义 API 配置。", style = MaterialTheme.typography.bodySmall)
                            Text("Aster-free · 算力正常 · 1000 token = 1 积分 · ${if (capabilities.optBoolean("free")) "可用" else "暂不可用"}", style = MaterialTheme.typography.bodySmall)
                            Text("Aster-free go · 算力充足 · 1000 token = 50 积分（含推理输出） · ${if (capabilities.optBoolean("free_go")) "可用" else "暂不可用"}", style = MaterialTheme.typography.bodySmall)
                            Text("余额、签到和扣费均由服务端维护；刷新可同步到其他设备。", style = MaterialTheme.typography.bodySmall)
                            if (account.length() == 0) OutlinedButton(onClick = { run({ repository.state() }) }, enabled = !busy) { Text("重新连接账户服务") }
                        }
                    }
                }
                error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Aster") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
                ledger?.let { entries ->
                    AlertDialog(onDismissRequest = { ledger = null }, title = { Text("积分明细") }, text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (i in 0 until entries.length()) {
                                val row = entries.getJSONObject(i)
                                val kind = when(row.optString("kind")) { "registration" -> "注册奖励"; "checkin" -> "签到奖励"; else -> "模型使用" }
                                Text("$kind  ${row.optString("points")} 积分\n${row.optString("created_at")}")
                            }
                            if (entries.length() == 0) Text("暂无记录")
                        }
                    }, confirmButton = { TextButton(onClick = { ledger = null }) { Text("关闭") } })
                }
            }
        }
    }
    override fun onResume() { super.onResume(); resumed++ }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); callback = intent.data }
}
