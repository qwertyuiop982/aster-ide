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
            var accountNotice by remember { mutableStateOf<String?>(null) }
            var modelSettingsExpanded by remember { mutableStateOf(false) }
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
                            val capabilities = account.optJSONObject("capabilities") ?: JSONObject()
                            val loggedIn = account.optBoolean("logged_in")
                            if (!loggedIn) {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Aster", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                            Text("登录账户", style = MaterialTheme.typography.titleLarge)
                                            Text("同步积分与账户状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Button(onClick = { run({ JSONObject().put("url", repository.githubStart()) }, { reply -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reply.getString("url")))) }) }, enabled = !busy && capabilities.optBoolean("github_login"), modifier = Modifier.fillMaxWidth()) { Text("使用 GitHub 登录") }
                                        if (capabilities.optBoolean("email_login")) {
                                            HorizontalDivider()
                                            OutlinedTextField(email, { email = it; accountNotice = null }, label = { Text("邮箱地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && challenge.isBlank(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                                            if (challenge.isBlank()) {
                                                OutlinedButton(onClick = { run({ repository.emailStart(email) }, { reply -> challenge = reply.getString("challenge_id"); accountNotice = "验证码已发送，请查收邮件" }) }, enabled = !busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("发送验证码") }
                                            } else {
                                                OutlinedTextField(code, { code = it }, label = { Text("6 位验证码") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                                Button(onClick = { run({ repository.emailVerify(challenge, code); repository.state() }) }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth()) { Text("邮箱登录") }
                                                TextButton(onClick = { challenge = ""; code = ""; accountNotice = null }, enabled = !busy) { Text("更换邮箱") }
                                            }
                                            accountNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                                        }
                                    }
                                }
                                TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("返回工作空间") }
                            } else {
                                val wallet = account.optJSONObject("wallet") ?: JSONObject()
                                Text(account.optJSONObject("identity")?.optString("display_name") ?: "已登录", style = MaterialTheme.typography.titleLarge)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("可用积分", style = MaterialTheme.typography.labelLarge)
                                        Text(wallet.optString("available", "—"), style = MaterialTheme.typography.displaySmall)
                                        Text("总积分 ${wallet.optString("balance", "—")} · 预占 ${wallet.optString("reserved", "0.000")}", style = MaterialTheme.typography.bodySmall)
                                        Text("${wallet.optString("beijing_date")} · 每日更新", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Button(onClick = { run({ repository.checkin(); repository.state() }) }, enabled = !busy && !wallet.optBoolean("checked_in"), modifier = Modifier.fillMaxWidth()) { Text(if (wallet.optBoolean("checked_in")) "今日已签到" else "签到 +100 积分") }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { run({ repository.state() }) }, enabled = !busy) { Text("刷新账户") }
                                    OutlinedButton(onClick = { run({ repository.ledger() }, { ledger = it.optJSONArray("items") }) }, enabled = !busy) { Text("积分明细") }
                                }
                                TextButton(onClick = { run({ repository.logout(); repository.state() }) }, enabled = !busy) { Text("退出登录") }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("Pi 模型设置", style = MaterialTheme.typography.titleMedium)
                                            Text(if (settings.agentUseFree) "Aster-free · ${if (settings.agentFreeTier == "go") "Go" else "Free"}" else "自定义 API", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { modelSettingsExpanded = !modelSettingsExpanded }) { Text(if (modelSettingsExpanded) "收起" else "设置") }
                                    }
                                    if (modelSettingsExpanded) {
                                        HorizontalDivider()
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text("使用账户模型")
                                                Text("Free 或 Go", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(settings.agentUseFree, { enabled -> core.settings.update { it.copy(agentUseFree = enabled) } })
                                        }
                                        if (settings.agentUseFree) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FilterChip(selected = settings.agentFreeTier == "free", onClick = { core.settings.update { it.copy(agentFreeTier = "free") } }, label = { Text("Free") })
                                                FilterChip(selected = settings.agentFreeTier == "go", onClick = { core.settings.update { it.copy(agentFreeTier = "go") } }, label = { Text("Go") })
                                            }
                                            Text("Free：1000 tokens / 1 积分（${if (capabilities.optBoolean("free")) "可用" else "暂不可用"}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("Go：1000 tokens / 50 积分（${if (capabilities.optBoolean("free_go")) "可用" else "暂不可用"}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
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
