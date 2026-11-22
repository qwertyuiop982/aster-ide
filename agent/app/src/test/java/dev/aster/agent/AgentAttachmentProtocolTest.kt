package dev.aster.agent

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentAttachmentProtocolTest {
    private fun request(command: String) = JSONObject()
        .put("version", 1).put("command", command).put("workspace", "project")
        .put("session", "session").put("engine", "codex")

    private fun validate(request: JSONObject) = AgentWebProtocol.validate(request, "project", "session", "codex")

    @Test fun pickerCommandsDoNotAcceptUrisOrImageBytes() {
        for (command in listOf("pick_images", "pick_documents", "context_files")) {
            validate(request(command))
            assertThrows(IllegalArgumentException::class.java) {
                validate(request(command).put("uri", "content://test/file"))
            }
        }
        validate(request("send").put("text", ""))
        assertThrows(IllegalArgumentException::class.java) {
            validate(request("send").put("text", "").put("images", "base64"))
        }
    }

    @Test fun bubblePositionIsNormalizedAndScopedToTheSession() {
        validate(request("bubble_hold"))
        validate(request("bubble_position").put("x", 0.0).put("y", 1.0))
        for (value in listOf(-0.01, 1.01, "0.5")) {
            assertThrows(IllegalArgumentException::class.java) {
                validate(request("bubble_position").put("x", value).put("y", 0.5))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(request("bubble_position").put("session", "previous").put("x", 0.5).put("y", 0.5))
        }
    }

    @Test fun attachmentRemovalOnlyAcceptsAnOpaqueId() {
        validate(request("remove_attachment").put("id", "0fc84891-c480-453d-b821-84e2e5822bdb"))
        assertThrows(IllegalArgumentException::class.java) {
            validate(request("remove_attachment").put("id", "../../file"))
        }
    }
}
