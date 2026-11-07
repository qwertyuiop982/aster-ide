"""Verify collaboration contracts in captured HTTP requests, without API keys."""
import unittest
from test_pi_transport import Session, server
import aster_model

class CollaborationPayloadTests(unittest.TestCase):
    def test_current_contract_reaches_http_request(self):
        cases = [('team', False, 'TEAM ROOT CONTRACT'), ('team', True, 'TEAM WORKER CONTRACT'), ('extreme', False, 'EXTREME ROOT CONTRACT'), ('extreme', True, 'EXTREME CHILD CONTRACT'), ('off', False, None)]
        for mode, child, marker in cases:
            with self.subTest(mode=mode, child=child), server() as (endpoint, requests):
                session = Session()
                session.request = {'configuration': {'collaborationMode': mode, 'subagentsUnlimited': True, 'thinkingLevel': 'ultra'}, 'isSubagent': child}
                adapter = aster_model.OpenAIModel({'endpoint': endpoint, 'model': 'test', 'stream': False}, session)
                messages = [{'role': 'system', 'content': 'original instructions'}, {'role': 'user', 'content': 'hello'}]
                adapter.query(messages)
                content = requests[0]['messages'][0]['content']
                self.assertIn('original instructions', content)
                self.assertEqual(messages[0]['content'], 'original instructions')
                if marker:
                    self.assertIn(marker, content)
                else:
                    self.assertEqual(content, 'original instructions')

if __name__ == '__main__':
    unittest.main()
