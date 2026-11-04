"""Offline JVM regression for ACS metadata exclusion; original files remain untouched."""
from pathlib import Path
import json
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
# Compile WorkspaceTransferPolicy.kt directly for an offline, build-independent JVM test.
STDLIB = Path(os.environ.get('GRADLE_USER_HOME', '/root/.gradle')) / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
JDK = '/usr/lib/jvm/java-17-openjdk-arm64/bin/'


def main():
    library = sorted(STDLIB.glob('*/*/*.jar'))[-1]
    source = r'''
import dev.aster.ide.data.WorkspaceTransferPolicy;
import java.nio.file.*;
import java.util.*;
public class WorkspaceTransferProbe {
    public static void main(String[] args) throws Exception {
        String[] excluded = {".androidide", ".androidide/", ".androidide/editor/openedFiles.json.bak", "module/.androidide/ui/font.ttf"};
        String[] preserved = {".android", ".android/debug.keystore", ".androidide_root", "androidide", ".androidide-backup/file", "app/src/Main.kt", ".aster/skills/demo/SKILL.md", "gradle/wrapper/gradle-wrapper.properties"};
        for (String path : excluded) if (WorkspaceTransferPolicy.INSTANCE.shouldCopy(path)) throw new AssertionError("ACS state copied: " + path);
        for (String path : preserved) if (!WorkspaceTransferPolicy.INSTANCE.shouldCopy(path)) throw new AssertionError("Project input lost: " + path);
        Path original = Paths.get(args[0], "original"), imported = Paths.get(args[0], "imported"), mirrored = Paths.get(args[0], "mirrored");
        Files.createDirectories(original);
        Files.createDirectories(imported);
        Files.createDirectories(mirrored);
        String[] fixture = {".androidide/editor/openedFiles.json.bak", ".android/debug.keystore", "app/src/Main.kt", "gradle/wrapper/gradle-wrapper.properties"};
        for (String path : fixture) {
            Path file = original.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "fixture:" + path);
        }
        for (Path destination : Arrays.asList(imported, mirrored)) {
            try (var files = Files.walk(original)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String relative = original.relativize(file).toString().replace('\\', '/');
                    if (!WorkspaceTransferPolicy.INSTANCE.shouldCopy(relative)) continue;
                    Path target = destination.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
            if (Files.exists(destination.resolve(".androidide"))) throw new AssertionError("ACS state directory recreated");
            for (String path : preserved) {
                if (Files.isRegularFile(original.resolve(path)) && !Files.exists(destination.resolve(path))) throw new AssertionError(path);
            }
        }
        for (String path : fixture) if (!Files.readString(original.resolve(path)).equals("fixture:" + path)) throw new AssertionError("Original changed: " + path);
        System.out.println("PASS: 4 ACS metadata paths excluded, 8 project paths preserved, import/mirror do not create .androidide, original files unchanged");
    }
}
'''
    # These must remain attached to the real import and sync paths, not just a test helper.
    workspace = (ROOT / 'core/app/src/main/java/dev/aster/ide/data/Workspace.kt').read_text()
    bridge = (ROOT / 'core/app/src/main/java/dev/aster/ide/runtime/BridgeClient.kt').read_text()
    assert 'if (!WorkspaceTransferPolicy.shouldCopy(path)) return@forEach' in workspace
    assert 'WorkspaceTransferPolicy.shouldCopy(it.path)' in bridge
    assert 'WorkspaceTransferPolicy.shouldCopy(it.substringAfter' in bridge
    with tempfile.TemporaryDirectory(prefix='aster-workspace-transfer-') as directory:
        folder = Path(directory)
        file = folder / 'WorkspaceTransferProbe.java'
        file.write_text(source)
        # Compile the real tiny policy directly; no concurrent Gradle build or stale Debug classes.
        cache = Path(os.environ.get('GRADLE_USER_HOME', '/root/.gradle')) / 'caches/modules-2/files-2.1'
        def jar(group, name, version):
            return next((cache / group / name / version).glob('*/*.jar'))
        compilerpath = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.0'),
                        jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.0'),
                        jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.1.0'),
                        jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                        jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                        jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'),
                        jar('org.jetbrains', 'annotations', '13.0')]
        classes = folder / 'policy-classes'
        subprocess.run([JDK + 'java', '-Xmx256m', '-cp', os.pathsep.join(map(str, compilerpath)),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '17', '-classpath', str(library), '-d', str(classes),
                        str(ROOT / 'core/app/src/main/java/dev/aster/ide/data/WorkspaceTransferPolicy.kt')], check=True, timeout=90)
        classpath = os.pathsep.join([str(classes), str(library), directory])
        subprocess.run([JDK + 'javac', '-cp', classpath, str(file)], check=True, timeout=40)
        subprocess.run([JDK + 'java', '-cp', classpath, 'WorkspaceTransferProbe', directory], check=True, timeout=15)
    print(json.dumps({'success': True, 'realImportAndSyncGuarded': True, 'noAutomaticAcsMetadataCopies': True, 'standardAndroidDirectoryPreserved': True, 'originalFilesUnchanged': True}))


if __name__ == '__main__':
    main()
