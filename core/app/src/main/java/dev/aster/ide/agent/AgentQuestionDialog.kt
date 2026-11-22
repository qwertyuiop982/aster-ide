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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.aster.core.R

@Composable
fun AgentQuestionDialog(
    input: AgentUserInput,
    frame: (@Composable (content: @Composable () -> Unit, confirm: @Composable () -> Unit, dismiss: @Composable () -> Unit) -> Unit)? = null,
    answer: (String, Map<String, List<String>>, Boolean) -> Boolean
) {
    val context = LocalContext.current
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
            error = context.getString(R.string.agent_question_expired)
        }
    }
    val content: @Composable () -> Unit = {
            val body = if (frame == null) Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()) else Modifier
            Column(body.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                input.questions.forEach { question ->
                    key(input.requestId, question.id) {
                        if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.titleSmall)
                        Text(if (question.required) question.question else stringResource(R.string.agent_question_optional, question.question))
                        if (question.url.startsWith("https://") || question.url.startsWith("http://")) {
                            TextButton(onClick = { runCatching { uriHandler.openUri(question.url) }.onFailure { error = context.getString(R.string.agent_question_link_error) } }) { Text(question.url) }
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
                            label = { Text(question.placeholder.ifBlank { stringResource(if (question.options.isEmpty()) R.string.agent_question_answer else R.string.agent_question_custom_answer) }) },
                            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = if (question.secret) 1 else 8,
                            enabled = !submitted, singleLine = question.secret,
                            visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (question.secret) KeyboardType.Password else KeyboardType.Text)
                        )
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
    }
    val confirm: @Composable () -> Unit = {
        Button(onClick = { submit(false) }, enabled = !submitted && input.questions.all { !it.required || values(it).isNotEmpty() }) { Text(stringResource(if (submitted) R.string.agent_question_sending else R.string.agent_question_submit)) }
    }
    val dismiss: @Composable () -> Unit = { TextButton(onClick = { submit(true) }, enabled = !submitted) { Text(stringResource(R.string.agent_question_cancel)) } }
    if (frame != null) frame(content, confirm, dismiss)
    else AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.agent_question_title)) }, text = content, confirmButton = confirm, dismissButton = dismiss)
}
