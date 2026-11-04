"""Real Streamable HTTP MCP integration, including Aster's actual MCP client."""
from pathlib import Path
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
import urllib.error
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'core/app/src/main/assets/bridge'))
from agent_mcp import McpClient, execute_mcp

class TerminalMcpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='aster-terminal-mcp-')
        cls.root=Path(cls.temp.name)
        with socket.socket() as s:s.bind(('127.0.0.1',0));cls.port=s.getsockname()[1]
        cls.endpoint=f'http://127.0.0.1:{cls.port}/terminal-mcp'
        cls.token='aster-local-integration-only-'+'x'*40
        cls.start_server()

    @classmethod
    def start_server(cls):
        cls.log=(cls.root/'server.log').open('a')
        cls.process=subprocess.Popen([sys.executable,str(ROOT/'services/terminal-mcp/server.py')],
            env=dict(os.environ,ASTER_TERMINAL_TOKEN=cls.token,ASTER_TERMINAL_ROOT=str(cls.root/'state'),ASTER_TERMINAL_PORT=str(cls.port)),
            stdin=subprocess.DEVNULL,stdout=cls.log,stderr=subprocess.STDOUT)
        end=time.monotonic()+60
        while time.monotonic()<end:
            if cls.process.poll() is not None:raise RuntimeError((cls.root/'server.log').read_text())
            try:
                with urllib.request.urlopen(urllib.request.Request(cls.endpoint.replace('/terminal-mcp','/healthz'),headers={'Authorization':'Bearer '+cls.token}),timeout=1) as f:
                    if f.status==200:return
            except (OSError,urllib.error.URLError):time.sleep(.1)
        cls.process.terminate()
        cls.process.wait(timeout=10)
        cls.log.close()
        raise RuntimeError('MCP startup timeout: '+(cls.root/'server.log').read_text())

    @classmethod
    def stop_server(cls):
        cls.process.terminate();cls.process.wait(timeout=10);cls.log.close()

    @classmethod
    def tearDownClass(cls):
        cls.stop_server();cls.temp.cleanup()

    def client(self):
        session=SimpleNamespace(check_cancelled=lambda:None)
        client=McpClient(self.endpoint,{'Authorization':'Bearer '+self.token},session);client.initialize();return client

    def call(self,client,tool_name,conversation='integration',**arguments):
        response=client.request('tools/call',{'name':tool_name,'arguments':{'conversation_id':conversation,**arguments}})
        self.assertFalse(response.get('isError'),response)
        result=response.get('structuredContent')
        if result is None:result=json.loads(next(x['text'] for x in response['content'] if x['type']=='text'))
        self.assertTrue(result.get('ok'),result);return result

    def until(self,client,identifier,marker,conversation='integration',seconds=5):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            result=self.call(client,'terminal_read',conversation,terminal_id=identifier,wait_ms=100)
            if marker in result['output']:return result
        self.fail('Missing '+repr(marker)+' in '+result['output'])

    def test_01_authentication_and_protocol(self):
        for auth in [None,'Bearer wrong']:
            headers={} if auth is None else {'Authorization':auth}
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(urllib.request.Request(self.endpoint,data=b'{}',headers=headers),timeout=3)
            self.assertEqual(error.exception.code,401)
        client=self.client();names={tool['name'] for tool in client.request('tools/list')['tools']}
        self.assertEqual(names,{'terminal_open','terminal_exec','terminal_write','terminal_read','terminal_list','terminal_resize','terminal_close'})

    def test_02_state_history_multiple_sessions_and_reconnecting_client(self):
        client=self.client();first=self.call(client,'terminal_open',name='one')['terminal_id'];second=self.call(client,'terminal_open',name='two')['terminal_id']
        try:
            self.call(client,'terminal_exec',terminal_id=first,command="export MCP_KEEP=retained; printf 'PREVIOUS_OUTPUT\\n'")
            self.until(client,first,'\nPREVIOUS_OUTPUT\n')
            client=self.client()
            self.call(client,'terminal_exec',terminal_id=first,command="printf 'KEEP:%s\\n' \"$MCP_KEEP\"")
            result=self.until(client,first,'\nKEEP:retained\n');self.assertIn('PREVIOUS_OUTPUT',result['output'])
            self.call(client,'terminal_exec',terminal_id=second,command="printf 'SECOND:%s\\n' \"${MCP_KEEP:-empty}\"")
            self.until(client,second,'\nSECOND:empty\n')
            self.assertEqual(len([x for x in self.call(client,'terminal_list')['terminals'] if x['running']]),2)
        finally:
            for identifier in [first,second]:self.call(client,'terminal_close',terminal_id=identifier)

    def test_03_interactive_text_enter_modifiers_and_resize(self):
        client=self.client();identifier=self.call(client,'terminal_open',conversation='keyboard')['terminal_id']
        try:
            self.call(client,'terminal_exec','keyboard',terminal_id=identifier,command="read -r value; printf 'INPUT:%s\\n' \"$value\"")
            self.call(client,'terminal_write','keyboard',terminal_id=identifier,text='interactive',key='Enter')
            self.until(client,identifier,'\nINPUT:interactive\n',conversation='keyboard')
            self.call(client,'terminal_resize','keyboard',terminal_id=identifier,rows=37,columns=103)
            self.call(client,'terminal_exec','keyboard',terminal_id=identifier,command='stty size')
            self.until(client,identifier,'\n37 103\n',conversation='keyboard')
            command = """python3 -c 'import sys,tty; tty.setraw(0); data=sys.stdin.buffer.read(2); print("CHORD:"+data.hex(),flush=True)'"""
            self.call(client,'terminal_exec','keyboard',terminal_id=identifier,command=command,wait_ms=50)
            time.sleep(.15)
            self.call(client,'terminal_write','keyboard',terminal_id=identifier,key='x',modifiers=['ctrl','alt'])
            self.until(client,identifier,'CHORD:1b18',conversation='keyboard')
        finally:self.call(client,'terminal_close','keyboard',terminal_id=identifier)

    def test_04_aster_configuration_injects_conversation_scope(self):
        workspace=self.root/'aster-workspace';(workspace/'.aster').mkdir(parents=True,exist_ok=True)
        (workspace/'.aster/mcp.json').write_text(json.dumps({'mcpServers':{'terminal':{'type':'http','url':self.endpoint,'headers':{'Authorization':'Bearer '+self.token},'conversationScoped':True}}}))
        session=SimpleNamespace(workspace=workspace,engine_id='pi',session_id='aster-conversation',environment={},request={'configuration':{'mcpServers':'terminal'}},approve_external=lambda description:True,check_cancelled=lambda:None)
        raw=execute_mcp(session,{'server':'terminal','tool':'terminal_open','arguments':{'conversation_id':'model-supplied-scope'}})
        result=raw.get('structuredContent') or json.loads(raw['content'][0]['text']);self.assertTrue(result['ok'])
        import hashlib
        expected=hashlib.sha256((str(workspace)+':pi:aster-conversation').encode()).hexdigest()[:32]
        try:
            listing=self.call(self.client(),'terminal_list',expected)
            self.assertIn(result['terminal_id'],[x['terminal_id'] for x in listing['terminals']])
            self.assertIn('Bearer '+self.token,session.mcp_secret_values)
        finally:self.call(self.client(),'terminal_close',expected,terminal_id=result['terminal_id'])

    def test_05_read_wait_longer_than_old_client_network_limit(self):
        client=self.client();identifier=self.call(client,'terminal_open','wait')['terminal_id']
        try:
            self.call(client,'terminal_exec','wait',terminal_id=identifier,command="sleep 16; printf 'WAIT_FINISHED\\n'",wait_ms=0)
            result=self.call(client,'terminal_read','wait',terminal_id=identifier,wait_ms=100)
            while result['foreground_running'] is False:
                time.sleep(.02);result=self.call(client,'terminal_read','wait',terminal_id=identifier)
            result=self.call(client,'terminal_read','wait',terminal_id=identifier,cursor=result['cursor'],wait_ms=20000)
            self.assertIn('WAIT_FINISHED',result['output'])
        finally:self.call(client,'terminal_close','wait',terminal_id=identifier)

    def test_06_history_survives_service_restart_without_input_replay(self):
        client=self.client();identifier=self.call(client,'terminal_open','restart')['terminal_id']
        self.call(client,'terminal_exec','restart',terminal_id=identifier,command="printf 'ONCE\\n' >> once.txt; printf 'BEFORE_RESTART\\n'")
        self.until(client,identifier,'\nBEFORE_RESTART\n',conversation='restart')
        type(self).stop_server();type(self).start_server();client=self.client()
        result=self.call(client,'terminal_read','restart',terminal_id=identifier)
        self.assertFalse(result['running']);self.assertFalse(result['reconnectable']);self.assertIn('BEFORE_RESTART',result['output'])
        self.assertEqual((self.root/'state/workspaces/restart/once.txt').read_text(),'ONCE\n')

if __name__=='__main__':unittest.main(verbosity=2)