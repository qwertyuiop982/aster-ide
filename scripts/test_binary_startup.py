"""Run the real Kotlin initial-tab policy and UTF-8 document loader on binary workspaces.

Android-only workspace/settings objects are replaced with local file fixtures; the
production InitialDocument, EditorDocument and ContentLoader sources are compiled
without modification, against the cached Compose and Sora libraries.
"""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", "/root/.gradle")) / "caches/modules-2/files-2.1"
FIXTURES = r'''
package dev.aster.ide.data
import java.io.File

data class Workspace(val directory: File)
data class FileNode(val path: String, val name: String, val directory: Boolean, val depth: Int, val size: Long)
data class IdeSettings(val maxFileMB: Int = 8, val largeFileKB: Int = 512)
class WorkspaceRepository {
    fun resolve(workspace: Workspace, path: String): File = File(workspace.directory, path)
}
'''
PROBE = r'''
import dev.aster.ide.data.*
import kotlinx.coroutines.*
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val root = File(args[0])
    val repo = WorkspaceRepository()
    val workspace = Workspace(root)
    val settings = IdeSettings()
    fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }
    fun node(path: String, directory: Boolean = false) =
        FileNode(path, File(path).name, directory, path.count { it == '/' }, File(root, path).length())
    val binary = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0) + ByteArray(120)
    write("usr/bin/00-self-built", binary)
    write("README.md", "# Project\nText remains editable.\n".toByteArray())
    write("src/main.py", "print('ready')\n".toByteArray())
    write("invalid.txt", byteArrayOf(0xc3.toByte(), 0x28))
    val originalBinary = File(root, "usr/bin/00-self-built").readBytes()
    val attempts = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val documents = mutableListOf<EditorDocument>()
    val openDocument: suspend (String) -> Unit = { path ->
        attempts += path
        documents += repo.open(workspace, path, settings)
    }
    val onSkipped: (String, Exception) -> Unit = { path, error ->
        skipped += "$path: ${error.message}"
    }
    fun reset() { attempts.clear(); skipped.clear(); documents.clear() }

    // Reproduce the screenshot's exact exception using the production text loader.
    val original = try { repo.open(workspace, "usr/bin/00-self-built", settings); null }
                   catch (error: IllegalStateException) { error }
    check(original?.message == "这是二进制文件") { "Screenshot error was not reproduced" }
    println("PASS: reproduced the exact screenshot error before automatic-tab recovery")

    reset()
    check(InitialDocument.open(listOf(node("usr", true), node("usr/bin/00-self-built"), node("README.md")), openDocument, onSkipped))
    check(attempts == listOf("usr/bin/00-self-built", "README.md"))
    check(skipped.single().endsWith("这是二进制文件"))
    check(documents.single().buffer.toString() == "# Project\nText remains editable.\n")
    check(!documents.single().dirty)
    println("PASS: a binary sorted before text does not prevent opening the workspace")

    reset()
    check(!InitialDocument.open(listOf(node("usr", true), node("usr/bin/00-self-built")), openDocument, onSkipped))
    check(documents.isEmpty() && skipped.size == 1)
    println("PASS: a binary-only workspace finishes with no automatic text tab")

    reset()
    check(!InitialDocument.open(emptyList(), openDocument, onSkipped))
    check(!InitialDocument.open(listOf(node("usr", true)), openDocument, onSkipped))
    check(attempts.isEmpty())
    println("PASS: empty and directory-only workspaces do not attempt to open a file")

    reset()
    check(InitialDocument.open(listOf(node("usr/bin/00-self-built"), node("README.md"), node("src/main.py")), openDocument, onSkipped))
    check(attempts == listOf("src/main.py"))
    check(documents.single().buffer.toString() == "print('ready')\n")
    println("PASS: the existing src/main.py preference is preserved")

    reset()
    write("src/main.py", binary)
    check(InitialDocument.open(listOf(node("README.md"), node("src/main.py")), openDocument, onSkipped))
    check(attempts == listOf("src/main.py", "README.md"))
    println("PASS: an unreadable preferred source falls back to a readable file")

    reset()
    check(InitialDocument.open(listOf(node("invalid.txt"), node("README.md")), openDocument, onSkipped))
    check(skipped.single().contains("UTF-8"))
    println("PASS: unsupported UTF-8 content does not prevent startup")

    reset()
    check(InitialDocument.open(listOf(node("removed.txt"), node("README.md")), openDocument, onSkipped))
    check(skipped.single().endsWith("文件不存在"))
    println("PASS: a file removed after listing does not prevent startup")

    reset()
    File(root, "oversized.dat").apply {
        java.io.RandomAccessFile(this, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
    }
    check(InitialDocument.open(listOf(node("oversized.dat"), node("README.md")), openDocument, onSkipped))
    check(skipped.single().contains("文件超过"))
    println("PASS: an oversized file does not prevent startup")

    reset()
    val cancellation = CancellationException("cancel initial document")
    val thrown = try {
        InitialDocument.open(listOf(node("README.md")), { throw cancellation }, onSkipped)
        null
    } catch (error: CancellationException) { error }
    check(thrown === cancellation && skipped.isEmpty())
    println("PASS: cancellation propagates without being treated as an unreadable file")

    reset()
    val cancelled = Job().apply { cancel() }
    try {
        withContext(cancelled) {
            InitialDocument.open(listOf(node("README.md")), openDocument, onSkipped)
        }
    } catch (_: CancellationException) { }
    check(attempts.isEmpty() && skipped.isEmpty())
    println("PASS: an already-cancelled task does not keep scanning files")

    check(File(root, "usr/bin/00-self-built").readBytes().contentEquals(originalBinary))
    check(File(root, "README.md").readText() == "# Project\nText remains editable.\n")
    println("PASS: original binaries and source files remain unchanged")
}
'''


