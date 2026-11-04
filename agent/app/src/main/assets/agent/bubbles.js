import {syncCodexCanvas} from './appearance.js';
import {el,button,iconButton,icon} from './dom.js';

export function thinkingBubble(root,anchor,getState,request,notify) {
  const panel=el('dialog','thinking-bubble');panel.setAttribute('aria-label','思考设置');
  const bar=el('header','bubble-heading');bar.append(el('h2','','思考'),iconButton('关闭思考设置','x',()=>close()));
  const content=el('div','thinking-content');panel.append(bar,content);root.append(panel);
  let last='',previous,closing=false,finishTimer;
  const isOpen=()=>panel.hasAttribute('open');
  function position(){if(!isOpen())return;const a=anchor.getBoundingClientRect(),h=panel.offsetHeight;panel.style.left=Math.max(12,Math.min(innerWidth-panel.offsetWidth-12,a.right-panel.offsetWidth))+'px';const above=a.top-h-12;panel.style.top=Math.max(12,Math.min(innerHeight-h-12,above>=12?above:a.bottom+10))+'px';syncCodexCanvas(root);}
  function finish(){clearTimeout(finishTimer);closing=false;panel.removeAttribute('data-closing');if(isOpen()){if(panel.close)panel.close();else panel.removeAttribute('open');}const s=getState();panel.dataset.engine=s.engine||'';anchor.dataset.engine=s.engine||'';setUltra(s.engine==='codex'&&s.thinkingLevel==='ultra');anchor.setAttribute('aria-expanded','false');notify(false);if(previous?.isConnected)previous.focus();}
  function close(immediate=false){if(!isOpen())return false;if(immediate||getState().reducedMotion||matchMedia('(prefers-reduced-motion: reduce)').matches){finish();return true;}if(!closing){closing=true;panel.dataset.closing='true';finishTimer=setTimeout(finish,130);}return true;}
  function open(){if(isOpen()){close();return;}const s=getState();if(s.busy||!s.session)return;previous=document.activeElement;last='';update();if(panel.showModal)panel.showModal();else panel.setAttribute('open','');position();anchor.setAttribute('aria-expanded','true');notify(true);requestAnimationFrame(()=>content.querySelector('input')?.focus());}
  anchor.addEventListener('click',open);anchor.setAttribute('aria-controls','thinking-bubble');panel.id='thinking-bubble';
  if(typeof ResizeObserver==='function'){const observer=new ResizeObserver(()=>{if(isOpen())syncCodexCanvas(root);});observer.observe(panel);panel._canvasObserver=observer;}
  panel.addEventListener('cancel',e=>{e.preventDefault();close();});
  panel.addEventListener('click',e=>{if(e.target===panel){const r=panel.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)close();}});
  function setUltra(active){const value=String(!!active);panel.dataset.ultra=value;anchor.dataset.ultra=value;}
  function update(){const s=getState();panel.dataset.engine=s.engine||'';anchor.dataset.engine=s.engine||'';setUltra(s.engine==='codex'&&s.thinkingLevel==='ultra');if(s.busy||!s.session){close(true);return;}const key=JSON.stringify([s.engine,s.thinkingChoices,s.thinkingLevel,s.thinkingMode,s.thinkingBudget,s.thinkingBudgetRange,s.thinkingBudgetSupported]);if(key===last)return;last=key;content.replaceChildren();
    const choices=(s.thinkingChoices||[{id:'auto',label:'自动'}]).map(c=>s.engine==='codex'?{...c,label:c.id==='ultra'?'Ultra':c.id.toLowerCase()}:c);
    const value=el('output','thinking-value');value.hidden=true;const slider=el('input','thinking-slider');slider.type='range';slider.min='0';slider.max=String(Math.max(1,choices.length-1));slider.step='1';slider.value=String(Math.max(0,choices.findIndex(c=>c.id===(s.thinkingLevel||'auto'))));slider.disabled=choices.length<2;slider.setAttribute('aria-label','思考等级');
    function preview(){const choice=choices[Number(slider.value)]||choices[0];setUltra(s.engine==='codex'&&choice.id==='ultra');value.textContent=choice.label;slider.setAttribute('aria-valuetext',choice.label);for(const tick of content.querySelectorAll('.thinking-tick'))tick.setAttribute('aria-pressed',String(tick.dataset.level===choice.id));slider.style.setProperty('--progress',(100*Number(slider.value)/Number(slider.max))+'%');}
    slider.addEventListener('input',preview);slider.addEventListener('change',()=>{if(!getState().busy)request('thinking',{id:choices[Number(slider.value)].id});});preview();
    const ticks=el('div','thinking-ticks');for(const [i,c]of choices.entries()){const tick=button(c.label,()=>{slider.value=String(i);preview();request('thinking',{id:c.id});},'thinking-tick');tick.dataset.level=c.id;tick.title=c.label;tick.setAttribute('aria-pressed',String(i===Number(slider.value)));ticks.append(tick);}
    content.append(value,slider,ticks);
    if(s.thinkingBudgetSupported){const limits=s.thinkingBudgetRange||[128,24576],group=el('details','thinking-budget'),budgetBody=el('div','thinking-budget-body'),label=el('label','','固定思考预算（tokens）'),budget=el('input','budget-input');budget.type='number';budget.min=String(limits[0]);budget.max=String(limits[1]);budget.step='1';budget.value=String(s.thinkingBudget||limits[0]);budget.id='thinking-budget';label.htmlFor=budget.id;const error=el('p','budget-error');error.setAttribute('role','alert');const apply=button('应用预算',()=>{if(getState().busy)return;if(!budget.checkValidity()||!Number.isInteger(Number(budget.value))){error.textContent='请输入 '+limits[0]+'–'+limits[1]+' 的整数';return;}error.textContent='';request('thinking_budget',{value:Number(budget.value)});},'budget-apply');budgetBody.append(label,budget,el('p','small-text',limits[0]+'–'+limits[1]+' · 与等级互斥'),apply,error);group.append(el('summary','','预算设置'),budgetBody);content.append(group);group.addEventListener('toggle',position);}
    const note=el('p','selection-note','下一次任务生效');note.title=s.thinkingNote||'';content.append(note);requestAnimationFrame(position);
  }
  window.addEventListener('resize',position);
  return {open,close,isOpen,update,dispose(){panel._canvasObserver?.disconnect();close(true);clearTimeout(finishTimer);anchor.removeEventListener('click',open);window.removeEventListener('resize',position);panel.remove();}};
}

