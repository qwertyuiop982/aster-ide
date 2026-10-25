"""Exercise Agent installer order with mocked Termux commands, without touching apt."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "core/app/src/main/assets/bridge/agent-runtime.sh"


class AgentRuntimeInstallTests(unittest.TestCase):
    def test_refreshes_libraries_before_installing_node(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            prefix = root / "prefix"
            cert = prefix / "etc/tls/cert.pem"
            cert.parent.mkdir(parents=True)
            cert.write_text("mock certificate")
            commands = root / "commands"
            apt = bin_dir / "apt-get"
            apt.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$INSTALLER_LOG"\ncase " $* " in *" install "*) touch "$PREFIX/node-ready";; esac\n')
            apt.chmod(0o755)
            node = bin_dir / "node"
            node.write_text('#!/bin/sh\n[ -f "$PREFIX/node-ready" ] && printf "v22.19.0\\n"\n')
            node.chmod(0o755)
            for name, version in (("git", "git version 2.0"), ("rg", "ripgrep 14.0"), ("openssl", "OpenSSL 3.0")):
                tool = bin_dir / name
                tool.write_text("#!/bin/sh\nprintf '%s\\n' '" + version + "'\n")
                tool.chmod(0o755)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], PREFIX=str(prefix), INSTALLER_LOG=str(commands))
            result = subprocess.run(["bash", str(SCRIPT), "--install"], env=env, text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            issued = commands.read_text().splitlines()
            self.assertEqual(len(issued), 3)
            self.assertTrue(issued[0].endswith(" update"))
            self.assertTrue(issued[1].endswith(" upgrade"))
            self.assertIn("install libc++ nodejs-lts", issued[2])
            self.assertIn("ASTER_AGENT_FIELD:ready=true", result.stdout)


if __name__ == "__main__":
    unittest.main()