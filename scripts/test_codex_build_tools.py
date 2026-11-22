import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from codex_build_tools import ANDROID_TARGET, cargo_command, cargo_environment, cargo_registry_args, open_project_lock
from prepare_native import codex_source_inputs


class CodexBuildToolsTests(unittest.TestCase):
    def test_default_cargo_command_preserves_rustup_pin(self):
        self.assertEqual(cargo_command(Path('/bin/cargo'), {}), ['/bin/cargo', '+1.95.0'])

    def test_system_cargo_on_android_uses_host_protoc(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'libstd-0123.abcdef.rlib').touch()
            env = {'PATH': '/termux/bin', 'ASTER_CODEX_RUST_TOOLCHAIN': 'system'}
            with patch('codex_build_tools.shutil.which', side_effect=lambda name, path: f'{path}/{name}'), \
                 patch('codex_build_tools.subprocess.check_output',
                       side_effect=[directory + '\n', f'rustc 1.100.0\nhost: {ANDROID_TARGET}\n']):
                self.assertEqual(cargo_command(Path('/termux/bin/cargo'), env), ['/termux/bin/cargo'])
            self.assertEqual(env['PROTOC'], '/termux/bin/protoc')

    def test_system_cargo_requires_android_target_std(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {'PATH': '/termux/bin', 'ASTER_CODEX_RUST_TOOLCHAIN': 'system'}
            with patch('codex_build_tools.shutil.which', return_value='/termux/bin/rustc'), \
                 patch('codex_build_tools.subprocess.check_output', return_value=directory):
                with self.assertRaisesRegex(SystemExit, f'missing the standard library for {ANDROID_TARGET}'):
                    cargo_command(Path('/termux/bin/cargo'), env)

    def test_system_cargo_respects_explicit_protoc(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'libstd-0123.abcdef.rlib').touch()
            env = {'PATH': '/termux/bin', 'ASTER_CODEX_RUST_TOOLCHAIN': 'system', 'PROTOC': '/custom/protoc'}
            with patch('codex_build_tools.shutil.which', return_value='/termux/bin/rustc') as which, \
                 patch('codex_build_tools.subprocess.check_output',
                       side_effect=[directory, f'rustc 1.100.0\nhost: {ANDROID_TARGET}\n']):
                cargo_command(Path('/termux/bin/cargo'), env)
            which.assert_called_once_with('rustc', path='/termux/bin')
            self.assertEqual(env['PROTOC'], '/custom/protoc')

    def test_git_proxy_keeps_inherited_config(self):
        inherited = {'PATH': '/bin', 'GIT_CONFIG_COUNT': '1',
                     'GIT_CONFIG_KEY_0': 'http.version', 'GIT_CONFIG_VALUE_0': 'HTTP/1.1'}
        args = (Path('/cargo/bin/cargo'), Path('/ndk/bin'), '/cargo', '/rustup', Path('/target'))
        with patch.dict(os.environ, inherited, clear=True):
            env = cargo_environment(*args)
        self.assertEqual(env['PATH'], os.pathsep.join(('/ndk/bin', '/cargo/bin', '/bin')))
        self.assertEqual(env['GIT_CONFIG_COUNT'], '3')
        self.assertEqual(env['GIT_CONFIG_KEY_0'], 'http.version')
        self.assertEqual(env['GIT_CONFIG_VALUE_1'], 'https://github.com/')
        self.assertEqual(env['GIT_CONFIG_VALUE_2'], 'ssh://git@github.com/')
        with patch.dict(os.environ, dict(inherited, ASTER_CODEX_GITHUB_PROXY='direct'), clear=True):
            self.assertEqual(cargo_environment(*args)['GIT_CONFIG_COUNT'], '1')

    def test_registry_is_configurable_without_changing_lock(self):
        self.assertIn('source.rsproxy.registry="sparse+https://rsproxy.cn/index/"', cargo_registry_args({}))
        self.assertEqual(cargo_registry_args({'ASTER_CODEX_CRATES_REGISTRY': 'crates-io'}), [])
        self.assertIn('source.rsproxy.registry="sparse+https://example.org/index/"',
                      cargo_registry_args({'ASTER_CODEX_CRATES_REGISTRY': 'sparse+https://example.org/index/'}))

    def test_native_inputs_include_protocols_and_embedded_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            inputs = ('pkg/src/lib.rs', 'pkg/src/wire.proto', 'pkg/templates/config.json', 'pkg/help.md')
            for name in (*inputs, '.git/index', 'target/old.rs', 'pkg/target/old.json'):
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            self.assertEqual({p.relative_to(source).as_posix() for p in codex_source_inputs(source)}, set(inputs))

    def test_project_lock_uses_private_temp_storage_and_excludes_concurrent_builds(self):
        with tempfile.TemporaryDirectory() as workspace, tempfile.TemporaryDirectory() as temp:
            with patch.dict(os.environ, {'TMPDIR': temp}):
                first = open_project_lock(workspace, 'native.lock')
                try:
                    self.assertTrue(str(Path(first.name).is_relative_to(temp)))
                    with self.assertRaises(BlockingIOError):
                        open_project_lock(workspace, 'native.lock', nonblocking=True)
                finally:
                    first.close()


if __name__ == '__main__':
    unittest.main()
