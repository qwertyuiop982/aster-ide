import {tr} from './locale.js';
import {syncCodexCanvas} from './appearance.js';
import {el,button,iconButton,icon} from './dom.js';

export function thinkingBubble(root,anchor,getState,request,notify) {
  const panel=el('dialog','thinking-bubble');panel.setAttribute('aria-label',tr('思考设置','Reasoning settings'));
  const bar=el('header','bubble-heading');bar.append(el('h2','',tr('思考','Reasoning')),iconButton(tr('关闭思考设置','Close reasoning settings'),'x',()=>close()));
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
    const choices=(s.thinkingChoices||[{id:'auto',label:tr('自动','Auto')}]).map(c=>s.engine==='codex'?{...c,label:c.id==='ultra'?'Ultra':c.id.toLowerCase()}:c);
    const value=el('output','thinking-value');value.hidden=true;const slider=el('input','thinking-slider');slider.type='range';slider.min='0';slider.max=String(Math.max(1,choices.length-1));slider.step='1';slider.value=String(Math.max(0,choices.findIndex(c=>c.id===(s.thinkingLevel||'auto'))));slider.disabled=choices.length<2;slider.setAttribute('aria-label',tr('思考等级','Reasoning level'));
    function preview(){const choice=choices[Number(slider.value)]||choices[0];setUltra(s.engine==='codex'&&choice.id==='ultra');value.textContent=choice.label;slider.setAttribute('aria-valuetext',choice.label);for(const tick of content.querySelectorAll('.thinking-tick'))tick.setAttribute('aria-pressed',String(tick.dataset.level===choice.id));slider.style.setProperty('--progress',(100*Number(slider.value)/Number(slider.max))+'%');}
    slider.addEventListener('input',preview);slider.addEventListener('change',()=>{if(!getState().busy)request('thinking',{id:choices[Number(slider.value)].id});});preview();
    const ticks=el('div','thinking-ticks');for(const [i,c]of choices.entries()){const tick=button(c.label,()=>{slider.value=String(i);preview();request('thinking',{id:c.id});},'thinking-tick');tick.dataset.level=c.id;tick.title=c.label;tick.setAttribute('aria-pressed',String(i===Number(slider.value)));ticks.append(tick);}
    content.append(value,slider,ticks);
    if(s.thinkingBudgetSupported){const limits=s.thinkingBudgetRange||[128,24576],group=el('details','thinking-budget'),budgetBody=el('div','thinking-budget-body'),label=el('label','',tr('固定思考预算（tokens）','Fixed reasoning budget (tokens)')),budget=el('input','budget-input');budget.type='number';budget.min=String(limits[0]);budget.max=String(limits[1]);budget.step='1';budget.value=String(s.thinkingBudget||limits[0]);budget.id='thinking-budget';label.htmlFor=budget.id;const error=el('p','budget-error');error.setAttribute('role','alert');const apply=button(tr('应用预算','Apply budget'),()=>{if(getState().busy)return;if(!budget.checkValidity()||!Number.isInteger(Number(budget.value))){error.textContent=tr('请输入 ','Enter a whole number from ')+limits[0]+'–'+limits[1]+tr(' 的整数','');return;}error.textContent='';request('thinking_budget',{value:Number(budget.value)});},'budget-apply');budgetBody.append(label,budget,el('p','small-text',limits[0]+'–'+limits[1]+tr(' · 与等级互斥',' · use instead of a reasoning level')),apply,error);group.append(el('summary','',tr('预算设置','Budget settings')),budgetBody);content.append(group);group.addEventListener('toggle',position);}
    const note=el('p','selection-note',tr('下一次任务生效','Applies to the next task'));note.title=s.thinkingNote||'';content.append(note);requestAnimationFrame(position);
  }
  window.addEventListener('resize',position);
  return {open,close,isOpen,update,dispose(){panel._canvasObserver?.disconnect();close(true);clearTimeout(finishTimer);anchor.removeEventListener('click',open);window.removeEventListener('resize',position);panel.remove();}};
}

