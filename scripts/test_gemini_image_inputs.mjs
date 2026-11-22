import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';
import {test} from 'node:test';

const engine=fileURLToPath(new URL('../core/app/src/main/assets/bridge/agent_vendor/gemini-agent.mjs',import.meta.url));
const image={mimeType:'image/jpeg',data:'/9j//9k='};
const prompt='Inspect this image.';

for(const protocol of ['openai','gemini']) test(`shipped Gemini runner sends image to ${protocol}`,async()=>{
  const workspace=await mkdtemp(join(tmpdir(),'aster-gemini-input-'));
  const seen=[];
  const server=createServer(async(req,res)=>{
    const chunks=[];
    for await(const chunk of req)chunks.push(chunk);
    if(!chunks.length){res.writeHead(404).end();return;}
    const body=JSON.parse(Buffer.concat(chunks).toString());
    seen.push({url:req.url,body});
    const usageMetadata={promptTokenCount:10,candidatesTokenCount:2,totalTokenCount:12};
    if(req.url.includes(':countTokens')){
      res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({totalTokens:10}));
    }else if(protocol==='openai'){
      res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({
        choices:[{message:{role:'assistant',content:'Image received.'},finish_reason:'stop'}],
        usage:{prompt_tokens:10,completion_tokens:2,total_tokens:12}
      }));
    }else{
      const chunk={candidates:[{index:0,content:{role:'model',parts:[{text:'Image received.'}]},finishReason:'STOP'}],usageMetadata};
      res.writeHead(200,{'Content-Type':'text/event-stream'}).end(`data: ${JSON.stringify(chunk)}\n\n`);
    }
  });
  server.listen(0,'127.0.0.1');
  await once(server,'listening');
  const endpoint=`http://127.0.0.1:${server.address().port}`+(protocol==='openai'?'/v1':'');
  const child=spawn(process.execPath,[engine],{cwd:workspace,stdio:['pipe','pipe','pipe']});
  const closed=once(child,'close');
  const timer=setTimeout(()=>child.kill(),35000);
  let diagnostics='';
  child.stderr.setEncoding('utf8').on('data',chunk=>{diagnostics=(diagnostics+chunk).slice(-4000);});
  try{
    child.stdin.write(JSON.stringify({
      type:'start',system:'Answer the user.',task:prompt,images:[image],sessionId:'e0889758-4473-469c-847c-c0c1da25a568',
      stage:workspace,workspace,sessionFile:join(workspace,'session.jsonl'),engineHome:join(workspace,'gemini-home'),
      configuration:{endpoint,protocol,model:'gemini-2.5-flash',apiKey:'fixture'},
      tools:[],allowedTools:[],nativeTools:[],nativeMcpServers:{},thinking:{level:'auto'},contextWindow:8192,maxTokens:512
    })+'\n');
    const events=[];
    for await(const line of createInterface({input:child.stdout})){
      const event=JSON.parse(line);
      events.push(event);
      if(event.type==='error'||event.type==='idle')break;
    }
    const result=events.find(e=>e.type==='result');
    assert.equal(result?.status,'Submitted',JSON.stringify(events)+'\n'+diagnostics);
    if(protocol==='openai'){
      const input=seen.find(r=>r.url.endsWith('/chat/completions')).body.messages.find(m=>Array.isArray(m.content)&&m.content.some(part=>part.type==='image_url'));
      assert.deepEqual(input.content.slice(-2),[
        {type:'text',text:prompt},{type:'image_url',image_url:{url:'data:image/jpeg;base64,/9j//9k='}},
      ]);
    }else{
      const input=seen.find(r=>r.url.includes(':streamGenerateContent')).body.contents.find(c=>c.parts.some(p=>p.inlineData));
      assert.ok(input.parts.some(part=>part.text===prompt));
      assert.deepEqual(input.parts.find(part=>part.inlineData).inlineData,image);
    }
  }finally{
    clearTimeout(timer);
    child.stdin.end();
    if(child.exitCode===null)child.kill();
    await closed;
    const stop=once(server,'close');server.close();await stop;
    await rm(workspace,{recursive:true,force:true});
  }
});
