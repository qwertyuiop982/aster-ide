package dev.aster.ide.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class RuntimeInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun canInstallBootstrap(usr: File) = RuntimeInstaller.canInstallBootstrap(usr) { file ->
        val path = file.toPath()
        when {
            Files.isDirectory(path, NOFOLLOW_LINKS) -> RuntimeInstaller.PrefixEntry.DIRECTORY
            Files.notExists(path, NOFOLLOW_LINKS) -> RuntimeInstaller.PrefixEntry.MISSING
            else -> RuntimeInstaller.PrefixEntry.OTHER
        }
    }

    @Test fun allowsFreshAndEmptyPrefixesAndRecoversTheLegacyTmpPlaceholder() {
        val usr = File(temporary.root, "usr")
        assertTrue(canInstallBootstrap(usr))
        assertTrue(usr.mkdir())
        assertTrue(canInstallBootstrap(usr))
        val tmp = File(usr, "tmp")
        assertTrue(tmp.mkdir())
        assertTrue(canInstallBootstrap(usr))
        assertTrue(tmp.isDirectory)
    }

    @Test fun preservesNonEmptyTmpAndOtherPrefixEntries() {
        val usr = temporary.newFolder("usr")
        val tmp = File(usr, "tmp").apply { mkdir() }
        val data = File(tmp, "data").apply { writeText("keep") }
        assertFalse(canInstallBootstrap(usr))
        assertEquals("keep", data.readText())
        assertTrue(data.delete())

        File(usr, ".keep").writeText("existing")
        assertFalse(canInstallBootstrap(usr))
    }

    @Test fun refusesToReplaceFilesAndSymbolicLinks() {
        val target = temporary.newFolder("target").toPath()
        val usr = File(temporary.root, "usr")
        usr.writeText("keep")
        assertFalse(canInstallBootstrap(usr))
        assertTrue(usr.delete())
        Files.createSymbolicLink(usr.toPath(), target)
        assertFalse(canInstallBootstrap(usr))
        assertTrue(usr.delete())
        assertTrue(usr.mkdir())

        val tmp = File(usr, "tmp").toPath()
        Files.createSymbolicLink(tmp, target)
        assertFalse(canInstallBootstrap(usr))
        Files.delete(tmp)
        Files.createSymbolicLink(tmp, target.resolve("missing"))
        assertFalse(canInstallBootstrap(usr))
        assertTrue(Files.isSymbolicLink(tmp))
    }

    @Test fun recognizesExistingTermuxTreeForNonDestructiveMigration() {
        val usr = temporary.newFolder("legacy-usr")
        listOf("bin/bash", "bin/apt-get", "bin/dpkg").forEach { path ->
            File(usr, path).apply { parentFile!!.mkdirs(); writeText("fixture"); setExecutable(true) }
        }
        File(usr, "lib").mkdirs()
        File(usr, "etc/apt").mkdirs()
        File(usr, "var/lib/dpkg/status").apply { parentFile!!.mkdirs(); writeText("Status: install ok installed\n") }

        assertTrue(RuntimeInstaller.canAdoptExisting(usr))
        assertEquals("fixture", File(usr, "bin/bash").readText())
    }

    @Test fun refusesToAdoptIncompleteExistingToolchain() {
        val usr = temporary.newFolder("partial-usr")
        File(usr, "bin/bash").apply { parentFile!!.mkdirs(); writeText("fixture"); setExecutable(true) }

        assertFalse(RuntimeInstaller.canAdoptExisting(usr))
        assertEquals("fixture", File(usr, "bin/bash").readText())
    }

    @Test fun removesOnlyUnchangedLegacyAptConfiguration() {
        val usr = temporary.newFolder("usr")
        val apt = File(usr, "etc/apt")
        val legacy = File(apt, "apt.conf.d/99aster")
        assertTrue(legacy.parentFile!!.mkdirs())
        val sources = File(apt, "sources.list")
        val existingSource = "deb https://example.test/current stable main\n"
        sources.writeText(existingSource)
        val oldConfiguration = """
            APT::Sandbox::User "root";
            DPkg::Options { "--force-confold"; };
            Acquire::Retries "1";
            Acquire::http::Timeout "15";
            Acquire::https::Timeout "15";
            DPkg::Lock::Timeout "30";
        """.trimIndent() + "\n"

        assertFalse(RuntimeInstaller.removeLegacyAptConfiguration(usr))
        legacy.writeText(oldConfiguration)
        assertTrue(RuntimeInstaller.removeLegacyAptConfiguration(usr))
        assertFalse(legacy.exists())

        // A same-length customization must still be preserved.
        val editedConfiguration = oldConfiguration.replace("\"30\"", "\"60\"")
        legacy.writeText(editedConfiguration)
        assertFalse(RuntimeInstaller.removeLegacyAptConfiguration(usr))
        assertEquals(editedConfiguration, legacy.readText())

        val custom = File(legacy.parentFile, "90custom").apply { writeText(oldConfiguration) }
        assertTrue(legacy.delete())
        Files.createSymbolicLink(legacy.toPath(), custom.toPath())
        assertFalse(RuntimeInstaller.removeLegacyAptConfiguration(usr))
        assertTrue(Files.isSymbolicLink(legacy.toPath()))
        assertEquals(oldConfiguration, custom.readText())
        assertEquals(existingSource, sources.readText())
    }
}