def jar(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))


def main():
    viewmodel = (ROOT / "ide/app/src/main/java/dev/aster/ide/IdeViewModel.kt").read_text()
    assert "InitialDocument.open(nodes, ::openInternal)" in viewmodel
    assert "nodes.firstOrNull" not in viewmodel, "Automatic first-file opening is still present"
    switch = viewmodel.split("private suspend fun switchInternal", 1)[1].split("fun createFile", 1)[0]
    prepare = viewmodel.split("private suspend fun prepareEditor", 1)[1].split("fun continueLocally", 1)[0]
    assert "openInitialDocument()" in switch
    assert "if (active == null) openInitialDocument()" in prepare
    assert prepare.index("openInitialDocument()") < prepare.index("editorReady = true")
    assert "try { openInternal(path) }" in viewmodel, "Manual file opening must retain its error handling"
    compiler = [
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.0"),
        jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.0"),
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.1.0"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
        jar("org.jetbrains", "annotations", "13.0"),
    ]
    java = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-arm64")) / "bin/java"
    with tempfile.TemporaryDirectory(prefix="aster-binary-startup-") as directory:
        folder = Path(directory)
        android_jar = Path(os.environ.get("ANDROID_HOME", "/root/Android")) / "platforms/android-35/android.jar"
        libraries = [compiler[1], compiler[5], compiler[6], android_jar]
        for group, name, version, entry in [
            ("io.github.rosemoe", "editor", "0.24.4", "classes.jar"),
            ("androidx.compose.runtime", "runtime-android", "1.7.1", "classes.jar"),
        ]:
            aar = next((CACHE / group / name / version).glob("*/*.aar"))
            target = folder / (name + ".jar")
            with zipfile.ZipFile(aar) as archive:
                target.write_bytes(archive.read(entry))
            libraries.append(target)
        fixture = folder / "Fixtures.kt"
        probe = folder / "BinaryStartupProbe.kt"
        fixture.write_text(FIXTURES)
        probe.write_text(PROBE)
        classes = folder / "classes"
        classpath = os.pathsep.join(map(str, libraries))
        production = [
            ROOT / "ide/app/src/main/java/dev/aster/ide/data/InitialDocument.kt",
            ROOT / "ide/app/src/main/java/dev/aster/ide/data/EditorDocument.kt",
            ROOT / "ide/app/src/main/java/dev/aster/ide/editor/ContentLoader.kt",
        ]
        subprocess.run([
            str(java), "-Xmx384m", "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", classpath, "-d", str(classes),
            *map(str, production), str(fixture), str(probe),
        ], check=True, timeout=90)
        workspace = folder / "workspace"
        workspace.mkdir()
        subprocess.run([
            str(java), "-cp", os.pathsep.join([str(classes), classpath]),
            "BinaryStartupProbeKt", str(workspace),
        ], check=True, timeout=30)
    print(json.dumps({"success": True, "cases": 12, "realTextLoader": True,
                      "startupAndProjectSwitchGuarded": True, "cancellationPreserved": True,
                      "originalFilesUnchanged": True}))


if __name__ == "__main__":
    main()
