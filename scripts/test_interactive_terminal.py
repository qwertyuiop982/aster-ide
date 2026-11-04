"""Real PTY and approved command regressions; optional wall-clock >120 s check."""
from pathlib import Path
import os
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'core/app/src/main/assets/bridge'), str(ROOT/'core/app/src/main/assets/bridge/agent_vendor')]
from agent_runner import AgentSession
from aster_tools import ProjectEnvironment
from aster_terminal import TerminalRegistry, encode_input, session_scope
from aster_diagnostics import LinkerWarningFilter, strip_linker_warnings

WARNING = 'WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"'

class TerminalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='aster-terminal-test-')
        self.root = Path(self.temp.name)
        self.registry = TerminalRegistry()
        self.patch = patch('aster_terminal.TERMINALS', self.registry); self.patch.start()
        self.approved = True
        self.session = self.make_session()

    def make_session(self, conversation='conversation1', engine='pi'):
        workspace = self.root/'workspace'; workspace.mkdir(exist_ok=True)
        home = self.root/'home';home.mkdir(exist_ok=True)
        holder = {}
        def send(event):
            if event.get('kind') == 'approval':
                holder['session'].decide(event['requestId'], self.approved)
        session = AgentSession('turn' + str(time.time_ns()), workspace, self.root/'base',
            {'engineId':engine, 'sessionId':conversation, 'mode':'execute'},
            {**os.environ, 'HOME':str(home)}, send)
        holder['session'] = session
        session.stage.mkdir(parents=True, exist_ok=True)
        return session

    def tearDown(self):
        self.registry.close_all(); self.patch.stop(); self.temp.cleanup()

    def call(self, action, terminal_id=None, session=None, **args):
        return self.registry.execute(session or self.session, {'action':action, **({'terminal_id':terminal_id} if terminal_id else {}), **args})

    def opened(self, **args):
        result = self.call('open', **args)
        self.assertTrue(result['ok']);return result['terminal_id']

    def until(self, terminal_id, marker, session=None, seconds=6):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            result = self.call('read', terminal_id, session=session, wait_ms=100)
            if marker in result['output'].replace('\r',''):return result
        self.fail('terminal output missing '+repr(marker)+': '+result['output'])

    def test_real_controlling_tty_and_prompt_input(self):
        identifier = self.opened()
        self.call('exec', identifier, command="test -t 0 && test -t 1 && printf 'TTY_OK\\n'; read -r answer; printf 'ANSWER:%s\\n' \"$answer\"", wait_ms=20)
        self.until(identifier, '\nTTY_OK\n')
        self.call('write', identifier, text='hello', key='Enter')
        self.until(identifier, '\nANSWER:hello\n')

    def test_shell_state_and_history_survive_new_agent_turn(self):
        identifier = self.opened()
        self.call('exec', identifier, command="export ASTER_KEEP=retained; mkdir -p sub; cd sub; printf 'FIRST_DONE\\n'")
        self.until(identifier, '\nFIRST_DONE\n')
        next_turn = self.make_session()
        self.call('exec', identifier, session=next_turn, command="printf '%s %s\\n' \"$ASTER_KEEP\" \"${PWD##*/}\"")
        result = self.until(identifier, '\nretained sub\n', session=next_turn)
        self.assertIn('FIRST_DONE', result['output']);self.assertTrue(result['history_included'])

    def test_multiple_terminals_have_independent_shell_variables(self):
        first, second = self.opened(name='one'), self.opened(name='two')
        self.call('exec', first, command="export ASTER_VALUE=one; printf 'ONE_READY\\n'")
        self.until(first, '\nONE_READY\n')
        self.call('exec', second, command="printf 'VALUE:%s\\n' \"${ASTER_VALUE:-empty}\"")
        self.until(second, '\nVALUE:empty\n')
        self.assertEqual(len(self.call('list')['terminals']), 2)

    def test_other_conversation_cannot_read_or_write_terminal(self):
        identifier = self.opened()
        other = self.make_session(conversation='other')
        for action in ['read','write','close']:
            with self.assertRaises(ValueError):self.call(action, identifier, session=other, text='echo BAD', key='enter')

    def test_wait_elapsed_does_not_kill_long_command(self):
        identifier = self.opened()
        began = time.monotonic()
        self.call('exec', identifier, command="sleep 1; printf 'ASYNC_DONE\\n'", wait_ms=10)
        self.assertLess(time.monotonic()-began, .8)
        result = self.until(identifier, '\nASYNC_DONE\n')
        self.assertTrue(result['running'])

    def test_ctrl_c_interrupts_foreground_and_preserves_shell(self):
        identifier = self.opened()
        self.call('exec', identifier, command='sleep 60', wait_ms=0)
        item = self.registry.items[(session_scope(self.session), identifier)]
        end=time.monotonic()+3
        while item.foreground() in (None,item.process.pid) and time.monotonic()<end:time.sleep(.02)
        self.call('write', identifier, key='c', modifiers=['ctrl'])
        self.call('exec', identifier, command="printf 'AFTER_INTERRUPT\\n'")
        self.until(identifier, '\nAFTER_INTERRUPT\n')

    def test_resize_reaches_real_terminal(self):
        identifier = self.opened()
        self.call('resize', identifier, rows=41, columns=111)
        self.call('exec', identifier, command='stty size')
        self.until(identifier, '\n41 111\n')

    def test_history_remains_readable_after_registry_restart(self):
        identifier = self.opened()
        self.call('exec', identifier, command="printf 'PERSISTED_HISTORY\\n'")
        self.until(identifier, '\nPERSISTED_HISTORY\n')
        self.call('close', identifier)
        replacement = TerminalRegistry()
        result = replacement.execute(self.session, {'action':'read','terminal_id':identifier})
        self.assertIn('PERSISTED_HISTORY',result['output']);self.assertFalse(result['reconnectable'])
        with self.assertRaises(ValueError):replacement.execute(self.session, {'action':'write','terminal_id':identifier,'text':'bad'})

    def test_open_and_input_require_approval(self):
        self.approved=False
        self.assertFalse(self.call('open')['ok']);self.assertEqual(self.registry.items,{})
        self.approved=True;identifier=self.opened();self.approved=False
        self.assertFalse(self.call('exec',identifier,command="printf 'REJECTED\\n'")['ok'])
        self.assertNotIn('REJECTED',self.call('read',identifier)['output'])

    def test_read_only_modes_do_not_expose_interactive_tool(self):
        self.session.allowed_tools.discard('terminal')
        result=ProjectEnvironment(self.session).execute({'name':'terminal','arguments':{'action':'open'}})
        self.assertFalse(result['ok']);self.assertEqual(self.registry.items,{})

    def test_command_timeout_not_silently_clamped_and_out_of_range_rejected(self):
        with patch.object(self.session,'run_command',return_value={'ok':True}) as command:
            ProjectEnvironment(self.session).execute({'name':'run_command','arguments':{'command':'sleep 125','timeout':300}})
            command.assert_called_once_with('sleep 125',300)
        with self.assertRaises(ValueError):self.session.run_command('true',3601)

    def test_actual_one_second_timeout_and_runtime_budget_extension(self):
        self.session.deadline=time.monotonic()+.2
        began=time.monotonic();result=self.session.run_command('sleep 5',1)
        self.assertFalse(result['ok']);self.assertIn('1 秒',result['error'])
        self.assertGreaterEqual(time.monotonic()-began,.9);self.assertLess(time.monotonic()-began,3)

    def test_known_linker_warning_removed_and_real_linker_errors_kept(self):
        fatal='WARNING: linker: Warning: failed to load shared library libmissing.so'
        for size in range(1,len(WARNING)+3):
            filter_output=LinkerWarningFilter();data=(WARNING+'\r\n'+fatal+'\naster$ ').encode()
            actual=b''.join(filter_output.feed(data[i:i+size]) for i in range(0,len(data),size))+filter_output.feed(final=True)
            self.assertEqual(actual.decode(),fatal+'\naster$ ')
        self.assertEqual(strip_linker_warnings(WARNING),'')
        self.assertEqual(LinkerWarningFilter().feed(b'aster$ '),b'aster$ ')

    def test_key_combinations(self):
        self.assertEqual(encode_input(key='c',modifiers=['ctrl']),b'\x03')
        self.assertEqual(encode_input(key='x',modifiers=['ctrl','alt']),b'\x1b\x18')
        self.assertEqual(encode_input(key='left',modifiers=['ctrl']),b'\x1b[1;5D')
        self.assertEqual(encode_input(key='Tab',modifiers=['shift']),b'\x1b[Z')
        self.assertEqual(encode_input(text='你好',key='Enter'),'你好\r'.encode())

    @unittest.skipUnless(os.environ.get('ASTER_LONG_TERMINAL_TEST')=='1','set ASTER_LONG_TERMINAL_TEST=1 for wall-clock regression')
    def test_actual_300_second_limit_survives_past_two_minutes(self):
        began=time.monotonic()
        result=ProjectEnvironment(self.session).execute({'name':'run_command','arguments':{'command':"sleep 125; printf 'SURVIVED_120_SECONDS\\n'",'timeout':300}})
        self.assertTrue(result['ok'],result);self.assertEqual(result['timeout'],300)
        self.assertIn('SURVIVED_120_SECONDS',result['output']);self.assertGreaterEqual(time.monotonic()-began,124)

if __name__=='__main__':unittest.main(verbosity=2)
