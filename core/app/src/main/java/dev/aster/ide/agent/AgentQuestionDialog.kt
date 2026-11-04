package dev.aster.ide.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AgentQuestionDialog(input: AgentUserInput, answer: (String, Map<String, List<String>>, Boolean) -> Boolean) {
    val selected = remember(input.requestId) { mutableStateMapOf<String, Set<String>>() }
    val text = remember(input.requestId) { mutableStateMapOf<String, String>() }
    var submitted by remember(input.requestId) { mutableStateOf(false) }
    var error by remember(input.requestId) { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    fun values(question: AgentQuestion): List<String> {
        val custom = text[question.id]?.trim()?.takeIf(String::isNotEmpty)
        if (!question.multiple && custom != null) return listOf(custom)
        return selected[question.id].orEmpty().toList() + listOfNotNull(custom)
    }
    fun submit(dismissed: Boolean) {
        if (submitted) return
        submitted = true
        if (!answer(input.requestId, if (dismissed) emptyMap() else input.questions.associate { it.id to values(it) }, dismissed)) {
            submitted = false
            error = "此提问已结束或提交未被接受，请返回任务查看状态。"
        }
    }
    AlertDialog(
        onDismissRequest = { /* Explicit cancel prevents accidental loss when using the keyboard. */ },
        title = { Text("补充信息") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                input.questions.forEach { question ->
                    key(input.requestId, question.id) {
                        if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.titleSmall)
                        Text(question.question + if (question.required) "" else "（可选）")
                        if (question.url.startsWith("https://") || question.url.startsWith("http://")) {
                            TextButton(onClick = { runCatching { uriHandler.openUri(question.url) }.onFailure { error = "无法打开链接，请手动在浏览器中访问。" } }) { Text(question.url) }
                        }
                        question.options.forEach { option ->
                            val checked = option.label in selected[question.id].orEmpty()
                            val toggle = {
                                text.remove(question.id)
                                selected[question.id] = if (question.multiple) {
                                    if (checked) selected[question.id].orEmpty() - option.label else selected[question.id].orEmpty() + option.label
                                } else if (checked && !question.required) emptySet() else setOf(option.label)
                            }
                            Row(Modifier.fillMaxWidth().clickable(enabled = !submitted, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                                if (question.multiple) Checkbox(checked, { toggle() }, enabled = !submitted) else RadioButton(checked, { toggle() }, enabled = !submitted)
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(option.label)
                                    if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (question.allowCustom || question.options.isEmpty()) OutlinedTextField(
                            value = text[question.id].orEmpty(), onValueChange = {
                                text[question.id] = it.take(24000)
                                if (!question.multiple && it.isNotBlank()) selected.remove(question.id)
                            },
                            label = { Text(question.placeholder.ifBlank { if (question.options.isEmpty()) "回答" else "自定义回答" }) },
                            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = if (question.secret) 1 else 8,
                            enabled = !submitted, singleLine = question.secret,
                            visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (question.secret) KeyboardType.Password else KeyboardType.Text)
                        )
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { submit(false) }, enabled = !submitted && input.questions.all { !it.required || values(it).isNotEmpty() }) { Text(if (submitted) "正在提交" else "提交") }
        },
        dismissButton = { TextButton(onClick = { submit(true) }, enabled = !submitted) { Text("取消提问") } }
    )
}
