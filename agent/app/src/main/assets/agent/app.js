import {createPage} from './components.js';
import codex from './engines/codex.js';
import openclaude from './engines/openclaude.js';
import gemini from './engines/gemini.js';
import pi from './engines/pi.js';
const profiles={codex,openclaude,gemini,pi};
const drafts=new Map();let page,holder,key='',confirmingFrame=false;
window.AsterReceive=(state,revision=0)=>{
 try {
  if(!state||!profiles[state.engine]||!state.workspace||!state.session)return false;
  const next=[state.workspace,state.engine,state.session].join(':');
  if(next!==key){
    page?.dispose();key=next;holder={value:state};
    document.body.dataset.engine=state.engine;
    const local=drafts.get(key)||{text:state.draft||'',dirty:false};drafts.set(key,local);
    while(drafts.size>32)drafts.delete(drafts.keys().next().value);
    const scope=holder;
    page=createPage(profiles[state.engine],(command,data={})=>{
      if(!window.AsterNative)return;
      const current=scope.value;
      window.AsterNative.postMessage(JSON.stringify({version:1,token:current.token,workspace:current.workspace,session:current.session,engine:current.engine,command,...data}));
    },local);
    document.getElementById('app').replaceChildren(page.root);
  }
  holder.value=state;page.update(state);
  if(!confirmingFrame && revision>0){
    confirmingFrame=true;let attempts=0;
    const confirm=()=>{
      if(window.AsterBoot?.rendered(revision) || ++attempts>=120){confirmingFrame=false;return;}
      requestAnimationFrame(confirm);
    };
    requestAnimationFrame(confirm);
  }
  return true;
 } catch(error) { window.AsterBoot?.fail(error.message||String(error)); return false; }
};
window.AsterClosePanel=()=>page?.closePanel()||false;
window.AsterBoot?.ready();
