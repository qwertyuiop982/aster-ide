"""Gemini manual thinking overrides, without provider requests or credentials."""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'core/app/src/main/assets/bridge'))
from agent_thinking import resolve_thinking


class GeminiManualThinkingTest(unittest.TestCase):
    def configuration(self, **overrides):
        return {
            'model': 'private-model-alias', 'protocol': 'gemini',
            'maxTokens': 4096, 'thinkingMode': 'manual', 'thinkingLevel': 'high',
            'thinkingBudget': 1024, **overrides,
        }

    def test_native_alias_sends_level_without_a_budget(self):
        self.assertEqual(resolve_thinking('gemini', self.configuration()), {
            'mode': 'manual', 'level': 'high', 'gemini': {'thinkingLevel': 'HIGH'},
        })

    def test_openai_alias_preserves_effort_without_native_parameters(self):
        self.assertEqual(resolve_thinking('gemini', self.configuration(
            protocol='openai', thinkingLevel='Provider_Level.v2-high',
        )), {
            'mode': 'manual', 'level': 'Provider_Level.v2-high',
            'effort': 'Provider_Level.v2-high',
        })

    def test_native_custom_level_is_uppercased_only_in_provider_parameter(self):
        self.assertEqual(resolve_thinking('gemini', self.configuration(
            thinkingLevel='Provider_Level.v2-high',
        )), {
            'mode': 'manual', 'level': 'Provider_Level.v2-high',
            'gemini': {'thinkingLevel': 'PROVIDER_LEVEL.V2-HIGH'},
        })

    def test_manual_override_does_not_infer_a_budget_for_gemini_25(self):
        self.assertEqual(resolve_thinking('gemini', self.configuration(
            model='gemini-2.5-pro',
        )), {
            'mode': 'manual', 'level': 'high', 'gemini': {'thinkingLevel': 'HIGH'},
        })

    def test_manual_level_length_boundaries(self):
        for level in ('a', 'a' * 64):
            with self.subTest(level=level):
                self.assertEqual(resolve_thinking('gemini', self.configuration(
                    thinkingLevel=level,
                ))['gemini'], {'thinkingLevel': level.upper()})

    def test_invalid_manual_levels_are_rejected_for_both_protocols(self):
        for protocol in ('gemini', 'openai'):
            for level in ('', 'a' * 65, '1high', ' high', 'high ', 'high\n',
                          'high,low', 'high/low', '高', None, True, 1, ['high'], {}):
                with self.subTest(protocol=protocol, level=level):
                    with self.assertRaisesRegex(ValueError, 'Invalid manual Gemini thinking level'):
                        resolve_thinking('gemini', self.configuration(
                            protocol=protocol, thinkingLevel=level,
                        ))

    def test_manual_mode_is_rejected_by_other_engines(self):
        for engine in ('pi', 'codex', 'openclaude'):
            with self.subTest(engine=engine):
                with self.assertRaisesRegex(ValueError, 'Invalid manual Gemini thinking level'):
                    resolve_thinking(engine, self.configuration())

    def test_automatic_alias_keeps_engine_default(self):
        self.assertEqual(resolve_thinking('gemini', self.configuration(
            thinkingMode='auto',
        )), {'mode': 'auto', 'level': 'auto'})

    def test_builtin_strength_does_not_apply_manual_alias_override(self):
        with self.assertRaisesRegex(ValueError, 'does not support selected thinking strength'):
            resolve_thinking('gemini', self.configuration(thinkingMode='strength'))

    def test_builtin_gemini_25_budget_remains_mutually_exclusive(self):
        self.assertEqual(resolve_thinking('gemini', self.configuration(
            model='gemini-2.5-pro', thinkingMode='budget', thinkingBudget=1024,
        )), {
            'mode': 'budget', 'level': 'auto', 'budgetTokens': 1024,
            'gemini': {'thinkingBudget': 1024},
        })


if __name__ == '__main__':
    unittest.main()
