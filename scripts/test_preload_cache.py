"""Cached file reads must invalidate after edits even when size and mtime match."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'core/app/src/main/assets/bridge'))
import agent_file_cache as cache
import agent_engines


class PreloadCacheTest(unittest.TestCase):
    def test_file_cache_reuses_data_and_detects_same_size_edit(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'source.txt'
            path.write_bytes(b'first')
            previous = path.stat()
            self.assertEqual(cache.content(path), b'first')
            with patch.object(Path, 'read_bytes', side_effect=AssertionError('unchanged file reread')):
                self.assertEqual(cache.content(path), b'first')
                self.assertEqual(cache.digest(path), cache.digest(path))
            path.write_bytes(b'other')
            os.utime(path, ns=(previous.st_atime_ns, previous.st_mtime_ns))
            self.assertEqual(cache.content(path), b'other')

    def test_node_readiness_is_reused_until_binary_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            binary = Path(folder) / 'node'
            binary.write_text('fixture')
            report = {'path': str(binary), 'version': 'v22.19.0', 'ready': True}
            env = {'PATH': folder}
            with patch.object(agent_engines.shutil, 'which', return_value=str(binary)), patch.object(agent_engines, '_probe_node_runtime', return_value=report) as probe:
                self.assertTrue(agent_engines.node_runtime(env)['ready'])
                self.assertTrue(agent_engines.node_runtime(env)['ready'])
                self.assertEqual(probe.call_count, 1)
                binary.write_text('fixture changed')
                self.assertTrue(agent_engines.node_runtime(env)['ready'])
                self.assertEqual(probe.call_count, 2)


if __name__ == '__main__': unittest.main()