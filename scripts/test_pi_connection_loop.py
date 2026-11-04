"""Run the shipped Pi engine with tool replies whose SSE trailer aborts with errno 103."""
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from test_pi_transport import AbortStream, Session, model, aster_model
from test_free_tool_loop import TOOLS

ROOT=Path(__file__).resolve().parents[1]


class PiLoopConnectionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('node'), 'Node required for shipped Pi engine')
    def test_tool_loop_continues_after_completed_stream_connection_abort(self):
        for provider in ['lunara', 'OpenRouter', 'OpenAI']:
            with self.subTest(provider=provider), tempfile.TemporaryDirectory() as folder:
                base=Path(folder)
                (base/'one.txt').write_text('next=two.txt')
                (base/'two.txt').write_text('done')
                session=Session();session.tools=TOOLS
                adapter=model(name=provider);adapter.session=session
                observed=[];posts=[]

                def response(request,*args,**kwargs):
                    payload=json.loads(request.data)
                    posts.append(payload)
                    if len(observed)<2:
                        name='read_file';arguments={'path':['one.txt','two.txt'][len(observed)]}
                    else:
                        name='finish';arguments={'summary':'Read both files'}
                    if observed:
                        self.assertTrue(any(m['role']=='tool' for m in payload['messages']))
                    call={'index':0,'id':'call-'+str(len(posts)),'type':'function','function':{'name':name,'arguments':json.dumps(arguments)}}
                    return AbortStream([{'choices':[{'delta':{'tool_calls':[call]},'finish_reason':None}]},
                                        {'choices':[{'delta':{},'finish_reason':'tool_calls'}]}])

                process=subprocess.Popen([shutil.which('node'),str(ROOT/'core/app/src/main/assets/bridge/agent_vendor/pi-agent.mjs')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
                incoming=queue.Queue()
                def reader():
                    for line in process.stdout:incoming.put(line)
                    incoming.put('')
                threading.Thread(target=reader,daemon=True).start()
                def send(value):process.stdin.write(json.dumps(value)+'\n');process.stdin.flush()
                send({'type':'start','system':'Read the two linked files, then finish.','task':'Read one.txt and its linked file.','tools':TOOLS,'allowedTools':['read_file','finish'],'sessionId':'network-fixture','stage':folder,'workspace':folder,'sessionFile':str(base/'session.jsonl'),'model':provider,'thinkingLevel':'off','maxSteps':8,'maxTokens':2048,'contextWindow':32768})
                result=None
                try:
                    with patch.object(aster_model,'open_response',side_effect=response):
                        deadline=time.monotonic()+20
                        while time.monotonic()<deadline:
                            try:line=incoming.get(timeout=.1)
                            except queue.Empty:continue
                            self.assertTrue(line,'Engine stream closed')
                            packet=json.loads(line)
                            if packet['type']=='error':self.fail(packet['error'])
                            if packet['type']=='result':result=packet;break
                            if packet['type']!='request':continue
                            payload=packet['payload']
                            if packet['method']=='model':
                                reply=adapter.query(payload['messages'],purpose=payload.get('purpose'),tools=payload['tools'])
                            elif payload['name']=='finish':reply={'ok':True,'finished':True,'summary':payload['arguments']['summary']}
                            else:
                                path=payload['arguments']['path'];observed.append(path)
                                reply={'ok':True,'content':(base/path).read_text()}
                            send({'type':'response','id':packet['id'],'result':reply})
                    self.assertIsNotNone(result)
                    self.assertEqual(result['status'],'Submitted')
                    self.assertEqual(observed,['one.txt','two.txt'])
                    self.assertEqual(len(posts),3)
                finally:
                    process.stdin.close()
                    if process.poll() is None:process.terminate()
                    try:process.wait(3)
                    except subprocess.TimeoutExpired:process.kill();process.wait()
                    process.stdout.close();process.stderr.close()


if __name__=='__main__':unittest.main()