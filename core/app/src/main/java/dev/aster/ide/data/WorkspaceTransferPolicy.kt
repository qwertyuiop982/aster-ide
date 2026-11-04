package dev.aster.ide.data

/** ACS editor state is not a project input. Never manufacture it through import/sync. */
object WorkspaceTransferPolicy {
    fun shouldCopy(relativePath: String): Boolean = relativePath.split('/').none { it == ".androidide" }
}
