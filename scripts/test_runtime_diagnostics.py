"""Execute the compiled Kotlin diagnostic classifier against fatal and routine lines."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
classes=ROOT/'core/app/build/tmp/kotlin-classes/debug'
stdlib=list((Path(os.environ.get('GRADLE_USER_HOME','/root/.gradle'))/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib').glob('*/*/*.jar'))
if not (classes/'dev/aster/ide/runtime/RuntimeDiagnostics.class').is_file() or not stdlib:
    raise SystemExit('Build :core:app:compileDebugKotlin before running this test')
source=r'''
import dev.aster.ide.runtime.RuntimeDiagnostics;
public class RuntimeDiagnosticsProbe {
    public static void main(String[] args) {
        String[] routine = {
            "proot warning: can't sanitize binding '/proc/example': No such file or directory",
            "/data/app/lib/arm64/libaster_proot.so warning: optional binding unavailable",
            "proot error: can't remove '/private/tmp/proot-321-abcdef': No such file or directory"
        };
        String[] ignored = {
            "WARNING: linker: Warning: failed to find generated linker configuration from \"/linkerconfig/ld.config.txt\"",
            "WARNING: linker: Warning: failed to find generated linker configuration from '/linkerconfig/ld.config.txt'"
        };
        for (String line : ignored) if (!RuntimeDiagnostics.INSTANCE.isIgnored(line)) throw new AssertionError(line);
        String[] failures = {
            "proot error: ptrace(TRACEME): Operation not permitted",
            "proot error: can't create temporary directory: Permission denied",
            "proot error: can't change loader permissions (u+rx): Permission denied",
            "proot error: execve('/bin/bash'): No such file or directory",
            "proot error: can't remove '/private/project/source.c': No such file or directory",
            "proot error: can't remove '/private/tmp/proot-321-abcdef': Permission denied",
            "compiler: proot warning: text is user output",
            "Traceback: RuntimeError: build failed",
            "WARNING: linker: Warning: failed to load shared library libmissing.so"
        };
        for (String line : routine) if (!RuntimeDiagnostics.INSTANCE.isRoutine(line)) throw new AssertionError(line);
        for (String line : failures) if (RuntimeDiagnostics.INSTANCE.isIgnored(line)) throw new AssertionError("fatal hidden: " + line);
        System.out.println("PASS: 3 routine + 2 linker diagnostics ignored; 9 fatal/child outputs preserved");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='aster-diagnostics-') as folder:
    file=Path(folder)/'RuntimeDiagnosticsProbe.java';file.write_text(source)
    classpath=os.pathsep.join([str(classes),str(stdlib[-1]),folder])
    subprocess.run(['javac','-cp',classpath,str(file)],check=True,timeout=20)
    subprocess.run(['java','-cp',classpath,'RuntimeDiagnosticsProbe'],check=True,timeout=10)