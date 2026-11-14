package dev.aster.agent

import dev.aster.ide.agent.AgentEngine
import dev.aster.ide.agent.AgentProfile
import dev.aster.ide.agent.AgentThinkingPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentThinkingProtocolTest {
    private fun manualProfile(protocol: String = "gemini") = AgentProfile(
        endpoint = "https://provider.example",
        model = "private-model-alias",
        protocol = protocol,
        thinkingMode = "manual",
        thinkingLevel = "high"
    )

    private fun request(level: String, engine: String = "gemini") = JSONObject()
        .put("version", 1).put("command", "thinking")
        .put("workspace", "workspace-1").put("session", "session-1")
        .put("engine", engine).put("id", level)

    private fun validate(request: JSONObject, engine: String = "gemini", mode: String = "manual") =
        AgentWebProtocol.validate(request, "workspace-1", "session-1", engine, mode)

    @Test fun manualAliasSurvivesProtocolAndLevelSelection() {
        for (protocol in listOf("gemini", "openai")) {
            for (level in listOf("high", "Provider_Level.v2-high", "a", "a".repeat(64))) {
                val current = manualProfile(protocol)
                val command = request(level)
                validate(command, mode = current.thinkingMode)
                val selected = AgentThinkingPolicy.selectLevel(AgentEngine.GEMINI, current, command.getString("id"))
                assertEquals(level, selected.thinkingLevel)
                assertEquals("manual", selected.thinkingMode)
                assertEquals(protocol, selected.protocol)
                assertEquals("private-model-alias", selected.model)
            }
        }
    }

    @Test fun customLevelsRequireTheSavedGeminiManualMode() {
        for (engine in listOf("pi", "codex", "openclaude", "gemini")) {
            for (mode in listOf("auto", "legacy", "strength", "manual")) {
                if (engine == "gemini" && mode == "manual") continue
                assertThrows(IllegalArgumentException::class.java) {
                    validate(request("Provider_Level", engine), engine, mode)
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentWebProtocol.validate(request("Provider_Level"), "workspace-1", "session-1", "gemini")
        }
        for (engine in listOf(AgentEngine.PI, AgentEngine.CODEX, AgentEngine.OPENCLAUDE)) {
            assertThrows(IllegalArgumentException::class.java) {
                AgentThinkingPolicy.validate(engine, manualProfile())
            }
        }
    }
    @Test fun malformedManualLevelsAreRejectedByBothLayers() {
        for (level in listOf("", "a".repeat(65), "1high", " high", "high ", "high\n", "high,low", "high/low", "高")) {
            assertThrows(IllegalArgumentException::class.java) { validate(request(level)) }
            assertThrows(IllegalArgumentException::class.java) {
                AgentThinkingPolicy.selectLevel(AgentEngine.GEMINI, manualProfile(), level)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(request("high").put("id", 42))
        }
    }


    @Test fun manualModeDoesNotBypassSessionOrFieldValidation() {
        for ((field, value) in listOf("workspace" to "old-workspace", "session" to "old-session", "engine" to "pi")) {
            assertThrows(IllegalArgumentException::class.java) {
                validate(request("Provider_Level").put(field, value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(request("Provider_Level").put("thinkingMode", "manual"))
        }
    }

    @Test fun selectingAutoLeavesManualMode() {
        validate(request("auto"))
        val selected = AgentThinkingPolicy.selectLevel(AgentEngine.GEMINI, manualProfile(), "auto")
        assertEquals("auto", selected.thinkingMode)
        assertEquals("auto", selected.thinkingLevel)
    }

    @Test fun unknownAliasesStillRequireAnExplicitManualOverride() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentThinkingPolicy.selectLevel(AgentEngine.GEMINI, manualProfile().copy(thinkingMode = "auto"), "high")
        }
    }

    @Test fun builtinEngineLevelsKeepTheirModes() {
        val codex = AgentProfile(endpoint = AgentEngine.CODEX.endpoint, model = "codex-model", thinkingMode = "auto")
        for (level in listOf("none", "max", "ultra")) {
            validate(request(level, "codex"), "codex", "auto")
            val selected = AgentThinkingPolicy.selectLevel(AgentEngine.CODEX, codex, level)
            assertEquals("strength", selected.thinkingMode)
            assertEquals(level, selected.thinkingLevel)
        }
        val gemini25 = manualProfile().copy(model = "gemini-2.5-flash", thinkingMode = "auto")
        assertEquals("legacy", AgentThinkingPolicy.selectLevel(AgentEngine.GEMINI, gemini25, "high").thinkingMode)
        assertEquals("off", AgentThinkingPolicy.selectLevel(AgentEngine.GEMINI, gemini25.copy(thinkingMode = "manual"), "off").thinkingMode)
    }
}
