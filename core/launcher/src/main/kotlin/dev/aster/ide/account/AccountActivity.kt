package dev.aster.ide.account

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.core.AppLanguages
import dev.aster.core.AsterDialog
import dev.aster.core.CoreRuntime
import dev.aster.core.LocalAsterDialogMotion
import dev.aster.ide.R
import dev.aster.ide.ui.AsterTheme
import dev.aster.core.R as CoreR
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
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguages.wrapLegacyContext(newBase))
    }
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
                        catch (e: Exception) { error = e.message ?: getString(R.string.account_failed) }
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
                            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CoreR.string.action_back)) }
                            Text(stringResource(CoreR.string.title_account), style = MaterialTheme.typography.titleMedium)
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
                                            Text(stringResource(R.string.account_sign_in), style = MaterialTheme.typography.titleLarge)
                                            Text(stringResource(R.string.account_sync), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Button(onClick = { run({ JSONObject().put("url", repository.githubStart()) }, { reply -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reply.getString("url")))) }) }, enabled = !busy && capabilities.optBoolean("github_login"), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_github)) }
                                        if (capabilities.optBoolean("email_login")) {
                                            HorizontalDivider()
                                            OutlinedTextField(email, { email = it; accountNotice = null }, label = { Text(stringResource(R.string.account_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && challenge.isBlank(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                                            if (challenge.isBlank()) {
                                                OutlinedButton(onClick = { run({ repository.emailStart(email) }, { reply -> challenge = reply.getString("challenge_id"); accountNotice = getString(R.string.account_code_sent) }) }, enabled = !busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_send_code)) }
                                            } else {
                                                OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.account_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                                Button(onClick = { run({ repository.emailVerify(challenge, code); repository.state() }) }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_sign_in_email)) }
                                                TextButton(onClick = { challenge = ""; code = ""; accountNotice = null }, enabled = !busy) { Text(stringResource(R.string.account_change_email)) }
                                            }
                                            accountNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                                        }
                                    }
                                }
                                TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.account_back_to_workspace)) }
                            } else {
                                val wallet = account.optJSONObject("wallet") ?: JSONObject()
                                Text(account.optJSONObject("identity")?.optString("display_name") ?: stringResource(R.string.account_signed_in), style = MaterialTheme.typography.titleLarge)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.account_available_points), style = MaterialTheme.typography.labelLarge)
                                        Text(wallet.optString("available", "—"), style = MaterialTheme.typography.displaySmall)
                                        Text(stringResource(R.string.account_points_detail, wallet.optString("balance", "—"), wallet.optString("reserved", "0.000")), style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.account_points_date, wallet.optString("beijing_date")), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Button(onClick = { run({ repository.checkin(); repository.state() }) }, enabled = !busy && !wallet.optBoolean("checked_in"), modifier = Modifier.fillMaxWidth()) { Text(if (wallet.optBoolean("checked_in")) stringResource(R.string.account_checked_in) else stringResource(R.string.account_check_in)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { run({ repository.state() }) }, enabled = !busy) { Text(stringResource(R.string.account_refresh)) }
                                    OutlinedButton(onClick = { run({ repository.ledger() }, { ledger = it.optJSONArray("items") }) }, enabled = !busy) { Text(stringResource(R.string.account_ledger)) }
                                }
                                TextButton(onClick = { run({ repository.logout(); repository.state() }) }, enabled = !busy) { Text(stringResource(R.string.account_sign_out)) }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(stringResource(R.string.account_pi_model), style = MaterialTheme.typography.titleMedium)
                                            Text(if (settings.agentUseFree) "Aster-free · ${if (settings.agentFreeTier == "go") "Go" else "Free"}" else stringResource(R.string.account_custom_api), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { modelSettingsExpanded = !modelSettingsExpanded }) { Text(if (modelSettingsExpanded) stringResource(R.string.account_collapse) else stringResource(CoreR.string.action_settings)) }
                                    }
                                    if (modelSettingsExpanded) {
                                        HorizontalDivider()
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(stringResource(R.string.account_use_model))
                                                Text(stringResource(R.string.account_tier_options), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(settings.agentUseFree, { enabled -> core.settings.update { it.copy(agentUseFree = enabled) } })
                                        }
                                        if (settings.agentUseFree) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FilterChip(selected = settings.agentFreeTier == "free", onClick = { core.settings.update { it.copy(agentFreeTier = "free") } }, label = { Text("Free") })
                                                FilterChip(selected = settings.agentFreeTier == "go", onClick = { core.settings.update { it.copy(agentFreeTier = "go") } }, label = { Text("Go") })
                                            }
                                            Text(stringResource(R.string.account_free_pricing, stringResource(if (capabilities.optBoolean("free")) R.string.account_available else R.string.account_unavailable)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(stringResource(R.string.account_go_pricing, stringResource(if (capabilities.optBoolean("free_go")) R.string.account_available else R.string.account_unavailable)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (account.length() == 0) OutlinedButton(onClick = { run({ repository.state() }) }, enabled = !busy) { Text(stringResource(R.string.account_reconnect)) }
                        }
                    }
                }
                CompositionLocalProvider(LocalAsterDialogMotion provides (settings.animations && !settings.reducedMotion)) {
                    error?.let { message ->
                        AsterDialog("Aster", onDismissRequest = { error = null },
                            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(CoreR.string.action_ok)) } }) { Text(message) }
                    }
                    ledger?.let { entries ->
                        AsterDialog(stringResource(R.string.account_ledger), onDismissRequest = { ledger = null },
                            confirmButton = { TextButton(onClick = { ledger = null }) { Text(stringResource(CoreR.string.action_close)) } }) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (i in 0 until entries.length()) {
                                    val row = entries.getJSONObject(i)
                                    val kind = when(row.optString("kind")) { "registration" -> stringResource(R.string.account_ledger_registration); "checkin" -> stringResource(R.string.account_ledger_checkin); else -> stringResource(R.string.account_ledger_usage) }
                                    Text(stringResource(R.string.account_ledger_line, kind, row.optString("points"), row.optString("created_at")))
                                }
                                if (entries.length() == 0) Text(stringResource(R.string.account_no_history))
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); resumed++ }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); callback = intent.data }
}
