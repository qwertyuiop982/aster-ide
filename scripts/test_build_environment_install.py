"""Verify the shared installer options and failure/cancellation behavior without apt."""
from pathlib import Path
import io
import os
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'core/app/src/main/assets/bridge'))
import bridge
import setup_android


class BuildEnvironmentInstallTests(unittest.TestCase):
    def test_bridge_can_bind_a_system_assigned_loopback_port(self):
        with bridge.Server(('127.0.0.1', 0), bridge.Handler) as server:
            self.assertGreater(server.server_address[1], 0)

    def test_package_install_uses_apt_without_touching_existing_sources(self):
        with tempfile.TemporaryDirectory() as folder:
            prefix = Path(folder)
            apt_config = prefix / 'etc/apt'
            apt_config.mkdir(parents=True)
            sources = apt_config / 'sources.list'
            sources.write_text('deb https://example.test/current stable main\n')
            parts = apt_config / 'sources.list.d'
            parts.mkdir()
            supplement = parts / 'supplement.list'
            supplement.write_text('deb https://example.test/extra extra main\n')
            expected = [
                ['/private/bin/apt-get', '-o', 'APT::Update::Error-Mode=any', 'update'],
                ['/private/bin/apt-get', '-y', '-o', 'Dpkg::Options::=--force-confold', 'upgrade'],
            ]
            for options in ({}, {'mirror': 'ustc'}, {'mirror': 'tsinghua'}):
                with self.subTest(options=options), patch.dict(os.environ, PREFIX=folder), \
                    patch.object(bridge.shutil, 'which', return_value='/private/bin/apt-get') as which, \
                    patch.object(bridge, 'stream_process', return_value=0) as streamed:
                    self.assertEqual(bridge.install('agent', io.BytesIO(), 'fixture', options), 0)
                    which.assert_called_once_with('apt-get')
                    commands = [call.args[0] for call in streamed.call_args_list]
                    self.assertEqual(commands[:2], expected)
                    self.assertEqual(commands[2][:3], ['/private/bin/apt-get', '-y', 'install'])
                    self.assertEqual(sources.read_text(), 'deb https://example.test/current stable main\n')
                    self.assertEqual(supplement.read_text(), 'deb https://example.test/extra extra main\n')
                    self.assertEqual(set(apt_config.iterdir()), {sources, parts})

    def test_embedded_android_installer_gets_only_the_apt_path(self):
        apt = '/private/runtime with spaces/bin/apt-get'
        options = setup_android.arguments(['--ndk', '0'])
        with patch.dict(os.environ, PREFIX='/private/termux/usr'), patch.object(setup_android.shutil, 'which', return_value=apt) as which:
            args = setup_android.installer_arguments(options)
        which.assert_called_once_with('apt-get')
        self.assertEqual(shlex.split(args[args.index('-p') + 1]), [apt])
        self.assertEqual(setup_android.idesetup.parse_arguments([]).package_manager, 'apt-get')

    def test_language_server_download_is_a_cancellable_subprocess(self):
        options = {'proxy': False}
        catalog = {'kotlin': {'packages': ['kotlin'], 'special': 'kotlin'}}
        with patch.dict(os.environ, PREFIX='/private/termux/usr'), patch.object(bridge, 'CATALOG', catalog), \
            patch.object(bridge.shutil, 'which', return_value='/private/bin/apt-get'), \
            patch.object(bridge, 'stream_process', side_effect=[0, 0, 130]) as streamed:
            self.assertEqual(bridge.install('kotlin', io.BytesIO(), 'download', options), 130)
        command, _, _, job, actual_options = streamed.call_args.args
        self.assertEqual(command[-3:], ['--install-special', 'kotlin', '--no-github-proxy'])
        self.assertEqual(job, 'download')
        self.assertIs(actual_options, options)

    def test_sdk_versions_path_and_cmdline_selection_reach_existing_installer(self):
        commands=[]
        options={'sdk':'/private/shared/android-sdk','sdkBuildTools':'34.0.4','sdkJdk':'21','sdkNdk':'0','sdkCmdlineTools':False,'mirror':'official'}
        with patch.dict(os.environ,PREFIX='/private/termux/usr'), patch.object(bridge.shutil,'which',side_effect=lambda name,**kwargs:'/private/bin/'+name), patch.object(bridge,'stream_process',side_effect=lambda args,*rest:commands.append(args) or 0):
            code=bridge.install('android',io.BytesIO(),'fixture',options)
        self.assertEqual(code,0)
        last=commands[-1]
        self.assertEqual(last[0],'python')
        for flag,value in [('--sdk',options['sdk']),('--build-tools','34.0.4'),('--jdk','21'),('--ndk','0')]:
            self.assertEqual(last[last.index(flag)+1],value)
        self.assertIn('--skip-cmdline-tools',last)
        self.assertTrue(any('gradle' in args and 'install' in args for args in commands))

    def test_failed_package_install_prevents_sdk_execution(self):
        commands=[]
        def failed(args,*rest):
            commands.append(args)
            return 17 if 'install' in args else 0
        with patch.dict(os.environ,PREFIX='/private/termux/usr'),patch.object(bridge.shutil,'which',return_value='/private/bin/apt'),patch.object(bridge,'stream_process',side_effect=failed):
            code=bridge.install('android',io.BytesIO(),'fixture',{'mirror':'official'})
        self.assertEqual(code,17)
        self.assertFalse(any('setup_android.py' in ' '.join(args) for args in commands))
        self.assertFalse(bridge.INSTALL_LOCK.locked())

    def test_duplicate_install_rejected_under_shared_lock(self):
        bridge.INSTALL_LOCK.acquire()
        try:
            with self.assertRaisesRegex(ValueError,'Another installation'):
                bridge.install('base',io.BytesIO(),'fixture',{})
        finally:
            bridge.INSTALL_LOCK.release()

    def test_cancelled_install_never_launches_package_command(self):
        control=bridge.ProcessJob();control.cancelled.set()
        with bridge.JOBS_LOCK:bridge.JOBS['cancelled-environment']=control
        try:
            with patch.object(bridge.subprocess,'Popen',side_effect=AssertionError('must not execute')):
                self.assertEqual(bridge.stream_process(['apt-get','install','clang'],Path('/tmp'),io.BytesIO(),'cancelled-environment'),130)
        finally:
            with bridge.JOBS_LOCK:bridge.JOBS.pop('cancelled-environment',None)

    def test_shared_build_memory_and_worker_settings_are_applied(self):
        with patch.dict(os.environ,{'PREFIX':''}):
            env=bridge.environment({'memory':1536,'workers':3,'sdk':'/nonexistent-fixture-sdk'})
        self.assertIn('-Xmx1536m',env['GRADLE_OPTS'])
        self.assertIn('workers.max=3',env['GRADLE_OPTS'])
        self.assertIn('daemon=false',env['GRADLE_OPTS'])

    def test_presets_install_only_the_named_packages_and_extras(self):
        for preset, program in (
            ('env-python', 'python-pip'), ('env-node', 'nodejs-lts'), ('env-cpp', 'clang'),
            ('env-rust', 'rust'), ('env-go', 'golang'), ('env-jvm', 'openjdk-21'), ('env-cli', 'ripgrep'),
        ):
            with self.subTest(preset=preset):
                commands = []
                options = {'mirror': 'official', 'sdkJdk': '21', 'registry': 'https://example.test/npm'}
                with patch.dict(os.environ, PREFIX='/private/termux/usr'), \
                    patch.object(bridge.shutil, 'which', side_effect=lambda name: '/private/bin/' + name), \
                    patch.object(bridge, 'stream_process', side_effect=lambda args, *rest: commands.append(args) or 0):
                    self.assertEqual(bridge.install(preset, io.BytesIO(), 'preset', options), 0)
                self.assertIn(program, commands[1])
                self.assertNotIn('upgrade', commands[1])
                if preset == 'env-node':
                    self.assertEqual(commands[-1], ['npm', 'install', '--global', '--registry', options['registry'], 'typescript'])
                else:
                    self.assertEqual(len(commands), 2)

    def test_old_sdk_paths_migrate_to_current_private_runtime(self):
        path = '/data/user/0/com.termux/files/home/android-sdk'
        with patch.dict(os.environ, ASTER_PRIVATE_FILES='/private/termux/files'):
            self.assertEqual(bridge.runtime_path(path), '/private/termux/files/home/android-sdk')
            self.assertEqual(bridge.runtime_path(path.replace('files/', 'files2/')), path.replace('files/', 'files2/'))

    def test_native_project_lookup_is_live_and_review_upload_stays_isolated(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder).resolve()
            project = base / 'real-project'
            project.mkdir()
            bindings = base / 'bindings.json'
            with patch.object(bridge, 'WORKSPACES', base / 'workspaces'), patch.object(bridge, 'WORKSPACE_BINDINGS', str(bindings)):
                review = base / 'workspaces' / 'sample'
                request = {'workspace': 'sample', 'configuration': {'permissionMode': 'native'}}
                with self.assertRaises(PermissionError):
                    bridge.workspace_for_request('agent', request)
                bindings.write_text(bridge.json.dumps({'sample': str(project)}))
                self.assertEqual(bridge.workspace_for_request('agent', request), project)
                self.assertEqual(bridge.workspace_for_request('terminal', {'workspace': 'sample'}), project)
                self.assertEqual(bridge.workspace_for_request('put', request), review)
                self.assertEqual(bridge.workspace_for_request('remove', request), review)
                self.assertEqual(bridge.workspace_for_request('agent', {'workspace': 'sample'}), review)
                self.assertEqual(bridge.workspace_for_request('agent_terminal_close', {'workspace': 'sample'}), (review, project))
                self.assertEqual(bridge.workspace_for_request('agent', {**request, 'workspace': 'agent-chat'}), base / 'workspaces' / 'agent-chat')
                project.rmdir()
                with self.assertRaises(PermissionError):
                    bridge.workspace_for_request('agent', request)

    def test_stopping_bridge_terminates_managed_child_and_rejects_new_jobs(self):
        with patch.object(bridge, 'CHILDREN', set()), patch.object(bridge, 'SHUTTING_DOWN', False):
            child = bridge.start_child([sys.executable, '-c', 'import time; time.sleep(30)'])
            try:
                bridge.stop_runtime()
                self.assertIsNotNone(child.poll())
                self.assertFalse(bridge.CHILDREN)
                with self.assertRaisesRegex(RuntimeError, 'shutting down'):
                    bridge.start_child([sys.executable, '-c', 'pass'])
            finally:
                if child.poll() is None:
                    bridge.terminate(child)


if __name__=='__main__':unittest.main()