export function subagentNavigator(root,main,viewport,composerArea,getState,refresh,request,notify) {
  let selected='',listOpen=false,lastList='',mainScroll=0,returnScroll=null,lastSelected='';
  const dock=el('aside','agent-dock');dock.setAttribute('aria-label','子代理导航');
  const trigger=button('Agent',()=>toggle(!listOpen),'agent-bubble','layers'),count=el('span','agent-count','0');trigger.append(count);trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls','agent-list');
  const panel=el('section','agent-list-panel');panel.id='agent-list';panel.hidden=true;panel.setAttribute('aria-label','所有子代理');
  const heading=el('header','bubble-heading');heading.append(el('h2','','子代理'),iconButton('收起子代理','x',()=>toggle(false)));
  const list=el('div','agent-list');panel.append(heading,list);dock.append(trigger,panel);root.append(dock);
  const banner=el('header','subagent-banner'),back=button('返回主对话',()=>select(''),'subagent-back','arrow-left-right'),title=el('div','subagent-title'),name=el('strong'),status=el('span','small-text');title.append(name,status);banner.append(back,title);banner.hidden=true;main.insertBefore(banner,viewport);
  const footer=el('div','subagent-footer');footer.append(el('p','small-text','子代理任务由主代理派发，消息实时更新。'),button('返回主对话',()=>select(''),'subagent-return','message-square'));const stop=button('停止主任务',()=>request('stop'),'subagent-stop','square');footer.append(stop);footer.hidden=true;main.append(footer);
  function select(id){if(id===selected)return;if(!selected)mainScroll=viewport.scrollTop;selected=id;returnScroll=id?null:mainScroll;toggle(false);refresh();if(id)back.focus();else trigger.focus();}
  function toggle(open){listOpen=open;panel.hidden=!open;if(open)requestAnimationFrame(()=>syncCodexCanvas(root));trigger.setAttribute('aria-expanded',String(open));if(open)renderList(true);notify();}
  function renderList(force=false){const rows=getState().subagents||[],key=JSON.stringify(rows.map(c=>[c.id,c.label,c.status,c.running,c.messages?.length]));if(!force&&lastList===key)return;lastList=key;const focused=document.activeElement?.dataset.child;list.replaceChildren();
    list.append(button('主对话',()=>select(''),'agent-row main-agent','message-square'));
    for(const child of rows){const row=button('',()=>select(child.id),'agent-row');row.dataset.child=child.id;row.setAttribute('aria-label',child.label+' · '+child.status);row.setAttribute('aria-current',String(child.id===selected));const dot=el('span','child-dot');dot.dataset.running=String(!!child.running);const detail=el('span','child-detail');detail.append(el('strong','',child.label||child.model||'子代理'),el('span','small-text',child.status||'运行中'));row.append(dot,detail,icon('chevron-right'));list.append(row);if(focused===child.id)row.focus();}
    if(!rows.length)list.append(el('p','empty-note','尚未派发子代理。派发后将在这里显示。'));
  }
  function outside(e){if(listOpen&&!dock.contains(e.target))toggle(false);}
  function keydown(e){if(e.key==='Escape'&&listOpen){e.preventDefault();toggle(false);trigger.focus();}}
  document.addEventListener('pointerdown',outside);document.addEventListener('keydown',keydown);
  function update(){const s=getState(),rows=s.subagents||[];if(selected&&!rows.some(c=>c.id===selected))selected='';const child=rows.find(c=>c.id===selected);
    count.textContent=String(rows.length);trigger.title='子代理 '+rows.length+' · 运行中 '+rows.filter(c=>c.running).length;trigger.dataset.running=String(rows.some(c=>c.running));trigger.disabled=!s.session;
    banner.hidden=!child;footer.hidden=!child;composerArea.hidden=!!child;root.dataset.childView=String(!!child);if(child){name.textContent=child.label||child.model||'子代理';status.textContent=child.status||'运行中';}stop.hidden=!s.busy;stop.disabled=!!s.stopping;
    if(listOpen)renderList();
    if(lastSelected!==selected){lastSelected=selected;viewport.scrollTop=0;viewport.animate?.([{opacity:.4,transform:'translateY(5px)'},{opacity:1,transform:'none'}],{duration:s.reducedMotion||matchMedia('(prefers-reduced-motion: reduce)').matches?0:160});}
    return child;
  }
  return {update,get selected(){return selected;},get isOpen(){return listOpen;},restoreScroll(){if(returnScroll!==null){viewport.scrollTop=returnScroll;returnScroll=null;}},close(){if(listOpen){toggle(false);return true;}if(selected){select('');return true;}return false;},dispose(){document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',keydown);dock.remove();banner.remove();footer.remove();}};
}
