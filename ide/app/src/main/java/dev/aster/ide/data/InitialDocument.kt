package dev.aster.ide.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Opening a convenient first tab must not prevent entering a workspace. */
object InitialDocument {
    suspend fun open(
        nodes: List<FileNode>,
        openDocument: suspend (String) -> Unit,
        onSkipped: (String, Exception) -> Unit = { _, _ -> }
    ): Boolean {
        val candidates = nodes.asSequence().filterNot { it.directory }
            .sortedBy { it.path != "src/main.py" }
            .take(16)
        for (node in candidates) {
            coroutineContext.ensureActive()
            try {
                openDocument(node.path)
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onSkipped(node.path, error)
            }
        }
        return false
    }
}
