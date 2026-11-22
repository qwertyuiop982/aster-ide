"""Regression coverage for the bridge's image-only path and Codex turn retry."""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'core/app/src/main/assets/bridge'))
from agent_input_images import input_images
from agent_codex_recovery import execute_turn


class AgentInputImagesTest(unittest.TestCase):
    image = {'mimeType': 'image/jpeg', 'data': '/9j//9k='}

    def test_attachment_limits_and_unsupported_routes(self):
        for context in (
            {'engineId': 'openclaude'}, {'engineId': 'pi', 'configuration': {'freeMode': True}},
            {'compactOnly': True}, {'isSubagent': True}
        ):
            with self.assertRaises(ValueError):
                input_images({'images': [self.image], **context})
        for data in (None, 'incorrect', 'YQ==', 'A' * (512 * 1024 + 1)):
            with self.assertRaises(ValueError):
                input_images({'images': [{'mimeType': 'image/jpeg', 'data': data}]})
        with self.assertRaises(ValueError):
            input_images({'images': [self.image] * 4})
        self.assertEqual(input_images({'images': [self.image] * 3}), [self.image] * 3)
        self.assertEqual(input_images({}), [])

    def test_codex_retry_retains_direct_multimodal_parts(self):
        requests = []
        def request(method, params):
            self.assertEqual(method, 'turn/start')
            requests.append(params)
            if len(requests) == 1:
                raise RuntimeError('invalid_encrypted_content')
            return {'turn': {'id': 'new-turn'}}
        wire = SimpleNamespace(request=request, messages={}, thread_id='old', status='Submitted', native_completed=True,
                               has_interactions=lambda: False, has_active_children=lambda: False)
        state = SimpleNamespace(request={'images': [self.image]}, check_cancelled=lambda: None, redact_event=lambda val: val)
        execute_turn(wire, state, {'environments': []}, 'Please inspect this image.', None, lambda: setattr(wire, 'thread_id', 'new'))
        self.assertEqual([params['threadId'] for params in requests], ['old', 'new'])
        for params in requests:
            self.assertEqual(params['input'][1], {'type': 'image', 'url': 'data:image/jpeg;base64,/9j//9k='})
        self.assertIn('Please inspect this image.', requests[1]['input'][0]['text'])


if __name__ == '__main__':
    unittest.main()
