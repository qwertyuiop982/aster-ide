"""Native tool events may contain diagnostic lines inside nested JSON strings."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'core/app/src/main/assets/bridge/agent_vendor'))
from aster_diagnostics import clean_runtime_output, strip_runtime_diagnostics

WARNING = 'WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"'


class NativeDiagnosticsTest(unittest.TestCase):
    def test_nested_native_json_preserves_exit_status(self):
        value = {'text': json.dumps({'item': {'aggregatedOutput': WARNING + '\nhello\n'}, 'exitCode': 9})}
        cleaned = json.loads(clean_runtime_output(value)['text'])
        self.assertEqual(cleaned['item']['aggregatedOutput'], 'hello\n')
        self.assertEqual(cleaned['exitCode'], 9)

    def test_colored_routine_diagnostics_and_expired_temp_cleanup_are_removed(self):
        lines = [
            'proot warning: optional binding unavailable',
            '/data/app/lib/arm64/libaster_proot.so info: tracing',
            "proot error: can't remove '/private/tmp/proot-321-abcdef': No such file or directory",
            '\x1b[33mproot warning: optional binding\x1b[0m',
            '\x1b[33m' + WARNING + '\x1b[0m',
        ]
        for line in lines:
            self.assertEqual(strip_runtime_diagnostics(line + '\nready'), 'ready')

    def test_fatal_errors_and_child_output_remain_visible(self):
        lines = [
            'proot error: ptrace(TRACEME): Operation not permitted',
            "proot error: can't remove '/private/project/source.c': No such file or directory",
            "proot error: can't remove '/private/tmp/proot-1-a': Permission denied",
            'WARNING: linker: Warning: failed to load shared library libmissing.so',
            'compiler: proot warning: ordinary command output',
        ]
        for line in lines:
            self.assertEqual(strip_runtime_diagnostics(line), line)


if __name__ == '__main__': unittest.main()