export function subagentNavigator(root,main,viewport,composerArea,getState,refresh,request,notify) {
  let selected='',listOpen=false,lastList='',returnScroll=null,lastSelected='',parentScope='';
  let press=null,held=false,suppressClick=false,holdTimer,positionOverride=null;
  const positions=new Map();
  const dock=el('aside','agent-dock');dock.setAttribute('aria-label',tr('子代理导航','Subagent navigation'));
  const trigger=button('Agent',event=>{
    if(suppressClick&&event.detail!==0){event.preventDefault();suppressClick=false;return;}
    toggle(!listOpen);
  },'agent-bubble','layers'),count=el('span','agent-count','0');
  trigger.append(count);trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls','agent-list');
  const panel=el('section','agent-list-panel');panel.id='agent-list';panel.hidden=true;panel.setAttribute('aria-label',tr('所有子代理','All subagents'));
  const heading=el('header','bubble-heading');heading.append(el('h2','',tr('子代理','Subagents')),iconButton(tr('收起子代理','Hide subagents'),'x',()=>toggle(false)));
  const list=el('div','agent-list');panel.append(heading,list);dock.append(trigger,panel);root.append(dock);
  const banner=el('header','subagent-banner'),back=button(tr('返回主对话','Back to main conversation'),()=>select(''),'subagent-back','arrow-left-right'),title=el('div','subagent-title'),name=el('strong'),status=el('span','small-text');title.append(name,status);banner.append(back,title);banner.hidden=true;main.insertBefore(banner,viewport);
  const footer=el('div','subagent-footer');footer.append(el('p','small-text',tr('子代理任务由主代理派发，消息实时更新。','The main agent delegates tasks. Messages update live.')),button(tr('返回主对话','Back to main conversation'),()=>select(''),'subagent-return','message-square'));const stop=button(tr('停止主任务','Stop main task'),()=>request('stop'),'subagent-stop','square');footer.append(stop);footer.hidden=true;main.append(footer);
  const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
  function dragBounds(){
    const container=root.getBoundingClientRect(),area=viewport.getBoundingClientRect(),bubble=trigger.getBoundingClientRect();
    if(!container.width||!area.height||!bubble.width)return null;
    const left=area.left-container.left+10,right=Math.max(left,area.right-container.left-bubble.width-10);
    const top=area.top-container.top+8,bottom=Math.max(top,area.bottom-container.top-bubble.height-8);
    return {left,right,top,bottom,container};
  }
  function coordinates(point,bounds){
    const {top,bottom,left,right}=bounds;
    return {x:left+point.x*(right-left),y:top+point.y*(bottom-top)};
  }
  function moveBubble(x,y){
    dock.style.left=x+'px';dock.style.top=y+'px';dock.style.right='auto';
  }
  function placeList(){
    if(!listOpen)return;
    const c=root.getBoundingClientRect(),b=dock.getBoundingClientRect(),pad=10,gap=9;
    const right=b.right-c.left+gap,left=b.left-c.left-panel.offsetWidth-gap;
    const x=right+panel.offsetWidth<=c.width-pad?right:left>=pad?left:clamp(right,pad,c.width-panel.offsetWidth-pad);
    panel.style.left=(x-(b.left-c.left))+'px';
    panel.style.top=(clamp(b.top-c.top,pad,c.height-panel.offsetHeight-pad)-(b.top-c.top))+'px';
    syncCodexCanvas(root);
  }
  function placeBubble(){
    if(held)return;
    const bounds=dragBounds();if(!bounds)return;
    const saved=positionOverride||getState().bubblePosition;
    const {x,y}=saved?coordinates(saved,bounds):coordinates({x:1,y:.08},bounds);
    moveBubble(x,y);placeList();
  }
  function finishPress(event, cancelled=false){
    if(!press||event.pointerId!==press.id)return;
    clearTimeout(holdTimer);
    if(held&&press.point&&!cancelled&&press.moved){
      positionOverride=press.point;
      request('bubble_position',press.point);
    }
    press=null;held=false;delete dock.dataset.dragging;placeBubble();
  }
  trigger.addEventListener('pointerdown',event=>{
    if(event.button!==0||event.isPrimary===false||trigger.disabled||press)return;
    suppressClick=false;
    const bounds=dragBounds();if(!bounds)return;
    const b=dock.getBoundingClientRect();
    press={id:event.pointerId,x:event.clientX,y:event.clientY,dx:event.clientX-b.left,dy:event.clientY-b.top};
    trigger.setPointerCapture?.(event.pointerId);
    holdTimer=setTimeout(()=>{
      if(!press)return;
      held=true;suppressClick=true;dock.dataset.dragging='true';
      if(listOpen)toggle(false);
      request('bubble_hold');
    },450);
  });
  trigger.addEventListener('pointermove',event=>{
    if(!press||press.id!==event.pointerId)return;
    const distance=Math.hypot(event.clientX-press.x,event.clientY-press.y);
    if(!held){if(distance>10){clearTimeout(holdTimer);suppressClick=true;}return;}
    const b=dragBounds();if(!b)return;
    const x=clamp(event.clientX-press.dx-b.container.left,b.left,b.right);
    const y=clamp(event.clientY-press.dy-b.container.top,b.top,b.bottom);
    press.moved=press.moved||distance>3;
    press.point={x:(x-b.left)/(b.right-b.left||1),y:(y-b.top)/(b.bottom-b.top||1)};
    moveBubble(x,y);
  });
  trigger.addEventListener('pointerup',event=>finishPress(event));
  trigger.addEventListener('pointercancel',event=>finishPress(event,true));
  trigger.addEventListener('lostpointercapture',event=>finishPress(event,true));
  trigger.addEventListener('contextmenu',event=>event.preventDefault());
  const dockObserver=typeof ResizeObserver==='function'?new ResizeObserver(placeBubble):null;
  dockObserver?.observe(viewport);
  window.addEventListener('resize',placeBubble);
  function select(id){if(id===selected){toggle(false);return;}positions.set(selected,viewport.scrollTop);selected=id;returnScroll=positions.get(id)??null;toggle(false);refresh();if(id)back.focus();else trigger.focus();}
  function toggle(open){listOpen=open;panel.hidden=!open;trigger.setAttribute('aria-expanded',String(open));if(open){renderList(true);placeList();}notify();}
  function renderList(force=false){const rows=getState().subagents||[],key=JSON.stringify([selected,rows.map(c=>[c.id,c.label,c.model,c.status,c.running])]);if(!force&&lastList===key)return;lastList=key;const focused=document.activeElement?.dataset.child;list.replaceChildren();
    list.append(button(tr('主对话','Main conversation'),()=>select(''),'agent-row main-agent','message-square'));
    for(const child of rows){const row=button('',()=>select(child.id),'agent-row');row.dataset.child=child.id;row.setAttribute('aria-label',child.label+' · '+child.status);row.setAttribute('aria-current',String(child.id===selected));const dot=el('span','child-dot');dot.dataset.running=String(!!child.running);const detail=el('span','child-detail');detail.append(el('strong','',child.label||child.model||tr('子代理','Subagents')),el('span','small-text',child.status||tr('运行中','Running')));row.append(dot,detail,icon('chevron-right'));list.append(row);if(focused===child.id)row.focus();}
    if(!rows.length)list.append(el('p','empty-note',tr('尚未派发子代理。派发后将在这里显示。','No subagents yet.')));
  }
  function outside(e){if(listOpen&&!dock.contains(e.target))toggle(false);}
  function keydown(e){if(e.key==='Escape'&&listOpen){e.preventDefault();toggle(false);trigger.focus();}}
  document.addEventListener('pointerdown',outside);document.addEventListener('keydown',keydown);
  function update(){const s=getState(),rows=s.subagents||[],scope=JSON.stringify([s.workspace,s.engine,s.session]);
    if(parentScope!==scope){parentScope=scope;selected='';lastSelected='';lastList='';returnScroll=null;positions.clear();if(listOpen)toggle(false);}
    if(selected&&!rows.some(c=>c.id===selected)){selected='';returnScroll=positions.get('')??null;}
    const child=rows.find(c=>c.id===selected);
    count.textContent=String(rows.length);trigger.title=tr('子代理 ','Subagents ')+rows.length+tr(' · 运行中 ',' · Running ')+rows.filter(c=>c.running).length;trigger.dataset.running=String(rows.some(c=>c.running));trigger.disabled=!s.session;
    banner.hidden=!child;footer.hidden=!child;composerArea.hidden=!!child;root.dataset.childView=String(!!child);if(child){name.textContent=child.label||child.model||tr('子代理','Subagents');status.textContent=child.status||tr('运行中','Running');}stop.hidden=!s.busy;stop.disabled=!!s.stopping;
    if(listOpen)renderList();
    placeBubble();
    if(lastSelected!==selected){lastSelected=selected;viewport.scrollTop=0;if(!s.reducedMotion&&!matchMedia('(prefers-reduced-motion: reduce)').matches)viewport.animate?.([{opacity:.25},{opacity:1}],{duration:170});}
    return child;
  }
  return {update,get selected(){return selected;},get isOpen(){return listOpen;},restoreScroll(){if(returnScroll!==null){viewport.scrollTop=returnScroll;returnScroll=null;}},close(){if(listOpen){toggle(false);return true;}if(selected){select('');return true;}return false;},dispose(){clearTimeout(holdTimer);dockObserver?.disconnect();window.removeEventListener('resize',placeBubble);document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',keydown);dock.remove();banner.remove();footer.remove();}};
}
