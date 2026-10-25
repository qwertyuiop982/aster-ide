import {el,icon,button,iconButton,section,richText} from './dom.js';
import {choiceList} from './choices.js';
import {engineArtwork} from './patterns.js';

/** Shared real-state controls; each engine owns its layout profile. */
export function createPage(profile,send,local) {
  let state={},draftTimer,follow=true,lastMessages='',lastHistory='',lastTasks='',lastChanges='',lastFiles='',lastAck,lastPrompt;
  let drawerMode='',previousFocus=null,infoTab='overview',navPinned=profile.navigation,infoPinned=profile.inspector;
  const wide=matchMedia('(min-width: 1080px) and (min-height: 520px) and (orientation: landscape)');
  const dual=matchMedia('(min-width: 1360px) and (min-height: 520px) and (orientation: landscape)');
  const root=el('main','agent-layout '+profile.id);root.dataset.empty='true';
  const sidebarHost=el('aside','sidebar-host'),main=el('section','main-column'),infoHost=el('aside','info-host');
  const modal=el('dialog','utility-dialog');modal.setAttribute('aria-label','会话面板');
  const modalBar=el('header','dialog-bar'),modalTitle=el('h2');
  modalBar.append(modalTitle,iconButton('关闭面板','x',closePanel));
  const modalBody=el('div','dialog-body');modal.append(modalBar,modalBody);
  const shade=el('div','dialog-shade');shade.hidden=true;shade.addEventListener('click',closePanel);
  const rail=el('nav','navigation-rail');rail.setAttribute('aria-label','Agent 导航');
  const railBrand=el('div','rail-brand');railBrand.append(icon(profile.mark));
  rail.append(railBrand,iconButton('会话历史','history',toggleHistory),iconButton('新会话','plus',()=>request('new')),
    iconButton('工作信息','panel-right',()=>showInfo()),iconButton('上下文文件','paperclip',()=>nativeRequest('context_files')),
    el('span','rail-spacer'),iconButton('模型设置','settings-2',()=>nativeRequest('settings')));
  root.append(rail,sidebarHost,main,infoHost,shade,modal);
  const request=(command,data={})=>{if(command!=='draft')flushDraft();send(command,data);};
  function nativeRequest(command,data={}) { closePanel();request(command,data); }
  function saveDraft(){local.text=input.value;local.dirty=true;clearTimeout(draftTimer);draftTimer=setTimeout(flushDraft,250);}
  function flushDraft(){clearTimeout(draftTimer);if(local.dirty){send('draft',{text:local.text||''});local.dirty=false;}}
  const panelIsOpen=()=>modal.hasAttribute('open');
  const nativeDialog=typeof modal.showModal==='function'&&typeof modal.close==='function';
  if(!nativeDialog){modal.dataset.fallback='true';modal.setAttribute('role','dialog');modal.setAttribute('aria-modal','true');}
  function closePanel(){if(panelIsOpen()){shade.hidden=true;if(nativeDialog)modal.close();else {modal.removeAttribute('open');modal.dispatchEvent(new Event('close'));}}}
  function panelChanged(){
    send('panel',{id:panelIsOpen()?'open':''});
    for(const node of root.querySelectorAll('[aria-haspopup=dialog]'))node.setAttribute('aria-expanded',String(panelIsOpen()&&node.dataset.picker===drawerMode));
  }
  modal.addEventListener('close',()=>{if(panelIsOpen())return;drawerMode='';restorePanels();panelChanged();if(previousFocus?.isConnected)previousFocus.focus();});
  modal.addEventListener('keydown',event=>{
    if(event.key==='Escape'){event.preventDefault();closePanel();return;}
    if(event.key!=='Tab')return;
    const nodes=Array.from(modal.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[tabindex="0"]')).filter(node=>node.tabIndex>=0&&node.getClientRects().length);
    const first=nodes[0],last=nodes[nodes.length-1];
    if(!nodes.includes(document.activeElement)){event.preventDefault();(event.shiftKey?last:first)?.focus();return;}
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
    else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
  });
  modal.addEventListener('click',e=>{if(e.target===modal){const r=modal.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)closePanel();}});
  function openPanel(kind){
    const wasOpen=panelIsOpen();if(!wasOpen)previousFocus=document.activeElement;drawerMode=kind;
    modal.dataset.kind=kind;
    modalTitle.textContent=({history:'会话与工作区',menu:'会话选项',info:'工作信息',engine:'底层引擎',mode:'操作权限',thinking:'思考等级'})[kind];
    modalBody.replaceChildren(kind==='history'?sidebar:kind==='menu'?menu:kind==='info'?inspector:selection(kind));
    if(!wasOpen){if(nativeDialog)modal.showModal();else {shade.hidden=false;modal.setAttribute('open','');}}
    panelChanged();requestAnimationFrame(()=>{if(panelIsOpen())(modalBody.querySelector('[aria-selected=true]:not(:disabled)')||modalBody.querySelector('button:not(:disabled)')||modal.querySelector('button'))?.focus();});
  }
  function restorePanels(){
    if(drawerMode!=='history')sidebarHost.append(sidebar);
    if(drawerMode!=='info')infoHost.append(inspector);
    const navigation=wide.matches&&navPinned&&(!infoPinned||dual.matches);
    root.dataset.navigation=String(navigation);root.dataset.inspector=String(wide.matches&&infoPinned);
    sidebarHost.hidden=!navigation;infoHost.hidden=!(wide.matches&&infoPinned);
    menuButton?.setAttribute('aria-expanded',String(navigation||drawerMode==='history'));
  }
  function toggleHistory(){if(wide.matches){navPinned=sidebarHost.hidden;if(navPinned&&!dual.matches)infoPinned=false;restorePanels();}else openPanel('history');}
  function showInfo(tab='overview'){
    infoTab=tab;renderTabs();
    if(wide.matches){closePanel();drawerMode='';infoPinned=true;restorePanels();}else openPanel('info');
  }
  const header=el('header','topbar');
  const menuButton=iconButton('打开会话历史','menu',toggleHistory);
  function picker(label,kind,symbol){
    const node=button(label,()=>openPanel(kind),kind+'-picker picker-button',symbol);node.dataset.picker=kind;
    node.setAttribute('aria-haspopup','dialog');node.setAttribute('aria-expanded','false');node.setAttribute('aria-controls','selection-options');
    node.append(icon('chevron-down'));return node;
  }
  const engineSelect=picker(profile.label,'engine',profile.mark);engineSelect.setAttribute('aria-label','底层引擎');
  const engineLabel=engineSelect;
  const engineChoices=[{id:'codex',label:'Codex',icon:'code-xml'},{id:'openclaude',label:'OpenClaude',icon:'message-square'},{id:'gemini',label:'Gemini CLI',icon:'sparkles'},{id:'pi',label:'Pi',icon:'terminal'}];
  const modeChoices=[{id:'ask',label:'问答',detail:'只读',icon:'message-square'},{id:'plan',label:'规划',detail:'只读 · 任务清单',icon:'list-checks'},{id:'execute',label:'执行',detail:'暂存修改 · 命令需审批',icon:'wrench'}];
  function selection(kind){
    const choices=kind==='engine'?engineChoices.map(item=>{const availability=(state.engines||[]).find(engine=>engine.id===item.id);return {...item,detail:!availability?.checked?'尚未检查':availability.available?'本地资源可用':availability.reason||'未安装'};}):kind==='mode'?modeChoices:state.thinkingChoices||[{id:'auto',label:'自动',detail:'引擎默认'}];
    const value=kind==='engine'?state.engine:kind==='mode'?state.mode:state.thinkingLevel||'auto';
    const container=el('div','selection-content');
    container.append(choiceList(({engine:'底层引擎',mode:'操作权限',thinking:'思考等级'})[kind],choices,value,id=>{
      if(state.busy)return;closePanel();request(kind==='thinking'?'thinking':kind,{id});
    }));
    if(kind==='thinking'&&['gemini','openclaude'].includes(state.engine))container.replaceChildren(button('思考预算 / 思考强度',()=>nativeRequest('settings'),'menu-action','sliders-horizontal'));
    if(kind==='thinking'&&state.thinkingNote)container.append(el('p','selection-note',state.thinkingNote));
    return container;
  }
  const topTitle=el('span','top-title');
  const fresh=iconButton('新会话','plus',()=>request('new'));
  const more=iconButton('会话选项','ellipsis',()=>openPanel('menu'));
  header.append(menuButton,engineLabel,topTitle,fresh,more);
  const workspaceBar=el('div','workspace-bar');
  const workspace=button('工作区',()=>nativeRequest('workspaces'),'workspace-button','folder');
  const workspaceName=workspace.querySelector('span');
  const workInfo=button('工作信息',()=>showInfo(),'work-info','panel-right');
  const changesLink=button('改动',()=>nativeRequest('review'),'changes-link','git-pull-request');
  const contextBadge=button('上下文',()=>showInfo('context'),'context-badge','layers');
  workspaceBar.append(workspace,contextBadge,workInfo,changesLink);
  const alert=el('div','notice');alert.setAttribute('role','status');const alertText=el('span');
  const retry=button('连接环境',()=>request('connect'),'text-button');alert.append(alertText,retry);alert.hidden=true;
  const viewport=el('section','conversation-viewport');viewport.setAttribute('aria-label','对话记录');
  const welcome=el('div','welcome');welcome.append(engineArtwork(profile),el('h1','',profile.greeting));
  if(profile.starters.length){const starters=el('div','task-starters');for(const [label,symbol,text] of profile.starters){starters.append(button(label,()=>{if(!input.value.trim()){input.value=text;saveDraft();fitInput();}input.focus();},'starter',symbol));}welcome.append(starters);}
  const welcomeWorkspace=el('p','welcome-workspace');welcome.append(welcomeWorkspace);
  const messages=el('div','messages'),messageNodes=new Map();
  viewport.append(welcome,messages);
  const jump=button('回到最新',()=>{follow=true;viewport.scrollTop=viewport.scrollHeight;jump.hidden=true;},'jump-latest','arrow-down');jump.hidden=true;
  viewport.addEventListener('scroll',()=>{follow=viewport.scrollHeight-viewport.scrollTop-viewport.clientHeight<72;jump.hidden=follow;},{passive:true});
  const composerArea=el('div','composer-area');
  const statusLine=el('div','status-line');statusLine.setAttribute('role','status');
  const statusDot=el('span','status-dot'),statusText=el('span');statusLine.append(statusDot,statusText);
  const composer=el('form','composer');
  const project=button('选择工作区',()=>nativeRequest('workspaces'),'project-strip','folder');const projectName=project.querySelector('span');project.append(icon('chevron-down'));project.hidden=true;
  const chips=el('div','context-chips');chips.hidden=true;
  const input=el('textarea','task-input');input.rows=2;input.maxLength=12000;input.placeholder=profile.placeholder;input.setAttribute('aria-label','任务描述');input.value=local.text||'';
  function fitInput(){input.rows=Math.min(5,Math.max(2,input.value.split('\n').length));submit.disabled=!!state.busy||!state.session||!input.value.trim()||(availability()?.checked&&!availability()?.available);}
  input.addEventListener('input',()=>{saveDraft();fitInput();});
  input.addEventListener('blur',flushDraft);
  input.addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();if(composer.requestSubmit)composer.requestSubmit();else submit.click();}});
  const controls=el('div','composer-controls');
  const attach=iconButton('选择上下文文件','paperclip',()=>nativeRequest('context_files'));attach.classList.add('context-picker');
  const mode=picker('执行','mode');mode.setAttribute('aria-label','操作权限');const modeLabel=mode;
  const model=button('配置模型',()=>nativeRequest('settings'),'model-selector');const modelText=model.querySelector('span');model.append(icon('chevron-down'));
  const thinking=picker('自动','thinking','sliders-horizontal');thinking.setAttribute('aria-label','思考等级');thinking.title='思考等级';
  const submit=iconButton('发送任务','arrow-up',()=>{},'send-button');submit.type='submit';submit.disabled=true;
  const stop=iconButton('停止任务','square',()=>request('stop'),'send-button stop-button');stop.hidden=true;
  controls.append(attach,modeLabel,model,thinking);
  const inputRow=el('div','composer-input-row'),sendLane=el('div','send-lane');sendLane.append(submit,stop);inputRow.append(input,sendLane);
  composer.append(project,chips,inputRow,controls);composerArea.append(statusLine,composer);
  composer.addEventListener('submit',e=>{e.preventDefault();if(!submit.disabled&&input.value.trim())request('send',{text:input.value});});
  main.append(header,workspaceBar,alert,viewport,jump,composerArea);
  const sidebar=el('div','sidebar-content');
  sidebar.append(el('div','sidebar-brand',profile.id==='pi'?'Pi':'Aster'));
  const newNav=button('新会话',()=>{closePanel();request('new');},'new-conversation','plus');
  const workspaceNav=button('工作区',()=>{closePanel();request('workspaces');},'nav-link','folder');
  const promptNav=button('提示词',()=>showInfo('prompt'),'nav-link','notebook-pen');
  const search=el('input','history-search');search.placeholder='搜索会话';search.setAttribute('aria-label','搜索会话');search.type='search';
  const history=el('div','history-list');
  search.addEventListener('input',()=>renderHistory(true));
  const footer=el('div','sidebar-footer');
  const nativeSettings=button('模型设置',()=>{closePanel();request('settings');},'nav-link','settings-2');
  const workMode=button('切换工作模式',()=>{closePanel();request('work_mode');},'nav-link','arrow-left-right');
  footer.append(nativeSettings,button('轻舟浏览器',()=>nativeRequest('browser'),'nav-link','layers'),button('设备权限',()=>nativeRequest('device_permissions'),'nav-link','settings-2'),workMode);
  sidebar.append(newNav,workspaceNav,promptNav,el('h2','sidebar-heading','最近会话'),search,history,footer);
  const inspector=el('div','inspector-content');
  const sessionFacts=section('当前会话','session-facts'),modelFact=el('p','session-model'),thinkingFact=el('p','small-text');
  sessionFacts.append(modelFact,thinkingFact);
  const inspectorHeader=el('header','inspector-header');inspectorHeader.append(el('h2','','工作信息'),iconButton('收起工作信息','panel-right',()=>{infoPinned=false;restorePanels();}));
  const tabs=el('div','info-tabs');tabs.setAttribute('role','tablist');
  const tabNodes={},tabContent={};
  for(const [key,label] of [['overview','进度'],['context','上下文'],['prompt','提示词']]){
    const b=button(label,()=>{infoTab=key;renderTabs();},'info-tab');b.setAttribute('role','tab');b.id='tab-'+key;
    b.setAttribute('aria-controls','panel-'+key);tabs.append(b);tabNodes[key]=b;
    const panel=el('div','info-tab-content');panel.setAttribute('role','tabpanel');panel.id='panel-'+key;panel.setAttribute('aria-labelledby',b.id);tabContent[key]=panel;
  }
  function renderTabs(){for(const key of Object.keys(tabNodes)){tabNodes[key].setAttribute('aria-selected',String(key===infoTab));tabNodes[key].tabIndex=key===infoTab?0:-1;tabContent[key].hidden=key!==infoTab;}}
  tabs.addEventListener('keydown',event=>{
    if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
    event.preventDefault();const keys=Object.keys(tabNodes),index=keys.indexOf(infoTab);
    infoTab=keys[event.key==='Home'?0:event.key==='End'?keys.length-1:(index+(event.key==='ArrowRight'?1:-1)+keys.length)%keys.length];
    renderTabs();tabNodes[infoTab].focus();
  });
  const tasks=section('任务进度','tasks-section'),taskList=el('ul','task-list');tasks.append(taskList);
  const directory=section('工作目录');const directoryAction=button('工作区',()=>request('workspaces'),'directory-action','folder');directory.append(directoryAction);
  const changes=section('修改审阅'),changeList=el('div','change-list');
  const review=button('审阅修改',()=>request('review'),'outline-button','git-pull-request');changes.append(changeList,review);
  const contextOverview=section('上下文','context-overview'),overviewFiles=el('div');contextOverview.append(overviewFiles,button('查看上下文',()=>showInfo('context'),'outline-button','layers'));
  tabContent.overview.append(sessionFacts,tasks,directory,contextOverview,changes);
  const context=section('上下文用量'),usage=el('p','context-usage'),meter=el('progress');meter.max=1;meter.setAttribute('aria-label','上下文用量');
  const compactions=el('p','small-text'),configuredWindow=el('p','small-text context-configured'),measurement=el('p','small-text context-measurement'),policyNote=el('p','small-text context-policy');
  const contextSettings=button('调整上下文窗口',()=>request('settings'),'outline-button','settings-2');
  const compact=button('压缩上下文',()=>request('compact'),'outline-button','layers');
  const compactNote=el('p','small-text');context.append(usage,meter,configuredWindow,measurement,policyNote,contextSettings,compactions,compact,compactNote);
  const files=section('已选择的文件'),fileList=el('div','context-files'),pickFiles=button('选择文件',()=>request('context_files'),'outline-button','paperclip');files.append(fileList,pickFiles);
  const runtime=section('运行环境'),runtimeText=el('p','small-text'),connect=button('连接环境',()=>request('connect'),'outline-button','terminal');runtime.append(runtimeText,connect,button('运行详情与诊断',()=>request('diagnostics'),'outline-button','terminal'));
  tabContent.context.append(context,files,runtime);
  const prompt=el('textarea','prompt-input');prompt.rows=9;prompt.maxLength=8000;prompt.setAttribute('aria-label','工作指令');
  const savePrompt=button('保存提示词',()=>request('prompt',{text:prompt.value}),'primary-button');
  tabContent.prompt.append(el('p','small-text','这些指令会随工作区任务发送给当前引擎。'),prompt,savePrompt);
  inspector.append(inspectorHeader,tabs,...Object.values(tabContent));
  const menu=el('div','action-menu');
  const menuActions=[['工作信息','panel-right',()=>showInfo()],['上下文与压缩','layers',()=>showInfo('context')],['提示词','notebook-pen',()=>showInfo('prompt')],['修改审阅','git-pull-request',()=>{closePanel();request('review');}],['模型与上下文设置','settings-2',()=>{closePanel();request('settings');}],['运行详情与诊断','terminal',()=>{closePanel();request('diagnostics');}],['切换工作模式','arrow-left-right',()=>{closePanel();request('work_mode');}]];
  for(const [label,symbol,action]of menuActions)menu.append(button(label,action,'menu-action',symbol));
  function availability(){return(state.engines||[]).find(e=>e.id===state.engine);}
  function renderHistory(force=false){
    const key=JSON.stringify(state.history||[]);if(!force&&key===lastHistory)return;lastHistory=key;
    history.replaceChildren();const query=search.value.trim().toLocaleLowerCase();
    const entries=(state.history||[]).filter(e=>e.title.toLocaleLowerCase().includes(query));
    for(const entry of entries){
      const row=el('div','history-row');const b=button(entry.title,()=>{closePanel();request('history',{id:entry.id});},'history-link','message-square');
      b.dataset.session=entry.id;b.title=entry.title;
      const remove=iconButton('删除会话 '+entry.title,'trash-2',()=>request('delete',{id:entry.id}),'history-delete');
      b.disabled=!!state.busy;remove.disabled=!!state.busy;row.append(b,remove);history.append(row);
    }
    if(!entries.length)history.append(el('p','empty-note',query?'没有匹配的会话':'暂无历史会话'));
  }
  function update(next){
    state=next;root.dataset.reducedMotion=String(!!state.reducedMotion);
    const empty=!(state.messages||[]).length;root.dataset.empty=String(empty);root.dataset.busy=String(!!state.busy);
    welcome.hidden=!empty;messages.hidden=empty;topTitle.textContent=state.title||'新会话';
    engineSelect.querySelector('.button-label').textContent=profile.label;workspaceName.textContent=state.workspaceName||'选择工作区';projectName.textContent=state.workspaceName||'选择工作区';
    welcomeWorkspace.textContent=state.workspaceName||'';workspace.title=state.workspaceName||'选择工作区';
    const selectedThinking=(state.thinkingChoices||[]).find(item=>item.id===(state.thinkingLevel||'auto'));
    const thinkingLabel=state.thinkingLabel||selectedThinking?.label||'自动';thinking.querySelector('.button-label').textContent=thinkingLabel;
    thinking.dataset.explicit=String((state.thinkingLevel||'auto')!=='auto');
    thinking.title='思考等级 · '+thinkingLabel;thinking.setAttribute('aria-label',thinking.title);
    thinkingFact.textContent='思考 · '+thinkingLabel;modelFact.textContent=state.model||'未配置模型';
    const knownContext=['estimate','hybrid','engine'].includes(state.contextSource);
    contextBadge.querySelector('.button-label').textContent=knownContext?Math.round(100*(state.tokens||0)/Math.max(1,state.window||1))+'%':'上下文';
    contextBadge.title=knownContext?'最近上下文用量':'尚无有效用量报告';
    directoryAction.querySelector('span').textContent=state.workspaceName||'尚未选择';
    const fileCount=(state.contextFiles||[]).length;
    workInfo.querySelector('span').textContent='工作信息';
    changesLink.querySelector('span').textContent='改动 '+(state.changes||[]).length;
    changesLink.hidden=!(state.changes||[]).length;workInfo.hidden=false;
    const available=availability();
    const warning=state.saveError||state.runtimeError||(available?.checked&&!available.available?available.reason:'');
    alertText.textContent=warning;alert.hidden=!warning;retry.hidden=!!state.saveError;retry.disabled=!!state.busy;
    statusText.textContent=state.approval?'等待命令审批':state.stopping?'正在停止…':state.busy?(state.running?state.status:state.runtime||'准备中'):state.status;
    statusLine.hidden=!state.busy&&(state.status==='准备就绪'||!state.status);
    statusDot.classList.toggle('active',!!state.busy);mode.querySelector('.button-label').textContent=modeChoices.find(item=>item.id===state.mode)?.label||'执行';modelText.textContent=state.model||'配置模型';model.title=state.model||'配置模型';
    if(state.busy&&['engine','mode','thinking'].includes(drawerMode))closePanel();
    for(const button of rail.querySelectorAll('button'))button.disabled=(!!state.busy||!state.session)&&['新会话','上下文文件','模型设置'].includes(button.getAttribute('aria-label'));
    for(const node of [engineSelect,fresh,workspace,project,newNav,workspaceNav,mode,model,thinking,attach,nativeSettings,workMode,review,pickFiles,savePrompt,directoryAction,connect,contextSettings])node.disabled=!!state.busy||!state.session;
    review.disabled=review.disabled||!(state.changes||[]).length;changes.hidden=!(state.changes||[]).length;
    stop.hidden=!state.busy;submit.hidden=!!state.busy;stop.disabled=!!state.stopping;
    for(const b of menu.querySelectorAll('button'))b.disabled=!!state.busy&&['修改审阅','模型与上下文设置','切换工作模式'].includes(b.textContent);
    if(lastAck!==undefined&&next.acceptance!==lastAck&&input.value.trim()===next.acceptedText){input.value='';local.text='';local.dirty=false;}
    lastAck=next.acceptance;
    fitInput();
    requestAnimationFrame(positionJump);
    if(lastPrompt!==state.prompt){if(document.activeElement!==prompt)prompt.value=state.prompt||'';lastPrompt=state.prompt;}
    const source=state.contextSource||'unknown',known=['estimate','hybrid','engine'].includes(source);
    const labels={estimate:'估算用量',hybrid:'usage + 尾部估算',engine:'引擎报告用量'};
    usage.textContent=known?labels[source]+'：'+(state.tokens||0).toLocaleString()+' / '+(state.window||0).toLocaleString()+' tokens':'尚无有效用量报告';
    meter.hidden=!known;meter.value=Math.min(1,(state.tokens||0)/Math.max(1,state.window||1));
    configuredWindow.textContent='已保存窗口：'+(state.configuredWindow||state.window||0).toLocaleString()+' tokens · 下一次任务使用';
    measurement.textContent=known?'这是最近一次采样，不是实时账单。'+(state.contextUpdatedAt?' 更新于 '+new Date(state.contextUpdatedAt).toLocaleTimeString():''):'开始任务后显示估算或原生报告；未测量不等于已使用 0 tokens。';
    policyNote.textContent=state.contextPolicy||'';
    compactions.textContent='已压缩 '+(state.compactions||0)+' 次';
    compact.disabled=!!state.busy||empty||state.compactSupported===false;
    compactNote.textContent=state.compactSupported===false?'此引擎暂不支持手动压缩。':'';compactNote.hidden=!compactNote.textContent;
    runtimeText.textContent=(state.runtime||'环境未连接')+' · '+(state.setupPhase||'尚未检查')+'\n构建底层：'+profile.label+' '+(state.engineVersion||'')+'；资源可用不等于模型请求成功。';
    const messageKey=JSON.stringify(state.messages||[]);
    if(lastMessages!==messageKey){
      const ids=new Set();let previous=null;
      for(const item of state.messages||[]){
        ids.add(item.id);let record=messageNodes.get(item.id);
        if(!record){
          const tool=item.role==='tool'||item.role==='system';const row=el(tool?'details':'article','message '+(tool?'tool-event':item.role==='user'?'user':'assistant'));
          const body=el('div','message-body');
          if(tool){const label=el('summary');label.append(icon('terminal'),el('span','',item.text.split('\n')[0].slice(0,110)),icon('chevron-right'));row.append(label,body);}
          else {if(item.role!=='user')row.append(el('div','assistant-label',profile.label));row.append(body);}
          record={row,body,text:null};messageNodes.set(item.id,record);
        }
        if(record.text!==item.text){if(item.role==='tool'||item.role==='system'){record.row.querySelector('summary span').textContent=item.text.split('\n')[0].slice(0,110);record.row.dataset.failed=String(item.text.split('\n')[0].includes(' · 失败'));}if(item.role==='user')record.body.textContent=item.text;else richText(record.body,item.text,text=>request('copy',{text}));record.text=item.text;}
        if(record.row.previousElementSibling!==previous||record.row.parentElement!==messages)messages.insertBefore(record.row,previous?previous.nextSibling:messages.firstChild);
        previous=record.row;
      }
      for(const[id,record]of messageNodes)if(!ids.has(id)){record.row.remove();messageNodes.delete(id);}
      lastMessages=messageKey;if(follow)requestAnimationFrame(()=>{viewport.scrollTop=viewport.scrollHeight;});else jump.hidden=false;
    }
    renderHistory();for(const b of history.querySelectorAll('button')){b.disabled=!!state.busy;if(b.dataset.session)b.setAttribute('aria-current',String(b.dataset.session===state.session));}
    const taskKey=JSON.stringify(state.tasks||[]);if(taskKey!==lastTasks){taskList.replaceChildren();for(const item of state.tasks||[]){const done=['completed','done'].includes(item.status);const running=['in_progress','running','active'].includes(item.status);const li=el('li',done?'done':running?'active':'pending');li.append(icon(done?'check':running?'loader-circle':'circle'),el('span','',item.task));taskList.append(li);}if(!(state.tasks||[]).length)taskList.append(el('li','empty-note','尚未生成任务清单'));lastTasks=taskKey;}
    const changesKey=JSON.stringify(state.changes||[]);if(changesKey!==lastChanges){changeList.replaceChildren();for(const item of state.changes||[])changeList.append(button(item.path,()=>request('review'),'file-row','file-code-2'));if(!(state.changes||[]).length)changeList.append(el('p','empty-note','暂无待应用修改'));lastChanges=changesKey;}
    const filesKey=JSON.stringify(state.contextFiles||[]);if(filesKey!==lastFiles){chips.replaceChildren();fileList.replaceChildren();overviewFiles.replaceChildren();for(const path of state.contextFiles||[]){const item=button(path.split('/').pop(),()=>request('context_files'),'file-chip','file-code-2');item.title=path;chips.append(item);fileList.append(el('p','file-reference',path));overviewFiles.append(el('p','file-reference',path));}if(!fileCount){fileList.append(el('p','empty-note','未额外选择文件。Agent 可按当前权限读取工作区。'));overviewFiles.append(el('p','small-text','使用当前工作区'));}chips.hidden=!fileCount;lastFiles=filesKey;}
  }
  function positionJump(){if(root.isConnected)jump.style.bottom=(composerArea.offsetHeight+12)+'px';}
  const composerObserver=typeof ResizeObserver==='function'?new ResizeObserver(positionJump):null;
  composerObserver?.observe(composerArea);
  window.addEventListener('resize',positionJump);
  input.addEventListener('input',positionJump);
  renderTabs();restorePanels();
  const resized=()=>{if(panelIsOpen()&&['history','info'].includes(drawerMode)&&wide.matches)closePanel();restorePanels();};
  for(const query of [wide,dual]){if(query.addEventListener)query.addEventListener('change',resized);else query.addListener(resized);}
  const visibility=()=>{if(document.hidden)flushDraft();};document.addEventListener('visibilitychange',visibility);
  return {root,update,closePanel:()=>{if(!panelIsOpen())return false;closePanel();return true;},dispose(){flushDraft();clearTimeout(draftTimer);composerObserver?.disconnect();window.removeEventListener('resize',positionJump);for(const query of [wide,dual]){if(query.removeEventListener)query.removeEventListener('change',resized);else query.removeListener(resized);}document.removeEventListener('visibilitychange',visibility);closePanel();}};
}
