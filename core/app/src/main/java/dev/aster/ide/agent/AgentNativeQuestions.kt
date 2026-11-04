package dev.aster.ide.agent

import org.json.JSONArray
import org.json.JSONObject

/** Transient questions and credentials must never enter conversation snapshots. */
data class AgentQuestionOption(val label: String, val description: String)
data class AgentQuestion(
    val id: String, val header: String, val question: String,
    val options: List<AgentQuestionOption>, val multiple: Boolean, val placeholder: String,
    val secret: Boolean = false, val required: Boolean = true, val allowCustom: Boolean = true,
    val url: String = ""
)
data class AgentUserInput(val requestId: String, val questions: List<AgentQuestion>)

fun readAgentQuestions(array: JSONArray): List<AgentQuestion> {
    require(array.length() in 1..6) { "每页提问数量必须为 1–6 个" }
    val questions = (0 until array.length()).map { index ->
        val value = array.getJSONObject(index)
        val type = value.optString("type")
        val options = if (type == "text") JSONArray() else if (type == "yesno") JSONArray()
            .put(JSONObject().put("label", "Yes").put("description", "是"))
            .put(JSONObject().put("label", "No").put("description", "否"))
            else value.optJSONArray("options") ?: JSONArray()
        require(options.length() <= 64) { "提问选项超过 64 项" }
        AgentQuestion(
            value.optString("id", index.toString()).ifBlank { index.toString() },
            value.optString("header").take(100), value.optString("question", value.optString("title")),
            (0 until options.length()).map { option ->
                val raw = options.get(option)
                if (raw is String) AgentQuestionOption(raw, "")
                else (raw as JSONObject).let { AgentQuestionOption(it.getString("label"), it.optString("description")) }
            },
            value.optBoolean("multiSelect", value.optBoolean("multi_select", false)), value.optString("placeholder").take(200),
            value.optBoolean("isSecret", value.optBoolean("is_secret", false)), value.optBoolean("required", true),
            value.optBoolean("allowCustom", true), value.optString("url")
        ).also {
            require(it.id.length <= 128 && it.question.isNotBlank() && it.question.length <= 24000) { "提问内容为空或过长" }
            require(it.options.all { option -> option.label.isNotBlank() && option.label.length <= 4000 }) { "提问选项无效" }
        }
    }
    require(questions.map { it.id }.distinct().size == questions.size) { "提问编号重复" }
    return questions
}
