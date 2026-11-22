package dev.aster.ide.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolEventsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun event(kind: String, text: String, job: String = "job-a", turn: String = "turn-a", status: String = "") =
        AgentToolEvent.from(job, JSONObject()
            .put("callId", "call-1").put("turnId", turn).put("kind", kind)
            .put("name", "shell").put("status", status).put("text", text)) { "终端" }

    @Test fun completionPreservesArgumentsAndStreamedOutput() {
        val start = event("tool", "pwd\n结果\nliteral argument").applyTo(emptyList())
        val stream = event("tool_delta", "/project").applyTo(start)
        val end = event("tool_result", "exit 0").applyTo(stream)
        assertEquals(start.single().id, end.single().id)
        assertEquals("shell · 终端 · 完成\n参数\npwd\n结果\nliteral argument\n输出\n/project\n结果\nexit 0", end.single().text)
        assertSame(end, event("tool_delta", "late").applyTo(end))
        assertSame(end, event("tool", "duplicate").applyTo(end))
    }

    @Test fun callIdsAreLocalToAJobAndTurn() {
        val start = event("tool", "job a turn a").applyTo(emptyList())
        val nextTurn = event("tool", "job a turn b", turn = "turn-b").applyTo(start)
        val nextJob = event("tool", "job b turn a", job = "job-b").applyTo(nextTurn)
        val done = event("tool_result", "failed in job a turn b", turn = "turn-b", status = "failed").applyTo(nextJob)
        assertEquals(3, done.map { it.id }.toSet().size)
        assertSame(nextJob[0], done[0])
        assertSame(nextJob[2], done[2])
        assertTrue(done[1].text.contains(" · 失败"))
        assertTrue(done[1].text.contains("job a turn b"))
    }

    @Test fun childCompletionCanBeFollowedByItsFinalToolResult() {
        val start = event("tool", "pwd").applyTo(emptyList())
        val saved = interruptAgentTools(start, allowLateResults = true)
        assertTrue(saved.single().text.contains(" · 已中断"))
        assertSame(saved, event("tool_delta", "late").applyTo(saved))
        val done = event("tool_result", "exit 0").applyTo(saved)
        assertTrue(done.single().text.contains(" · 完成"))
        assertTrue(done.single().text.contains("参数\npwd"))
    }

    @Test fun switchingAndRestoringDoesNotLoseToolResultsOrReplayInterruptedCalls() {
        val start = event("tool", "pwd").applyTo(emptyList())
        val done = event("tool_result", "/project").applyTo(start)
        val store = AgentConversationStore(temporary.newFolder())
        val first = AgentSnapshot("parent", "workspace", "parent", 1, messages = done, wasRunning = true,
            subagentConversations = listOf(AgentSubagentConversation("child", "child", messages = start)))
        val second = AgentSnapshot("other", "workspace", "other", 2, messages = start)
        store.save(first)
        store.save(second)
        store.select("workspace", "other")
        assertTrue(store.load("workspace", "other")!!.messages.single().text.contains(" · 已中断"))
        store.select("workspace", "parent")
        val restored = store.load("workspace", "parent")!!
        assertEquals(done, restored.messages)
        val child = restored.subagentConversations.single()
        assertFalse(child.running)
        assertEquals(start.single().id, child.messages.single().id)
        assertTrue(child.messages.single().text.contains(" · 已中断"))
        assertTrue(child.messages.single().text.contains("参数\npwd"))
        assertNull(child.messages.single().toolTrace)
    }
}
