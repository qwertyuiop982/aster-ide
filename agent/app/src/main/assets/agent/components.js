import {createMessageRenderer} from './message-renderer.js';
import {thinkingBubble,subagentNavigator} from './bubbles.js';
import {applyCodexAppearance,syncCodexCanvas} from './appearance.js';
import {el,icon,button,iconButton,section,richText} from './dom.js';
import {choiceList} from './choices.js';
import {engineArtwork} from './patterns.js';

/** Shared real-state controls; each engine owns its layout profile. */
export function createPage(profile,send,local) {
  const preparedSelections=new Map();let bubble,childNav;
  let state={},draftTimer,follow=true,lastHistory='',lastTasks='',lastChanges='',lastFiles='',lastAck,lastPrompt;
  let drawerMode='',previousFocus=null,infoTab='overview',navPinned=profile.navigation,infoPinned=profile.inspector,layout='';
  const wide=matchMedia('(min-width: 1080px) and (min-height: 520px) and (orientation: landscape)');
  const dual=matchMedia('(min-width: 1360px) and (min-height: 520px) and (orientation: landscape)');
  const root=el('main','agent-layout '+profile.id);root.dataset.empty='true';
  const sidebarHost=el('aside','sidebar-host'),main=el('section','main-column'),infoHost=el('aside','info-host');
  const modal=el('aside','utility-dialog');modal.hidden=true;modal.setAttribute('aria-label','会话侧边栏');modal.setAttribute('aria-hidden','true');
  const modalBar=el('header','dialog-bar'),modalTitle=el('h2');
  const sidebarBack=iconButton('返回侧边栏','chevron-left',()=>openPanel('history'));sidebarBack.hidden=true;
  modalBar.append(sidebarBack,modalTitle,iconButton('关闭侧边栏','x',closePanel));
  const modalBody=el('div','dialog-body');modal.append(modalBar,modalBody);
  const routePage=el('section','route-page');routePage.hidden=true;routePage.setAttribute('aria-label','Agent 配置页面');routePage.id='agent-config-page';routePage.tabIndex=-1;
  const routeBar=el('header','route-bar'),routeTitle=el('h1');routeBar.append(iconButton('返回对话','arrow-left',closePanel),routeTitle);
  const routeBody=el('div','route-body');routePage.append(routeBar,routeBody);
  const quick=el('section','quick-picker');quick.hidden=true;quick.id='agent-quick-picker';quick.setAttribute('role','dialog');quick.setAttribute('aria-labelledby','quick-picker-title');
  const quickBar=el('header','quick-picker-bar'),quickTitle=el('h2');quickTitle.id='quick-picker-title';
  quickBar.append(quickTitle,iconButton('关闭选项','x',()=>closeQuickPicker()));
  const quickBody=el('div','quick-picker-body');quick.append(quickBar,quickBody);
  let quickMode='',quickAnchor=null,quickFocus=null;
  const shade=el('div','dialog-shade');shade.hidden=true;
  const rail=el('nav','navigation-rail');rail.setAttribute('aria-label','Agent 导航');
  const railBrand=el('div','rail-brand');railBrand.append(icon(profile.mark));
  rail.append(railBrand,iconButton('会话历史','history',toggleHistory),iconButton('新会话','plus',()=>request('new')),
    iconButton('工作信息','panel-right',()=>showInfo()),iconButton('上下文文件','paperclip',()=>nativeRequest('context_files')),
    el('span','rail-spacer'),iconButton('模型设置','settings-2',()=>nativeRequest('settings')));
  root.append(rail,sidebarHost,main,infoHost,shade,modal,routePage,quick);
  const request=(command,data={})=>{if(command!=='draft')flushDraft();send(command,data);};
  function nativeRequest(command,data={}) { closePanel();request(command,data); }
  function saveDraft(){local.text=input.value;local.dirty=true;clearTimeout(draftTimer);draftTimer=setTimeout(flushDraft,250);}
  function flushDraft(){clearTimeout(draftTimer);if(local.dirty){send('draft',{text:local.text||''});local.dirty=false;}}
  const panelIsOpen=()=>!modal.hidden||!routePage.hidden;
  function closePanel(){
    closeQuickPicker(false);
    bubble?.close(true);
    if(!panelIsOpen())return;
    modal.hidden=true;modal.setAttribute('aria-hidden','true');routePage.hidden=true;shade.hidden=true;
    drawerMode='';setRouteActive(false);restorePanels();panelChanged();
    if(previousFocus?.isConnected)previousFocus.focus();
  }
  function setRouteActive(active){for(const node of [rail,sidebarHost,main,infoHost,root.querySelector('.agent-dock')].filter(Boolean)){node.inert=active;if(active)node.setAttribute('aria-hidden','true');else node.removeAttribute('aria-hidden');}root.dataset.routeOpen=String(active);if(active){const focused=document.activeElement;if(focused&&main.contains(focused))focused.blur();}}
  function panelChanged(){
    send('panel',{id:panelIsOpen()||!quick.hidden||bubble?.isOpen()||childNav?.selected||childNav?.isOpen?'open':''});
    for(const node of root.querySelectorAll('[data-picker],.thinking-picker'))node.setAttribute('aria-expanded',String(node===thinking?!!bubble?.isOpen():['engine','mode','collaboration'].includes(node.dataset.picker)?!quick.hidden&&node.dataset.picker===quickMode:panelIsOpen()&&node.dataset.picker===drawerMode));
  }
  const escapePanel=event=>{if(event.key!=='Escape')return;if(!quick.hidden){event.preventDefault();closeQuickPicker();}else if(panelIsOpen()){event.preventDefault();closePanel();}};
  function closeQuickPicker(restoreFocus=true){
    if(quick.hidden)return false;
    quick.hidden=true;quickMode='';quickAnchor=null;panelChanged();
    if(restoreFocus&&quickFocus?.isConnected)quickFocus.focus();
    quickFocus=null;return true;
  }
  function positionQuickPicker(){
    if(quick.hidden||!quickAnchor?.isConnected)return;
    const anchor=quickAnchor.getBoundingClientRect();
    if(!anchor.width||!anchor.height){closeQuickPicker(false);return;}
    const pad=12,gap=8,belowSpace=Math.max(0,innerHeight-pad-anchor.bottom-gap),aboveSpace=Math.max(0,anchor.top-pad-gap);
    quick.style.maxHeight=Math.max(96,Math.max(belowSpace,aboveSpace))+'px';
    const width=quick.offsetWidth,height=quick.offsetHeight;
    const below=anchor.bottom+gap,above=anchor.top-height-gap;
    const top=below+height<=innerHeight-pad?below:above>=pad?above:Math.max(pad,Math.min(innerHeight-height-pad,below));
    quick.style.left=Math.max(pad,Math.min(innerWidth-width-pad,anchor.left))+'px';
    quick.style.top=top+'px';syncCodexCanvas(root);
  }
  function openQuickPicker(kind,anchor){
    if(state.busy||!state.session)return;
    if(!quick.hidden&&quickMode===kind){closeQuickPicker();return;}
    bubble?.close(true);closeQuickPicker(false);
    if(!routePage.hidden)closePanel();
    quickMode=kind;quickAnchor=anchor;quickFocus=anchor||document.activeElement;
    quick.dataset.kind=kind;quickTitle.textContent=kind==='collaboration'?'子代理协作模式':kind==='engine'?'底层引擎':'操作权限';
    quickBody.replaceChildren(selection(kind));quick.hidden=false;positionQuickPicker();panelChanged();
    requestAnimationFrame(()=>{if(quick.hidden||quickMode!==kind)return;positionQuickPicker();quickBody.querySelector('[aria-selected=true]:not(:disabled),button:not(:disabled)')?.focus();});
  }
  const quickOutside=event=>{if(!quick.hidden&&!quick.contains(event.target)&&!quickAnchor?.contains(event.target))closeQuickPicker(false);};
  document.addEventListener('pointerdown',quickOutside);
  window.addEventListener('resize',positionQuickPicker);
  document.addEventListener('scroll',positionQuickPicker,true);
  document.addEventListener('keydown',escapePanel);
  function openPanel(kind,anchor){
    if(['engine','mode','collaboration'].includes(kind)){openQuickPicker(kind,anchor);return;}
    closeQuickPicker(false);
    if(layout==='chat'&&kind==='menu')kind='history';
    bubble?.close(true);
    if(panelIsOpen())closePanel();
    previousFocus=document.activeElement;drawerMode=kind;
    modal.dataset.kind=kind;modal.dataset.side=String(layout==='chat');sidebarBack.hidden=layout!=='chat'||kind==='history';
    modalTitle.textContent=({history:'会话与工作区',menu:'会话选项'})[kind]||'';
    if(kind==='history'||kind==='menu'){
      modalBody.replaceChildren(kind==='history'?sidebar:menu);
      routePage.hidden=true;modal.hidden=false;modal.setAttribute('aria-hidden','false');menuButton?.setAttribute('aria-expanded','true');
      requestAnimationFrame(()=>modalBody.querySelector('button:not(:disabled),input:not(:disabled)')?.focus());
    }else{
      modal.hidden=true;modal.setAttribute('aria-hidden','true');routePage.hidden=false;
      routeTitle.textContent=({engine:'底层引擎',mode:'操作权限',thinking:'思考等级',info:'工作信息'})[kind]||'设置';
      routeBody.replaceChildren(kind==='info'?inspector:selection(kind));setRouteActive(true);
      requestAnimationFrame(()=>routeBody.querySelector('[aria-selected=true]:not(:disabled),button:not(:disabled),input:not(:disabled)')?.focus());
    }
    panelChanged();
  }
  function restorePanels(){
    if(drawerMode!=='history')sidebarHost.append(sidebar);
    if(drawerMode!=='info')infoHost.append(inspector);
    const navigation=layout!=='chat'&&wide.matches&&navPinned&&(!infoPinned||dual.matches);
    root.dataset.navigation=String(navigation);root.dataset.inspector=String(layout!=='chat'&&wide.matches&&infoPinned);
    sidebarHost.hidden=!navigation;infoHost.hidden=!(layout!=='chat'&&wide.matches&&infoPinned);
    menuButton?.setAttribute('aria-expanded',String(navigation||drawerMode==='history'));
  }
  function toggleHistory(){if(layout==='chat'){if(!modal.hidden&&drawerMode==='history')closePanel();else openPanel('history');return;}if(wide.matches){navPinned=sidebarHost.hidden;if(navPinned&&!dual.matches)infoPinned=false;restorePanels();}else openPanel('history');}
  function showInfo(tab='overview'){infoTab=tab;renderTabs();openPanel('info');}
  const header=el('header','topbar');
  const menuButton=iconButton('打开会话历史','menu',toggleHistory);
  function picker(label,kind,symbol){
    const node=button(label,event=>openPanel(kind,event.currentTarget),kind+'-picker picker-button',symbol);node.dataset.picker=kind;
    node.setAttribute('aria-haspopup','dialog');node.setAttribute('aria-expanded','false');node.setAttribute('aria-controls','agent-quick-picker');
    node.append(icon('chevron-down'));return node;
  }
  const engineSelect=picker(profile.label,'engine',profile.mark);engineSelect.setAttribute('aria-label','底层引擎');
  const engineLabel=engineSelect;
  const engineChoices=[{id:'codex',label:'Codex',icon:'code-xml'},{id:'openclaude',label:'OpenClaude',icon:'message-square'},{id:'gemini',label:'Gemini CLI',icon:'sparkles'},{id:'pi',label:'Pi',icon:'terminal'}];
  const modeChoices=[{id:'ask',label:'问答',detail:'只读',icon:'message-square'},{id:'plan',label:'规划',detail:'只读 · 任务清单',icon:'list-checks'},{id:'execute',label:'执行',detail:'暂存修改 · 命令需审批',icon:'wrench'}];
  const collaborationChoices=[{id:'extreme',label:'极端',detail:'配合 Ultra · 主代理首批派发，子代理递归协作',icon:'sparkles'},{id:'team',label:'团队',detail:'主代理只编排整合，全部执行委派子代理',icon:'layers'},{id:'assist',label:'辅助',detail:'根据任务大小适当委派',icon:'sliders-horizontal'},{id:'off',label:'关闭',detail:'不注入协作提示词，代理工具保留',icon:'circle'}];
  function selection(kind){
    const choices=kind==='collaboration'?collaborationChoices:kind==='engine'?engineChoices.map(item=>{const availability=(state.engines||[]).find(engine=>engine.id===item.id);return {...item,detail:!availability?.checked?'尚未检查':availability.available?'本地资源可用':availability.reason||'未安装'};}):kind==='mode'?modeChoices:state.thinkingChoices||[{id:'auto',label:'自动',detail:'引擎默认'}];
    const value=kind==='collaboration'?(state.collaborationMode||'assist'):kind==='engine'?state.engine:kind==='mode'?state.mode:state.thinkingLevel||'auto';
    const cacheKey=JSON.stringify([choices,value,state.thinkingNote||'']);
    const cached=preparedSelections.get(kind);
    if(cached?.key===cacheKey)return cached.node;
    const container=el('div','selection-content');
    container.append(choiceList(({collaboration:'子代理协作模式',engine:'底层引擎',mode:'操作权限',thinking:'思考等级'})[kind],choices,value,id=>{
      if(state.busy)return;if(['engine','mode','collaboration'].includes(kind))closeQuickPicker();else closePanel();request(kind==='thinking'?'thinking':kind,{id});
    }));
    if(kind==='thinking'&&['gemini','openclaude'].includes(state.engine))container.replaceChildren(button('思考预算 / 思考强度',()=>nativeRequest('settings'),'menu-action','sliders-horizontal'));
    if(kind==='thinking'&&state.thinkingNote)container.append(el('p','selection-note',state.thinkingNote));
    preparedSelections.set(kind,{key:cacheKey,node:container});
    return container;
  }
  const topTitle=el('span','top-title');
  const fresh=iconButton('新会话','plus',()=>request('new'));
  const more=iconButton('会话选项','ellipsis',()=>openPanel('menu'));
  const logs=button('日志',()=>request('diagnostics'),'icon-button','terminal');
  const topStop=iconButton('停止任务','square',()=>request('stop'),'top-stop');topStop.hidden=true;
  header.append(menuButton,engineLabel,topTitle,logs,fresh,more);
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
  const welcome=el('div','welcome');if(profile.id!=='codex')welcome.append(engineArtwork(profile));welcome.append(el('h1','',profile.greeting));
  if(profile.starters.length){const starters=el('div','task-starters');for(const [label,symbol,text] of profile.starters){starters.append(button(label,()=>{if(!input.value.trim()){input.value=text;saveDraft();fitInput();}input.focus();},'starter',symbol));}welcome.append(starters);}
  const welcomeWorkspace=el('p','welcome-workspace');welcome.append(welcomeWorkspace);
  const messages=el('div','messages');
  const renderMessages=createMessageRenderer(messages,request);
  viewport.append(welcome,messages);
  const jump=button('回到最新',()=>{follow=true;viewport.scrollTop=viewport.scrollHeight;jump.hidden=true;},'jump-latest','arrow-down');jump.hidden=true;
  viewport.addEventListener('scroll',()=>{follow=viewport.scrollHeight-viewport.scrollTop-viewport.clientHeight<72;jump.hidden=follow;},{passive:true});
  const composerArea=el('div','composer-area');
  const statusLine=el('div','status-line');statusLine.setAttribute('role','status');
  const statusDot=el('span','status-dot'),statusText=el('span');statusLine.append(statusDot,statusText);
  const composer=el('form','composer');
  const project=button('选择工作区',()=>nativeRequest('workspaces'),'project-strip','folder');const projectName=project.querySelector('span');project.append(icon('chevron-down'));project.hidden=true;
  const chips=el('div','context-chips');chips.hidden=true;
  const input=el('textarea','task-input');input.rows=1;input.maxLength=12000;input.placeholder=profile.placeholder;input.setAttribute('aria-label','任务描述');input.value=local.text||'';
  function fitInput(){input.rows=Math.min(5,Math.max(1,input.value.split('\n').length));submit.disabled=!!state.busy||!state.session||!input.value.trim()||(availability()?.checked&&!availability()?.available);}
  input.addEventListener('input',()=>{saveDraft();fitInput();});
  input.addEventListener('blur',flushDraft);
  input.addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();if(composer.requestSubmit)composer.requestSubmit();else submit.click();}});
  const controls=el('div','composer-controls');
  const attach=iconButton('选择上下文文件','paperclip',()=>nativeRequest('context_files'));attach.classList.add('context-picker');
  const mode=picker('执行','mode');mode.setAttribute('aria-label','操作权限');const modeLabel=mode;
  const model=button('配置模型',()=>nativeRequest('settings'),'model-selector');const modelText=model.querySelector('span');model.append(icon('chevron-down'));
  const thinking=button('自动',()=>{},'thinking-picker picker-button','sliders-horizontal');thinking.setAttribute('aria-haspopup','dialog');thinking.setAttribute('aria-expanded','false');thinking.setAttribute('aria-label','思考等级');thinking.title='思考等级';
  let longTaskTimer,longTaskPointer=null,longTaskHeld=false,longTaskOrigin;
  const longTask=iconButton('长任务模式：关闭；长按查看说明','timer',event=>{
    if(longTaskHeld){event.preventDefault();longTaskHeld=false;return;}
    if(state.busy||!state.session)return;
    request('long_task',{id:state.longTaskMode?'off':'on'});
  },'long-task-toggle');
  longTask.setAttribute('aria-pressed','false');
  function clearLongTaskPress(){clearTimeout(longTaskTimer);longTaskPointer=null;}
  function showLongTaskInfo(){clearLongTaskPress();if(!state.session)return;input.blur();nativeRequest('long_task_info');}
  longTask.addEventListener('pointerdown',event=>{
    if(event.button!==0||longTaskPointer!==null)return;
    longTaskHeld=false;longTaskPointer=event.pointerId;longTaskOrigin={x:event.clientX,y:event.clientY};
    longTaskTimer=setTimeout(()=>{longTaskHeld=true;showLongTaskInfo();},500);
  });
  longTask.addEventListener('pointermove',event=>{if(event.pointerId===longTaskPointer&&Math.hypot(event.clientX-longTaskOrigin.x,event.clientY-longTaskOrigin.y)>10)clearLongTaskPress();});
  for(const eventName of ['pointerup','pointercancel','pointerleave'])longTask.addEventListener(eventName,clearLongTaskPress);
  longTask.addEventListener('contextmenu',event=>{event.preventDefault();clearLongTaskPress();if(!longTaskHeld){longTaskHeld=true;showLongTaskInfo();}});
  longTask.addEventListener('keydown',event=>{if(event.key==='F1'){event.preventDefault();showLongTaskInfo();}});
  const collaboration=picker('协作','collaboration','layers');
  const submit=iconButton('发送任务','arrow-up',()=>{},'send-button');submit.type='submit';submit.disabled=true;
  const stop=iconButton('停止任务','square',()=>request('stop'),'send-button stop-button');stop.hidden=true;
  controls.append(attach,modeLabel,model,thinking,longTask,collaboration);
  const inputRow=el('div','composer-input-row'),sendLane=el('div','send-lane');sendLane.append(submit,stop);inputRow.append(input,sendLane);
  composer.append(project,chips,inputRow,controls);composerArea.append(statusLine,composer);
  composer.addEventListener('submit',e=>{e.preventDefault();if(!submit.disabled&&input.value.trim())request('send',{text:input.value});});
  main.append(header,workspaceBar,alert,viewport,jump,composerArea);
  bubble=thinkingBubble(root,thinking,()=>state,request,()=>panelChanged());
  childNav=subagentNavigator(root,main,viewport,composerArea,()=>state,()=>{follow=true;update(state);panelChanged();},request,panelChanged);
  const sidebar=el('div','sidebar-content');
  sidebar.append(el('div','sidebar-brand',profile.id==='pi'?'Pi':'Aster'));
  const newNav=button('新会话',()=>{closePanel();request('new');},'new-conversation','plus');
  const workspaceNav=button('工作区',()=>{closePanel();request('workspaces');},'nav-link','folder');
  const promptNav=button('提示词',()=>showInfo('prompt'),'nav-link','notebook-pen');
  const search=el('input','history-search');search.placeholder='搜索会话';search.setAttribute('aria-label','搜索会话');search.type='search';
  const history=el('div','history-list');
  search.addEventListener('input',()=>renderHistory(true));
  const sidebarControls=el('div','sidebar-controls');
  const footer=el('div','sidebar-footer');
  const nativeSettings=button('模型设置',()=>{closePanel();request('settings');},'nav-link','settings-2');
  const workMode=button('切换工作模式',()=>{closePanel();request('work_mode');},'nav-link','arrow-left-right');
  footer.append(button('环境安装设置',()=>{closePanel();request('environment');},'nav-link','terminal'),nativeSettings,button('轻舟浏览器',()=>nativeRequest('browser'),'nav-link','layers'),button('设备权限',()=>nativeRequest('device_permissions'),'nav-link','settings-2'),workMode);
  footer.insertBefore(button('界面配置',()=>nativeRequest('appearance'),'nav-link','sparkles'),nativeSettings);
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
  const menuActions=[['工作信息','panel-right',()=>showInfo()],['上下文与压缩','layers',()=>showInfo('context')],['提示词','notebook-pen',()=>showInfo('prompt')],['修改审阅','git-pull-request',()=>nativeRequest('review')],['运行详情与诊断','terminal',()=>nativeRequest('diagnostics')],['环境安装设置','terminal',()=>nativeRequest('environment')],['界面配置','sparkles',()=>nativeRequest('appearance')],['模型与上下文设置','settings-2',()=>nativeRequest('settings')],['切换工作模式','arrow-left-right',()=>nativeRequest('work_mode')]];
  for(const [label,symbol,action]of menuActions)menu.append(button(label,action,'menu-action',symbol));
  function applyLayout(next){
    const value=next.uiLayout==='classic'?'classic':'chat';if(value===layout)return;
    closePanel();layout=value;root.dataset.uiLayout=value;
    if(value==='chat'){
      header.replaceChildren(menuButton,model,thinking,topStop,fresh);
      inputRow.insertBefore(attach,input);attach.replaceChildren(icon('plus'));
      sidebarControls.replaceChildren(engineSelect,mode,longTask,collaboration);
      sidebar.insertBefore(sidebarControls,workspaceNav);sidebar.insertBefore(menu,footer);
      fresh.replaceChildren(icon('notebook-pen'));input.placeholder='询问 '+profile.label+'…';
    }else{
      header.replaceChildren(menuButton,engineLabel,topTitle,logs,fresh,more);
      controls.replaceChildren(attach,mode,model,thinking,longTask,collaboration);sidebarControls.remove();menu.remove();
      attach.replaceChildren(icon('paperclip'));fresh.replaceChildren(icon('plus'));input.placeholder=profile.placeholder;
    }
    restorePanels();requestAnimationFrame(()=>{positionJump();syncCodexCanvas(root);});
  }
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
    state=next;root.dataset.reducedMotion=String(!!state.reducedMotion);applyLayout(state);applyCodexAppearance(root,state);
    if(!quick.hidden){
      if(state.busy||!state.session)closeQuickPicker();
      else {const focused=quickBody.contains(document.activeElement)?document.activeElement?.dataset.value:null;quickBody.replaceChildren(selection(quickMode));positionQuickPicker();if(focused)Array.from(quickBody.querySelectorAll('[data-value]')).find(node=>node.dataset.value===focused)?.focus();}
    }
    if(!routePage.hidden&&drawerMode==='thinking')routeBody.replaceChildren(selection(drawerMode));
    bubble.update();const child=childNav.update();const displayMessages=child?child.messages||[]:state.messages||[];
    const empty=!displayMessages.length;root.dataset.empty=String(empty);root.dataset.busy=String(!!state.busy);
    welcome.hidden=!empty||!!child;messages.hidden=empty;topTitle.textContent=child?.label||state.title||'新会话';
    engineSelect.querySelector('.button-label').textContent=profile.label;workspaceName.textContent=state.workspaceName||'选择工作区';projectName.textContent=state.workspaceName||'选择工作区';
    welcomeWorkspace.textContent=state.workspaceName||'';workspace.title=state.workspaceName||'选择工作区';
    const selectedThinking=(state.thinkingChoices||[]).find(item=>item.id===(state.thinkingLevel||'auto'));
    const thinkingLabel=state.engine==='codex'?(state.thinkingLevel==='ultra'?'Ultra':String(state.thinkingLevel||'auto').toLowerCase()):(state.thinkingLabel||selectedThinking?.label||'自动');thinking.querySelector('.button-label').textContent=thinkingLabel;
    thinking.dataset.engine=state.engine||'';thinking.dataset.ultra=String(state.engine==='codex'&&state.thinkingLevel==='ultra');
    thinking.dataset.explicit=String((state.thinkingLevel||'auto')!=='auto');
    thinking.title='思考等级 · '+thinkingLabel;thinking.setAttribute('aria-label',thinking.title);
    collaboration.title='子代理协作模式：'+(collaborationChoices.find(c=>c.id===(state.collaborationMode||'assist'))?.label||'辅助');
    collaboration.setAttribute('aria-label',collaboration.title);
    collaboration.disabled=!!state.busy||!state.session;
    longTask.setAttribute('aria-pressed',String(!!state.longTaskMode));
    longTask.title='长任务模式：'+(state.longTaskMode?'开启 · 无人守候':'关闭 · 优先提问')+'；长按查看说明';
    longTask.setAttribute('aria-label',longTask.title);
    longTask.setAttribute('aria-disabled',String(!!state.busy||!state.session));
    if(!state.session)clearLongTaskPress();
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
    statusText.textContent=state.approval?'等待命令审批':state.stopping?'正在停止…':state.busy?(state.running?state.status:'处理中…'):state.status;
    statusLine.hidden=!state.session||(!state.busy&&(state.status==='准备就绪'||!state.status));
    statusDot.classList.toggle('active',!!state.busy);mode.querySelector('.button-label').textContent=modeChoices.find(item=>item.id===state.mode)?.label||'执行';modelText.textContent=state.model||'配置模型';model.title=state.model||'配置模型';
    if(state.busy&&['engine','mode','thinking'].includes(drawerMode))closePanel();
    for(const button of rail.querySelectorAll('button'))button.disabled=(!!state.busy||!state.session)&&['新会话','上下文文件','模型设置'].includes(button.getAttribute('aria-label'));
    for(const node of [engineSelect,fresh,workspace,project,newNav,workspaceNav,mode,model,thinking,attach,nativeSettings,workMode,review,pickFiles,savePrompt,directoryAction,connect,contextSettings])node.disabled=!!state.busy||!state.session;
    review.disabled=review.disabled||!(state.changes||[]).length;changes.hidden=!(state.changes||[]).length;
    stop.hidden=!state.busy;submit.hidden=!!state.busy;stop.disabled=!!state.stopping;topStop.hidden=!state.busy||layout!=='chat';topStop.disabled=!!state.stopping;
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
    const messageScope=JSON.stringify([state.session||'',child?.id||'',state.engine||'']);
    if(renderMessages(messageScope,displayMessages,child?.label||profile.label)){
      if(follow)requestAnimationFrame(()=>{viewport.scrollTop=viewport.scrollHeight;childNav.restoreScroll();});else jump.hidden=false;
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
  for(const kind of ['engine','mode'])selection(kind);
  renderTabs();restorePanels();
  const resized=()=>{syncCodexCanvas(root);if(layout!=='chat'&&panelIsOpen()&&['history','info'].includes(drawerMode)&&wide.matches)closePanel();restorePanels();};
  for(const query of [wide,dual]){if(query.addEventListener)query.addEventListener('change',resized);else query.addListener(resized);}
  const visibility=()=>{if(document.hidden)flushDraft();};document.addEventListener('visibilitychange',visibility);
  return {root,update,closePanel:()=>{if(bubble.close())return true;if(closeQuickPicker())return true;if(panelIsOpen()){closePanel();return true;}const closed=childNav.close();panelChanged();return closed;},dispose(){closeQuickPicker(false);document.removeEventListener('pointerdown',quickOutside);window.removeEventListener('resize',positionQuickPicker);document.removeEventListener('scroll',positionQuickPicker,true);quick.remove();bubble.dispose();childNav.dispose();clearLongTaskPress();flushDraft();clearTimeout(draftTimer);composerObserver?.disconnect();window.removeEventListener('resize',positionJump);for(const query of [wide,dual]){if(query.removeEventListener)query.removeEventListener('change',resized);else query.removeListener(resized);}document.removeEventListener('visibilitychange',visibility);document.removeEventListener('keydown',escapePanel);closePanel();}};
}
