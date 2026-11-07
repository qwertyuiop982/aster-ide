package dev.aster.ide.agent

/** Keep the newest messages within the existing count and character budgets. */
fun boundedAgentMessages(messages: List<AgentMessage>): List<AgentMessage> {
    if (messages.size <= 60) {
        var total = 0
        var fits = true
        for (message in messages) {
            if (message.text.length > 24000) { fits = false; break }
            total += message.text.length
        }
        if (fits && total <= 200000) return messages
    }
    val kept = ArrayList<AgentMessage>(minOf(60, messages.size))
    var characters = 0
    var unchanged = messages.size <= 60
    for (index in messages.lastIndex downTo maxOf(0, messages.size - 60)) {
        val message = messages[index]
        val length = minOf(message.text.length, 24000)
        if (characters + length > 200000) { unchanged = false; continue }
        characters += length
        if (length == message.text.length) kept.add(message)
        else { kept.add(message.copy(text = message.text.take(24000))); unchanged = false }
    }
    if (unchanged) return messages
    kept.reverse()
    return kept
}

/** Retain every child identity; unchanged transcripts preserve object identity. */
fun boundedSubagentConversations(rows: List<AgentSubagentConversation>): List<AgentSubagentConversation> {
    var remaining = 800000
    var changed: MutableList<AgentSubagentConversation>? = null
    for (index in rows.indices.reversed()) {
        val row = rows[index]
        val bounded = boundedAgentMessages(row.messages)
        var allowance = minOf(100000, remaining)
        val total = bounded.sumOf { it.text.length }
        if (total <= allowance) {
            remaining -= total
            if (bounded !== row.messages) {
                if (changed == null) changed = rows.toMutableList()
                changed[index] = row.copy(messages = bounded)
            }
            continue
        }
        val kept = ArrayList<AgentMessage>(bounded.size)
        for (message in bounded.asReversed()) {
            if (message.text.length > allowance) continue
            kept.add(message)
            allowance -= message.text.length
            remaining -= message.text.length
        }
        val messages = if (kept.size == bounded.size) bounded else kept.apply { reverse() }
        if (messages !== row.messages) {
            if (changed == null) changed = rows.toMutableList()
            changed[index] = row.copy(messages = messages)
        }
    }
    return changed ?: rows
}
