"""Verify the shared installer options and failure/cancellation behavior without apt."""
from pathlib import Path
import io
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'core/app/src/main/assets/bridge'))
import bridge


class BuildEnvironmentInstallTests(unittest.TestCase):
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


if __name__=='__main__':unittest.main()