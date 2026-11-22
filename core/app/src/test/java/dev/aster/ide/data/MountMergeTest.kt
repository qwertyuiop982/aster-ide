package dev.aster.ide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MountMergeTest {
    @Test fun initialMountTakesRemoteWithEmptyDirectories() {
        val original = mapOf("README" to "r1", "src" to MountMerge.DIRECTORY, "src/main.kt" to "k1", "empty" to MountMerge.DIRECTORY)
        assertEquals(original, MountMerge.merge(emptyMap(), emptyMap(), original))
    }

    @Test fun differentFilesCanChangeAtBothEnds() {
        val base = mapOf("local" to "l1", "remote" to "r1")
        assertEquals(mapOf("local" to "l2", "remote" to "r2"),
            MountMerge.merge(base, base + ("local" to "l2"), base + ("remote" to "r2")))
    }

    @Test fun matchingEditsResumeAfterInterruptedSync() {
        val original = mapOf("main" to "old")
        val updated = mapOf("main" to "new")
        assertEquals(updated, MountMerge.merge(original, updated, updated))
    }

    @Test fun deletionsRequireUnchangedCounterpart() {
        val original = mapOf("main" to "old")
        assertEquals(emptyMap<String, String>(), MountMerge.merge(original, emptyMap(), original))
        assertThrows(IllegalStateException::class.java) {
            MountMerge.merge(original, emptyMap(), mapOf("main" to "changed"))
        }
    }

    @Test fun deletingDirectoryDoesNotDiscardNewRemoteChildren() {
        val original = mapOf("src" to MountMerge.DIRECTORY)
        assertThrows(IllegalStateException::class.java) {
            MountMerge.merge(original, emptyMap(), original + ("src/new.kt" to "new"))
        }
    }

    @Test fun doesNotReplaceDirectoryWithFile() {
        val original = mapOf("src" to MountMerge.DIRECTORY)
        assertThrows(IllegalStateException::class.java) {
            MountMerge.merge(original, mapOf("src" to "file"), original)
        }
    }
}
