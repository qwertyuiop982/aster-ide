"""Exercise Agent installer order with mocked Termux commands, without touching apt."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "core/app/src/main/assets/bridge/agent-runtime.sh"
START_SCRIPT = SCRIPT.with_name("start.sh")


def write_tool(path, script):
    path.write_text("#!/bin/sh\n" + script + "\n")
    path.chmod(0o755)


def fake_environment(root):
    bin_dir = root / "bin"
    bin_dir.mkdir()
    prefix = root / "prefix"
    cert = prefix / "etc/tls/cert.pem"
    cert.parent.mkdir(parents=True)
    cert.write_text("mock certificate")
    for name in ("timeout", "pkg"):
        write_tool(bin_dir / name, 'printf "unexpected command: %s\\n" "$0" >&2\nexit 99')
    env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], PREFIX=str(prefix),
               INSTALLER_LOG=str(root / "commands"), TMPDIR=str(root))
    return bin_dir, env


class AgentRuntimeInstallTests(unittest.TestCase):
    def test_refreshes_libraries_before_installing_node(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            bin_dir, env = fake_environment(root)
            write_tool(bin_dir / "apt-get", 'printf "%s\\n" "$*" >> "$INSTALLER_LOG"\ncase " $* " in *" install "*) touch "$PREFIX/node-ready";; esac')
            write_tool(bin_dir / "node", '[ -f "$PREFIX/node-ready" ] && printf "v22.19.0\\n"')
            for name, version in (("git", "git version 2.0"), ("rg", "ripgrep 14.0"), ("openssl", "OpenSSL 3.0")):
                write_tool(bin_dir / name, "printf '%s\\n' " + shlex.quote(version))
            result = subprocess.run(["bash", str(SCRIPT), "--install"], env=env, text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((root / "commands").read_text().splitlines(), [
                "-o APT::Update::Error-Mode=any update",
                "-y -o Dpkg::Options::=--force-confold upgrade",
                "-y --reinstall install libc++ nodejs-lts git ripgrep openssl openssl-tool ca-certificates",
            ])
            self.assertIn("ASTER_AGENT_FIELD:ready=true", result.stdout)

    def test_bridge_bootstrap_uses_direct_apt_without_timeouts(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            bin_dir, env = fake_environment(root)
            write_tool(bin_dir / "apt-get", 'printf "%s\\n" "$*" >> "$INSTALLER_LOG"\ncase " $* " in *" install "*) touch "$PREFIX/python-ready";; esac')
            write_tool(bin_dir / "python", 'if [ "$1" = "-c" ]; then [ -f "$PREFIX/python-ready" ]; else printf "Aster bridge ready on loopback port 8768\\n"; fi')
            result = subprocess.run(["bash", str(START_SCRIPT), folder, "8768"], env=env, text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((root / "commands").read_text().splitlines(), [
                "-o APT::Update::Error-Mode=any update",
                "-y install python",
            ])
            self.assertIn("Aster bridge ready on loopback port 8768", result.stdout)

    def test_stopping_either_installer_signals_apt_descendants(self):
        for script, expected_code in ((SCRIPT, 130), (START_SCRIPT, 143)):
            with self.subTest(script=script.name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                bin_dir, env = fake_environment(root)
                write_tool(bin_dir / "python", "exit 1")
                write_tool(bin_dir / "node", "exit 1")
                child = (
                    "from pathlib import Path\n"
                    "import os, signal\n"
                    "root = Path(os.environ['TMPDIR'])\n"
                    "def stop(*args):\n"
                    "    (root / 'stopped').touch()\n"
                    "    raise SystemExit(0)\n"
                    "signal.signal(signal.SIGTERM, stop)\n"
                    "(root / 'ready').touch()\n"
                    "signal.pause()\n"
                )
                write_tool(bin_dir / "apt-get", f"{shlex.quote(sys.executable)} -c {shlex.quote(child)} &\nwait")
                args = ["--install"] if script == SCRIPT else [folder, "8768"]
                process = subprocess.Popen(["bash", str(script), *args], env=env, text=True, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT)
                try:
                    end = time.monotonic() + 15
                    while not (root / "ready").exists() and process.poll() is None and time.monotonic() < end:
                        time.sleep(0.02)
                    self.assertTrue((root / "ready").exists(), "mock apt child never started")
                    process.terminate()
                    output, _ = process.communicate(timeout=10)
                    self.assertEqual(process.returncode, expected_code, output)
                    self.assertTrue((root / "stopped").exists(), "apt descendant did not receive SIGTERM")
                finally:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=10)
                    process.stdout.close()


if __name__ == "__main__":
    unittest.main()
