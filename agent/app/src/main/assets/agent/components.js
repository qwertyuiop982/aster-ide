import {tr,uiLocale} from './locale.js';
import {createMessageRenderer} from './message-renderer.js';
import {thinkingBubble,subagentNavigator} from './bubbles.js';
import {applyCodexAppearance,syncCodexCanvas} from './appearance.js';
import {el,icon,button,iconButton,section,richText} from './dom.js';
import {choiceList} from './choices.js';
import {engineArtwork,drawBackdrop} from './patterns.js';

const quickTitles={model:tr('选择模型','Choose model'),collaboration:tr('子代理协作模式','Subagent collaboration'),engine:tr('底层引擎','Agent engine'),mode:tr('操作权限','Task mode'),attachment:tr('添加附件','Add attachments')};
const setupLabels={
  WORKSPACE:tr('准备工作区与会话','Preparing workspace and conversation'),
  TERMUX:tr('准备内置 Termux','Preparing the built-in Termux runtime'),
  NODE:tr('安装 Agent 运行环境','Installing the Agent runtime'),
  BRIDGE:tr('启动 Python 工具桥接','Starting the Python tool bridge'),
  ENGINES:tr('检查底层引擎','Checking agent engines'),
  READY:tr('本地引擎检查通过（未测试模型接口）','Local engines verified. Model access has yet to be checked.'),
  FAILED:tr('环境准备失败','Environment setup failed'),
  CANCELLED:tr('已停止环境准备','Environment setup stopped')
};
const conversationTitle=title=>!title||title==='新会话'?tr('新会话','New conversation'):title;

/** Shared real-state controls; each engine owns its layout profile. */
export function createPage(profile,send,local,toolExpansion) {
  const preparedSelections=new Map();let bubble,childNav;
  let state={},draftTimer,follow=true,lastHistory='',lastTasks='',lastChanges='',lastFiles='',lastAck,lastPrompt;
  let drawerMode='',previousFocus=null,infoTab='overview',navPinned=profile.navigation,infoPinned=profile.inspector,layout='';
  const wide=matchMedia('(min-width: 1080px) and (min-height: 520px) and (orientation: landscape)');
  const dual=matchMedia('(min-width: 1360px) and (min-height: 520px) and (orientation: landscape)');
  const root=el('main','agent-layout '+profile.id);root.dataset.empty='true';
  const sidebarHost=el('aside','sidebar-host'),main=el('section','main-column'),infoHost=el('aside','info-host');
  const backdrop=el('canvas','conversation-backdrop');backdrop.setAttribute('aria-hidden','true');main.append(backdrop);
  function paintBackdrop(){if(root.isConnected)drawBackdrop(backdrop,root,state.codexAppearance);}
  const backdropObserver=typeof ResizeObserver==='function'?new ResizeObserver(paintBackdrop):null;
  backdropObserver?.observe(main);
  const modal=el('aside','utility-dialog');modal.hidden=true;modal.setAttribute('aria-label',tr('会话侧边栏','Conversation sidebar'));modal.setAttribute('aria-hidden','true');
  const modalBar=el('header','dialog-bar'),modalTitle=el('h2');
  const sidebarBack=iconButton(tr('返回侧边栏','Back to sidebar'),'chevron-left',()=>openPanel('history'));sidebarBack.hidden=true;
  modalBar.append(sidebarBack,modalTitle,iconButton(tr('关闭侧边栏','Close sidebar'),'x',closePanel));
  const modalBody=el('div','dialog-body');modal.append(modalBar,modalBody);
  const routePage=el('section','route-page');routePage.hidden=true;routePage.setAttribute('aria-label',tr('Agent 配置页面','Agent settings'));routePage.id='agent-config-page';routePage.tabIndex=-1;
  const routeBar=el('header','route-bar'),routeTitle=el('h1');routeBar.append(iconButton(tr('返回对话','Back to conversation'),'arrow-left',closePanel),routeTitle);
  const routeBody=el('div','route-body');routePage.append(routeBar,routeBody);
  const quick=el('section','quick-picker');quick.hidden=true;quick.id='agent-quick-picker';quick.setAttribute('role','dialog');quick.setAttribute('aria-labelledby','quick-picker-title');
  const quickBar=el('header','quick-picker-bar'),quickTitle=el('h2');quickTitle.id='quick-picker-title';
  quickBar.append(quickTitle,iconButton(tr('关闭选项','Close options'),'x',()=>closeQuickPicker()));
  const quickBody=el('div','quick-picker-body');quick.append(quickBar,quickBody);
  let quickMode='',quickAnchor=null,quickFocus=null;
  const shade=el('div','dialog-shade');shade.hidden=true;
  const rail=el('nav','navigation-rail');rail.setAttribute('aria-label',tr('Agent 导航','Agent navigation'));
  const railBrand=el('div','rail-brand');railBrand.append(icon(profile.mark));
  rail.append(railBrand,iconButton(tr('会话历史','Conversation history'),'history',toggleHistory),iconButton(tr('新会话','New conversation'),'plus',()=>request('new')),
    iconButton(tr('工作信息','Task details'),'panel-right',()=>showInfo()),iconButton(tr('上下文文件','Context files'),'paperclip',()=>nativeRequest('context_files')),
    el('span','rail-spacer'),iconButton(tr('模型设置','Model settings'),'settings-2',()=>nativeRequest('settings')));
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
    for(const node of root.querySelectorAll('[data-picker],.thinking-picker'))node.setAttribute('aria-expanded',String(node===thinking?!!bubble?.isOpen():node.dataset.picker in quickTitles?!quick.hidden&&node.dataset.picker===quickMode:panelIsOpen()&&node.dataset.picker===drawerMode));
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
    const top=quickMode==='attachment'&&above>=pad?above:below+height<=innerHeight-pad?below:above>=pad?above:Math.max(pad,Math.min(innerHeight-height-pad,below));
    quick.style.left=Math.max(pad,Math.min(innerWidth-width-pad,anchor.left))+'px';
    quick.style.top=top+'px';syncCodexCanvas(root);
  }
  function openQuickPicker(kind,anchor){
    if(state.busy||!state.session)return;
    if(!quick.hidden&&quickMode===kind){closeQuickPicker();return;}
    bubble?.close(true);closeQuickPicker(false);
    if(!routePage.hidden)closePanel();
    quickMode=kind;quickAnchor=anchor;quickFocus=anchor||document.activeElement;
    quick.dataset.kind=kind;quickTitle.textContent=quickTitles[kind];
    quickBody.replaceChildren(selection(kind));quick.hidden=false;positionQuickPicker();panelChanged();
    requestAnimationFrame(()=>{if(quick.hidden||quickMode!==kind)return;positionQuickPicker();quickBody.querySelector('[aria-selected=true]:not(:disabled),button:not(:disabled)')?.focus();});
  }
  const quickOutside=event=>{if(!quick.hidden&&!quick.contains(event.target)&&!quickAnchor?.contains(event.target))closeQuickPicker(false);};
  document.addEventListener('pointerdown',quickOutside);
  window.addEventListener('resize',positionQuickPicker);
  document.addEventListener('scroll',positionQuickPicker,true);
  document.addEventListener('keydown',escapePanel);
  function openPanel(kind,anchor){
    if(kind in quickTitles){openQuickPicker(kind,anchor);return;}
    closeQuickPicker(false);
    if(layout==='chat'&&kind==='menu')kind='history';
    bubble?.close(true);
    if(panelIsOpen())closePanel();
    previousFocus=document.activeElement;drawerMode=kind;
    modal.dataset.kind=kind;modal.dataset.side=String(layout==='chat');sidebarBack.hidden=layout!=='chat'||kind==='history';
    modalTitle.textContent=({history:tr('会话与工作区','Conversations and workspaces'),menu:tr('会话选项','Conversation options')})[kind]||'';
    if(kind==='history'||kind==='menu'){
      modalBody.replaceChildren(kind==='history'?sidebar:menu);
      routePage.hidden=true;modal.hidden=false;modal.setAttribute('aria-hidden','false');menuButton?.setAttribute('aria-expanded','true');
      requestAnimationFrame(()=>modalBody.querySelector('button:not(:disabled),input:not(:disabled)')?.focus());
    }else{
      modal.hidden=true;modal.setAttribute('aria-hidden','true');routePage.hidden=false;
      routeTitle.textContent=({engine:tr('底层引擎','Agent engine'),mode:tr('操作权限','Task mode'),thinking:tr('思考等级','Reasoning level'),info:tr('工作信息','Task details')})[kind]||tr('设置','Settings');
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
  const menuButton=iconButton(tr('打开会话历史','Open conversation history'),'menu',toggleHistory);
  function picker(label,kind,symbol){
    const node=button(label,event=>openPanel(kind,event.currentTarget),kind+'-picker picker-button',symbol);node.dataset.picker=kind;
    node.setAttribute('aria-haspopup','dialog');node.setAttribute('aria-expanded','false');node.setAttribute('aria-controls','agent-quick-picker');
    node.append(icon('chevron-down'));return node;
  }
  const engineSelect=picker(profile.label,'engine',profile.mark);engineSelect.setAttribute('aria-label',tr('底层引擎','Agent engine'));
  const engineLabel=engineSelect;
  const engineChoices=[{id:'codex',label:'Codex',icon:'code-xml'},{id:'openclaude',label:'OpenClaude',icon:'message-square'},{id:'gemini',label:'Gemini CLI',icon:'sparkles'},{id:'pi',label:'Pi',icon:'terminal'}];
  const modeChoices=[{id:'ask',label:tr('问答','Ask'),detail:tr('只读','Read only'),icon:'message-square'},{id:'plan',label:tr('规划','Plan'),detail:tr('只读 · 任务清单','Read only · task list'),icon:'list-checks'},{id:'execute',label:tr('执行','Execute'),detail:tr('暂存修改 · 命令需审批','Stage edits · commands need approval'),icon:'wrench'}];
  const collaborationChoices=[{id:'extreme',label:tr('极端','Extreme'),detail:tr('配合 Ultra · 主代理首批派发，子代理递归协作','Use with Ultra · subagents may delegate further'),icon:'sparkles'},{id:'team',label:tr('团队','Team'),detail:tr('主代理只编排整合，全部执行委派子代理','The main agent coordinates and delegates execution'),icon:'layers'},{id:'assist',label:tr('辅助','Assist'),detail:tr('根据任务大小适当委派','Delegate based on task size'),icon:'sliders-horizontal'},{id:'off',label:tr('关闭','Off'),detail:tr('不注入协作提示词，代理工具保留','Keep agent tools available without collaboration prompts'),icon:'circle'}];
  const attachmentOptions=[
    [tr('图片','Images'),'image','pick_images'],
    [tr('文件','Files'),'file','pick_documents'],
    [tr('工作区文件','Workspace files'),'folder','context_files']
  ];
  function attachmentSelection(){
    const key=String(state.imagesSupported!==false),cached=preparedSelections.get('attachment');
    if(cached?.key===key)return cached.node;
    const node=el('div','attachment-options');
    for(const [label,symbol,command] of attachmentOptions){
      const item=button(label,()=>nativeRequest(command),'attachment-option',symbol);
      item.dataset.value=command;
      if(command==='pick_images'&&state.imagesSupported===false){
        item.disabled=true;item.title=tr('当前模型通道不支持图片','This model connection does not support images');item.setAttribute('aria-label',label+tr('：当前模型通道不支持',': unsupported by this model connection'));
      }
      node.append(item);
    }
    preparedSelections.set('attachment',{key,node});
    return node;
  }
  function selection(kind){
    if(kind==='attachment')return attachmentSelection();
    const choices=kind==='model'?(state.models||[]).map(id=>({id,label:id,detail:id===state.model?tr('当前模型','Current model'):tr('下一次任务使用','Used for the next task')})):kind==='collaboration'?collaborationChoices:kind==='engine'?engineChoices.map(item=>{const availability=(state.engines||[]).find(engine=>engine.id===item.id);return {...item,detail:!availability?.checked?tr('尚未检查','Not checked'):availability.available?tr('本地资源可用','Local resources available'):availability.reason||tr('未安装','Not installed')};}):kind==='mode'?modeChoices:state.thinkingChoices||[{id:'auto',label:tr('自动','Auto'),detail:tr('引擎默认','Engine default')}];
    const value=kind==='model'?state.model:kind==='collaboration'?(state.collaborationMode||'assist'):kind==='engine'?state.engine:kind==='mode'?state.mode:state.thinkingLevel||'auto';
    const cacheKey=JSON.stringify([choices,value,state.thinkingNote||'',state.modelSelectable]);
    const cached=preparedSelections.get(kind);
    if(cached?.key===cacheKey)return cached.node;
    const container=el('div','selection-content');
    if(kind!=='model'||state.modelSelectable!==false)container.append(choiceList(({model:tr('选择模型','Choose model'),collaboration:tr('子代理协作模式','Subagent collaboration'),engine:tr('底层引擎','Agent engine'),mode:tr('操作权限','Task mode'),thinking:tr('思考等级','Reasoning level')})[kind],choices,value,id=>{
      if(state.busy)return;if(['model','engine','mode','collaboration'].includes(kind))closeQuickPicker();else closePanel();request(kind==='thinking'?'thinking':kind,kind==='model'?{text:id}:{id});
    }));
    if(kind==='model'){
      if(state.modelSelectable===false)container.append(el('p','selection-note',tr('免费服务的模型由账号方案决定。','The account plan selects the model for the free service.')));
      else if(!choices.length)container.append(el('p','selection-note',tr('尚未配置模型。支持逗号分隔，也可以从云端获取列表。','Add models separated by commas, or fetch the list from your provider.')));
      container.append(button(tr('管理 API / 获取云端模型','Manage API and fetch models'),()=>nativeRequest('settings'),'menu-action','settings-2'));
    }
    if(kind==='thinking'&&['gemini','openclaude'].includes(state.engine))container.replaceChildren(button(tr('思考预算 / 思考强度 / 手动填入','Set a reasoning level or budget'),()=>nativeRequest('settings'),'menu-action','sliders-horizontal'));
    if(kind==='thinking'&&state.thinkingNote)container.append(el('p','selection-note',state.thinkingNote));
    preparedSelections.set(kind,{key:cacheKey,node:container});
    return container;
  }
  const topTitle=el('span','top-title');
  const fresh=iconButton(tr('新会话','New conversation'),'plus',()=>request('new'));
  const more=iconButton(tr('会话选项','Conversation options'),'ellipsis',()=>openPanel('menu'));
  const logs=button(tr('日志','Logs'),()=>request('diagnostics'),'icon-button','terminal');
  const topStop=iconButton(tr('停止任务','Stop task'),'square',()=>request('stop'),'top-stop');topStop.hidden=true;
  header.append(menuButton,engineLabel,topTitle,logs,fresh,more);
  const workspaceBar=el('div','workspace-bar');
  const workspace=button(tr('工作区','Workspace'),()=>nativeRequest('workspaces'),'workspace-button','folder');
  const workspaceName=workspace.querySelector('span');
  const workInfo=button(tr('工作信息','Task details'),()=>showInfo(),'work-info','panel-right');
  const changesLink=button(tr('改动','Changes'),()=>nativeRequest('review'),'changes-link','git-pull-request');
  const contextBadge=button(tr('上下文','Context'),()=>showInfo('context'),'context-badge','layers');
  workspaceBar.append(workspace,contextBadge,workInfo,changesLink);
  const alert=el('div','notice');alert.setAttribute('role','status');const alertText=el('span');
  const retry=button(tr('连接环境','Connect runtime'),()=>request('connect'),'text-button');alert.append(alertText,retry);alert.hidden=true;
  const viewport=el('section','conversation-viewport');viewport.setAttribute('aria-label',tr('对话记录','Conversation'));
  const welcome=el('div','welcome');if(profile.id!=='codex')welcome.append(engineArtwork(profile));welcome.append(el('h1','',profile.greeting));
  if(profile.starters.length){const starters=el('div','task-starters');for(const [label,symbol,text] of profile.starters){starters.append(button(label,()=>{if(!input.value.trim()){input.value=text;saveDraft();fitInput();}input.focus();},'starter',symbol));}welcome.append(starters);}
  const welcomeWorkspace=el('p','welcome-workspace');welcome.append(welcomeWorkspace);
  const messages=el('div','messages');
  const renderMessages=createMessageRenderer(messages,request,undefined,toolExpansion);
  viewport.append(welcome,messages);
  const jump=button(tr('回到最新','Jump to latest'),()=>{follow=true;viewport.scrollTop=viewport.scrollHeight;jump.hidden=true;},'jump-latest','arrow-down');jump.hidden=true;
  viewport.addEventListener('scroll',()=>{follow=viewport.scrollHeight-viewport.scrollTop-viewport.clientHeight<72;jump.hidden=follow;},{passive:true});
  const composerArea=el('div','composer-area');
  const statusLine=el('div','status-line');statusLine.setAttribute('role','status');
  const statusDot=el('span','status-dot'),statusText=el('span');statusLine.append(statusDot,statusText);
  const composer=el('form','composer');
  const project=button(tr('选择工作区','Choose workspace'),()=>nativeRequest('workspaces'),'project-strip','folder');const projectName=project.querySelector('span');project.append(icon('chevron-down'));project.hidden=true;
  const chips=el('div','context-chips');chips.hidden=true;
  const input=el('textarea','task-input');input.rows=1;input.maxLength=12000;input.placeholder=profile.placeholder;input.setAttribute('aria-label',tr('任务描述','Task description'));input.value=local.text||'';
  function fitInput(){
    input.rows=Math.min(5,Math.max(1,input.value.split('\n').length));
    const available=availability();
    submit.disabled=!!state.busy||!state.session||!(input.value.trim()||(state.attachments||[]).length)||(available?.checked===true&&available.available===false);
  }
  input.addEventListener('input',()=>{saveDraft();fitInput();});
  input.addEventListener('blur',flushDraft);
  input.addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();if(composer.requestSubmit)composer.requestSubmit();else submit.click();}});
  const controls=el('div','composer-controls');
  const attach=iconButton(tr('添加附件','Add attachments'),'plus',event=>openQuickPicker('attachment',event.currentTarget),'context-picker');
  attach.dataset.picker='attachment';attach.setAttribute('aria-haspopup','dialog');attach.setAttribute('aria-expanded','false');attach.setAttribute('aria-controls',quick.id);
  const mode=picker(tr('执行','Execute'),'mode');mode.setAttribute('aria-label',tr('操作权限','Task mode'));const modeLabel=mode;
  const model=picker(tr('配置模型','Configure model'),'model');model.classList.add('model-selector');const modelText=model.querySelector('span');model.setAttribute('aria-label',tr('选择模型','Choose model'));
  const thinking=button(tr('自动','Auto'),()=>{},'thinking-picker picker-button','sliders-horizontal');thinking.setAttribute('aria-haspopup','dialog');thinking.setAttribute('aria-expanded','false');thinking.setAttribute('aria-label',tr('思考等级','Reasoning level'));thinking.title=tr('思考等级','Reasoning level');
  let longTaskTimer,longTaskPointer=null,longTaskHeld=false,longTaskOrigin;
  const longTask=iconButton(tr('长任务模式：关闭；长按查看说明','Long task mode: off. Long press for details'),'timer',event=>{
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
  const collaboration=picker(tr('协作','Collaboration'),'collaboration','layers');
  const submit=iconButton(tr('发送任务','Send'),'arrow-up',()=>{},'send-button');submit.type='submit';submit.disabled=true;
  const stop=iconButton(tr('停止任务','Stop task'),'square',()=>request('stop'),'send-button stop-button');stop.hidden=true;
  controls.append(attach,modeLabel,model,thinking,longTask,collaboration);
  const inputRow=el('div','composer-input-row'),sendLane=el('div','send-lane');sendLane.append(submit,stop);inputRow.append(input,sendLane);
  composer.append(project,chips,inputRow,controls);composerArea.append(statusLine,composer);
  composer.addEventListener('submit',e=>{e.preventDefault();if(!submit.disabled)request('send',{text:input.value});});
  main.append(header,workspaceBar,alert,viewport,jump,composerArea);
  bubble=thinkingBubble(root,thinking,()=>state,request,()=>panelChanged());
  childNav=subagentNavigator(root,main,viewport,composerArea,()=>state,()=>{follow=true;update(state);panelChanged();},request,panelChanged);
  const sidebar=el('div','sidebar-content');
  sidebar.append(el('div','sidebar-brand',profile.id==='pi'?'Pi':'Aster'));
  const newNav=button(tr('新会话','New conversation'),()=>{closePanel();request('new');},'new-conversation','plus');
  const workspaceNav=button(tr('工作区','Workspace'),()=>{closePanel();request('workspaces');},'nav-link','folder');
  const promptNav=button(tr('提示词','Instructions'),()=>showInfo('prompt'),'nav-link','notebook-pen');
  const search=el('input','history-search');search.placeholder=tr('搜索会话','Search conversations');search.setAttribute('aria-label',tr('搜索会话','Search conversations'));search.type='search';
  const history=el('div','history-list');
  search.addEventListener('input',()=>renderHistory(true));
  const sidebarControls=el('div','sidebar-controls');
  const footer=el('div','sidebar-footer');
  const nativeSettings=button(tr('模型设置','Model settings'),()=>{closePanel();request('settings');},'nav-link','settings-2');
  const workMode=button(tr('切换工作模式','Switch work mode'),()=>{closePanel();request('work_mode');},'nav-link','arrow-left-right');
  footer.append(button(tr('环境安装设置','Environment setup'),()=>{closePanel();request('environment');},'nav-link','terminal'),nativeSettings,button(tr('浏览器','Browser'),()=>nativeRequest('browser'),'nav-link','layers'),button(tr('设备权限','Device permissions'),()=>nativeRequest('device_permissions'),'nav-link','settings-2'),workMode);
  footer.insertBefore(button(tr('界面配置','Appearance'),()=>nativeRequest('appearance'),'nav-link','sparkles'),nativeSettings);
  sidebar.append(newNav,workspaceNav,promptNav,el('h2','sidebar-heading',tr('最近会话','Recent conversations')),search,history,footer);
  const inspector=el('div','inspector-content');
  const sessionFacts=section(tr('当前会话','Current conversation'),'session-facts'),modelFact=el('p','session-model'),thinkingFact=el('p','small-text');
  sessionFacts.append(modelFact,thinkingFact);
  const inspectorHeader=el('header','inspector-header');inspectorHeader.append(el('h2','',tr('工作信息','Task details')),iconButton(tr('收起工作信息','Hide task details'),'panel-right',()=>{infoPinned=false;restorePanels();}));
  const tabs=el('div','info-tabs');tabs.setAttribute('role','tablist');
  const tabNodes={},tabContent={};
  for(const [key,label] of [['overview',tr('进度','Progress')],['context',tr('上下文','Context')],['prompt',tr('提示词','Instructions')]]){
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
  const tasks=section(tr('任务进度','Task progress'),'tasks-section'),taskList=el('ul','task-list');tasks.append(taskList);
  const directory=section(tr('工作目录','Working directory'));const directoryAction=button(tr('工作区','Workspace'),()=>request('workspaces'),'directory-action','folder');directory.append(directoryAction);
  const changes=section(tr('修改审阅','Review changes')),changeList=el('div','change-list');
  const review=button(tr('审阅修改','Review changes'),()=>request('review'),'outline-button','git-pull-request');changes.append(changeList,review);
  const contextOverview=section(tr('上下文','Context'),'context-overview'),overviewFiles=el('div');contextOverview.append(overviewFiles,button(tr('查看上下文','View context'),()=>showInfo('context'),'outline-button','layers'));
  tabContent.overview.append(sessionFacts,tasks,directory,contextOverview,changes);
  const context=section(tr('上下文用量','Context usage')),usage=el('p','context-usage'),meter=el('progress');meter.max=1;meter.setAttribute('aria-label',tr('上下文用量','Context usage'));
  const compactions=el('p','small-text'),configuredWindow=el('p','small-text context-configured'),measurement=el('p','small-text context-measurement'),policyNote=el('p','small-text context-policy');
  const contextSettings=button(tr('调整上下文窗口','Set context window'),()=>request('settings'),'outline-button','settings-2');
  const compact=button(tr('压缩上下文','Compact context'),()=>request('compact'),'outline-button','layers');
  const compactNote=el('p','small-text');context.append(usage,meter,configuredWindow,measurement,policyNote,contextSettings,compactions,compact,compactNote);
  const files=section(tr('已选择的文件','Selected files')),fileList=el('div','context-files'),pickFiles=button(tr('选择文件','Select files'),()=>request('context_files'),'outline-button','paperclip');files.append(fileList,pickFiles);
  const runtime=section(tr('运行环境','Runtime')),runtimeText=el('p','small-text'),connect=button(tr('连接环境','Connect runtime'),()=>request('connect'),'outline-button','terminal');runtime.append(runtimeText,connect,button(tr('运行详情与诊断','Runtime diagnostics'),()=>request('diagnostics'),'outline-button','terminal'));
  tabContent.context.append(context,files,runtime);
  const prompt=el('textarea','prompt-input');prompt.rows=9;prompt.maxLength=8000;prompt.setAttribute('aria-label',tr('工作指令','Workspace instructions'));
  const savePrompt=button(tr('保存提示词','Save instructions'),()=>request('prompt',{text:prompt.value}),'primary-button');
  tabContent.prompt.append(el('p','small-text',tr('这些指令会随工作区任务发送给当前引擎。','These instructions are sent to the current engine with workspace tasks.')),prompt,savePrompt);
  inspector.append(inspectorHeader,tabs,...Object.values(tabContent));
  const menu=el('div','action-menu');
  const menuActions=[[tr('工作信息','Task details'),'panel-right',()=>showInfo()],[tr('上下文与压缩','Context and compaction'),'layers',()=>showInfo('context')],[tr('提示词','Instructions'),'notebook-pen',()=>showInfo('prompt')],[tr('修改审阅','Review changes'),'git-pull-request',()=>nativeRequest('review')],[tr('运行详情与诊断','Runtime diagnostics'),'terminal',()=>nativeRequest('diagnostics')],[tr('环境安装设置','Environment setup'),'terminal',()=>nativeRequest('environment')],[tr('界面配置','Appearance'),'sparkles',()=>nativeRequest('appearance')],[tr('模型与上下文设置','Model and context settings'),'settings-2',()=>nativeRequest('settings')],[tr('切换工作模式','Switch work mode'),'arrow-left-right',()=>nativeRequest('work_mode')]];
  for(const [label,symbol,action]of menuActions)menu.append(button(label,action,'menu-action',symbol));
  function applyLayout(next){
    const value=next.uiLayout==='classic'?'classic':'chat';if(value===layout)return;
    closePanel();layout=value;root.dataset.uiLayout=value;
    if(value==='chat'){
      header.replaceChildren(menuButton,model,thinking,topStop,fresh);
      inputRow.insertBefore(attach,input);
      sidebarControls.replaceChildren(engineSelect,mode,longTask,collaboration);
      sidebar.insertBefore(sidebarControls,workspaceNav);sidebar.insertBefore(menu,footer);
      fresh.replaceChildren(icon('notebook-pen'));input.placeholder=tr('询问 ','Ask ')+profile.label+'…';
    }else{
      header.replaceChildren(menuButton,engineLabel,topTitle,logs,fresh,more);
      controls.replaceChildren(attach,mode,model,thinking,longTask,collaboration);sidebarControls.remove();menu.remove();
      fresh.replaceChildren(icon('plus'));input.placeholder=profile.placeholder;
    }
    restorePanels();requestAnimationFrame(()=>{positionJump();syncCodexCanvas(root);});
  }
  function availability(){return(state.engines||[]).find(e=>e.id===state.engine);}
  function renderHistory(force=false){
    const key=JSON.stringify(state.history||[]);if(!force&&key===lastHistory)return;lastHistory=key;
    history.replaceChildren();const query=search.value.trim().toLocaleLowerCase();
    const entries=(state.history||[]).filter(e=>e.title.toLocaleLowerCase().includes(query));
    for(const entry of entries){
      const title=conversationTitle(entry.title);
      const row=el('div','history-row');const b=button(title,()=>{closePanel();request('history',{id:entry.id});},'history-link','message-square');
      b.dataset.session=entry.id;b.title=title;
      const remove=iconButton(tr('删除会话 ','Delete conversation ')+title,'trash-2',()=>request('delete',{id:entry.id}),'history-delete');
      b.disabled=!!state.busy;remove.disabled=!!state.busy;row.append(b,remove);history.append(row);
    }
    if(!entries.length)history.append(el('p','empty-note',query?tr('没有匹配的会话','No matching conversations'):tr('暂无历史会话','No conversation history')));
  }
  function update(next){
    state=next;root.dataset.reducedMotion=String(!!state.reducedMotion);applyLayout(state);applyCodexAppearance(root,state);
    paintBackdrop();
    if(!quick.hidden){
      if(state.busy||!state.session)closeQuickPicker();
      else {
        const content=selection(quickMode);
        if(quickBody.firstChild!==content){
          const focused=quickBody.contains(document.activeElement)?document.activeElement?.dataset.value:null;
          quickBody.replaceChildren(content);if(focused)Array.from(quickBody.querySelectorAll('[data-value]')).find(node=>node.dataset.value===focused)?.focus();
        }
        positionQuickPicker();
      }
    }
    if(!routePage.hidden&&drawerMode==='thinking')routeBody.replaceChildren(selection(drawerMode));
    bubble.update();const child=childNav.update();const displayMessages=child?child.messages||[]:state.messages||[];
    const empty=!displayMessages.length;root.dataset.empty=String(empty);root.dataset.busy=String(!!state.busy);
    welcome.hidden=!empty||!!child;messages.hidden=empty;topTitle.textContent=child?.label||conversationTitle(state.title);
    engineSelect.querySelector('.button-label').textContent=profile.label;workspaceName.textContent=state.workspaceName||tr('选择工作区','Choose workspace');projectName.textContent=state.workspaceName||tr('选择工作区','Choose workspace');
    welcomeWorkspace.textContent=state.workspaceName||'';workspace.title=state.workspaceName||tr('选择工作区','Choose workspace');
    const selectedThinking=(state.thinkingChoices||[]).find(item=>item.id===(state.thinkingLevel||'auto'));
    const thinkingLabel=state.engine==='codex'?(state.thinkingLevel==='ultra'?'Ultra':String(state.thinkingLevel||'auto').toLowerCase()):(state.thinkingLabel||selectedThinking?.label||tr('自动','Auto'));thinking.querySelector('.button-label').textContent=thinkingLabel;
    thinking.dataset.engine=state.engine||'';thinking.dataset.ultra=String(state.engine==='codex'&&state.thinkingLevel==='ultra');
    thinking.dataset.explicit=String((state.thinkingLevel||'auto')!=='auto');
    thinking.title=tr('思考等级 · ','Reasoning level · ')+thinkingLabel;thinking.setAttribute('aria-label',thinking.title);
    collaboration.title=tr('子代理协作模式：','Subagent collaboration: ')+(collaborationChoices.find(c=>c.id===(state.collaborationMode||'assist'))?.label||tr('辅助','Assist'));
    collaboration.setAttribute('aria-label',collaboration.title);
    collaboration.disabled=!!state.busy||!state.session;
    longTask.setAttribute('aria-pressed',String(!!state.longTaskMode));
    longTask.title=tr('长任务模式：','Long task mode: ')+(state.longTaskMode?tr('开启 · 无人守候','on · unattended'):tr('关闭 · 优先提问','off · ask first'))+tr('；长按查看说明','. Long press for details');
    longTask.setAttribute('aria-label',longTask.title);
    longTask.setAttribute('aria-disabled',String(!!state.busy||!state.session));
    if(!state.session)clearLongTaskPress();
    thinkingFact.textContent=tr('思考 · ','Reasoning · ')+thinkingLabel;modelFact.textContent=state.model||tr('未配置模型','No model configured');
    const knownContext=['estimate','hybrid','engine'].includes(state.contextSource);
    contextBadge.querySelector('.button-label').textContent=knownContext?Math.round(100*(state.tokens||0)/Math.max(1,state.window||1))+'%':tr('上下文','Context');
    contextBadge.title=knownContext?tr('最近上下文用量','Latest context usage'):tr('尚无有效用量报告','No usage report yet');
    directoryAction.querySelector('span').textContent=state.workspaceName||tr('尚未选择','Not selected');
    const fileCount=(state.contextFiles||[]).length;
    workInfo.querySelector('span').textContent=tr('工作信息','Task details');
    changesLink.querySelector('span').textContent=tr('改动 ','Changes ')+(state.changes||[]).length;
    changesLink.hidden=!(state.changes||[]).length;workInfo.hidden=false;
    const available=availability();
    const warning=state.saveError||state.runtimeError||(available?.checked&&!available.available?available.reason:'');
    alertText.textContent=warning;alert.hidden=!warning;retry.hidden=!!state.saveError;retry.disabled=!!state.busy;
    statusText.textContent=state.approval?tr('等待命令审批','Waiting for command approval'):state.stopping?tr('正在停止…','Stopping…'):state.busy?(state.running?state.status:tr('处理中…','Working…')):state.status;
    statusLine.hidden=!state.session||(!state.busy&&(state.status==='准备就绪'||!state.status));
    statusDot.classList.toggle('active',!!state.busy);mode.querySelector('.button-label').textContent=modeChoices.find(item=>item.id===state.mode)?.label||tr('执行','Execute');modelText.textContent=state.model||tr('配置模型','Configure model');model.title=state.model||tr('配置模型','Configure model');
    if(state.busy&&['engine','mode','thinking'].includes(drawerMode))closePanel();
    for(const button of rail.querySelectorAll('button'))button.disabled=(!!state.busy||!state.session)&&[tr('新会话','New conversation'),tr('上下文文件','Context files'),tr('模型设置','Model settings')].includes(button.getAttribute('aria-label'));
    for(const node of [engineSelect,fresh,workspace,project,newNav,workspaceNav,mode,model,thinking,attach,nativeSettings,workMode,review,pickFiles,savePrompt,directoryAction,connect,contextSettings])node.disabled=!!state.busy||!state.session;
    review.disabled=review.disabled||!(state.changes||[]).length;changes.hidden=!(state.changes||[]).length;
    stop.hidden=!state.busy;submit.hidden=!!state.busy;stop.disabled=!!state.stopping;topStop.hidden=!state.busy||layout!=='chat';topStop.disabled=!!state.stopping;
    for(const b of menu.querySelectorAll('button'))b.disabled=!!state.busy&&[tr('修改审阅','Review changes'),tr('模型与上下文设置','Model and context settings'),tr('切换工作模式','Switch work mode')].includes(b.textContent);
    if(lastAck!==undefined&&next.acceptance!==lastAck&&input.value.trim()===next.acceptedText){input.value='';local.text='';local.dirty=false;}
    lastAck=next.acceptance;
    fitInput();
    requestAnimationFrame(positionJump);
    if(lastPrompt!==state.prompt){if(document.activeElement!==prompt)prompt.value=state.prompt||'';lastPrompt=state.prompt;}
    const source=state.contextSource||'unknown',known=['estimate','hybrid','engine'].includes(source);
    const labels={estimate:tr('估算用量','Estimated usage'),hybrid:tr('usage + 尾部估算','Reported usage + estimated tail'),engine:tr('引擎报告用量','Reported by engine')};
    usage.textContent=known?labels[source]+tr('：',': ')+(state.tokens||0).toLocaleString(uiLocale())+' / '+(state.window||0).toLocaleString(uiLocale())+' tokens':tr('尚无有效用量报告','No usage report yet');
    meter.hidden=!known;meter.value=Math.min(1,(state.tokens||0)/Math.max(1,state.window||1));
    configuredWindow.textContent=tr('已保存窗口：','Configured window: ')+(state.configuredWindow||state.window||0).toLocaleString(uiLocale())+tr(' tokens · 下一次任务使用',' tokens · applies to the next task');
    measurement.textContent=known?tr('这是最近一次采样，不是实时账单。','Latest sample. Live billing totals may differ.')+(state.contextUpdatedAt?tr(' 更新于 ',' Updated at ')+new Date(state.contextUpdatedAt).toLocaleTimeString(uiLocale()):''):tr('开始任务后显示估算或原生报告；未测量不等于已使用 0 tokens。','Usage appears once a task produces a report.');
    policyNote.textContent=state.contextPolicy||'';
    compactions.textContent=tr('已压缩 ','Compactions: ')+(state.compactions||0)+tr(' 次','');
    compact.disabled=!!state.busy||empty||state.compactSupported===false;
    compactNote.textContent=state.compactSupported===false?tr('此引擎暂不支持手动压缩。','This engine currently does not support manual compaction.'):'';compactNote.hidden=!compactNote.textContent;
    runtimeText.textContent=(state.runtime||tr('环境未连接','Runtime disconnected'))+' · '+(setupLabels[state.setupPhaseId]||state.setupPhase||tr('尚未检查','Not checked'))+tr('\n构建底层：','\nEngine: ')+profile.label+' '+(state.engineVersion||'')+tr('；资源可用不等于模型请求成功。','. Local resources are checked independently of model requests.');
    const messageScope=JSON.stringify([state.workspace||'',state.engine||'',state.session||'',child?.id||'']);
    if(renderMessages(messageScope,displayMessages,child?.label||profile.label)){
      if(follow)requestAnimationFrame(()=>{viewport.scrollTop=viewport.scrollHeight;childNav.restoreScroll();});else jump.hidden=false;
    }
    renderHistory();for(const b of history.querySelectorAll('button')){b.disabled=!!state.busy;if(b.dataset.session)b.setAttribute('aria-current',String(b.dataset.session===state.session));}
    const taskKey=JSON.stringify(state.tasks||[]);if(taskKey!==lastTasks){taskList.replaceChildren();for(const item of state.tasks||[]){const done=['completed','done'].includes(item.status);const running=['in_progress','running','active'].includes(item.status);const li=el('li',done?'done':running?'active':'pending');li.append(icon(done?'check':running?'loader-circle':'circle'),el('span','',item.task));taskList.append(li);}if(!(state.tasks||[]).length)taskList.append(el('li','empty-note',tr('尚未生成任务清单','No tasks yet')));lastTasks=taskKey;}
    const changesKey=JSON.stringify(state.changes||[]);if(changesKey!==lastChanges){changeList.replaceChildren();for(const item of state.changes||[])changeList.append(button(item.path,()=>request('review'),'file-row','file-code-2'));if(!(state.changes||[]).length)changeList.append(el('p','empty-note',tr('暂无待应用修改','No changes to apply')));lastChanges=changesKey;}
    const attachments=state.attachments||[],filesKey=JSON.stringify([state.contextFiles||[],attachments]);
    if(filesKey!==lastFiles){
      chips.replaceChildren();fileList.replaceChildren();overviewFiles.replaceChildren();
      for(const item of attachments){
        const chip=el('div','attachment-chip'),remove=iconButton(tr('移除 ','Remove ')+item.name,'x',()=>request('remove_attachment',{id:item.id}),'attachment-remove');
        chip.title=item.name;chip.append(icon(item.image?'image':'file'),el('span','button-label',item.name),remove);chips.append(chip);
      }
      for(const path of state.contextFiles||[]){
        const item=button(path.split('/').pop(),()=>nativeRequest('context_files'),'file-chip','file-code-2');item.title=path;
        chips.append(item);fileList.append(el('p','file-reference',path));overviewFiles.append(el('p','file-reference',path));
      }
      if(!fileCount){fileList.append(el('p','empty-note',tr('未额外选择文件。Agent 可按当前权限读取工作区。','No extra files selected. The Agent can read the workspace under its current permissions.')));overviewFiles.append(el('p','small-text',tr('使用当前工作区','Using the current workspace')));}
      chips.hidden=!fileCount&&!attachments.length;lastFiles=filesKey;
    }
    for(const item of chips.querySelectorAll('button'))item.disabled=!!state.busy;
  }
  function positionJump(){if(root.isConnected)jump.style.bottom=(composerArea.offsetHeight+12)+'px';}
  const composerObserver=typeof ResizeObserver==='function'?new ResizeObserver(positionJump):null;
  composerObserver?.observe(composerArea);
  window.addEventListener('resize',positionJump);
  window.addEventListener('resize',paintBackdrop);
  input.addEventListener('input',positionJump);
  for(const kind of ['engine','mode'])selection(kind);
  renderTabs();restorePanels();
  const resized=()=>{syncCodexCanvas(root);if(layout!=='chat'&&panelIsOpen()&&['history','info'].includes(drawerMode)&&wide.matches)closePanel();restorePanels();};
  for(const query of [wide,dual]){if(query.addEventListener)query.addEventListener('change',resized);else query.addListener(resized);}
  const visibility=()=>{if(document.hidden)flushDraft();};document.addEventListener('visibilitychange',visibility);
  return {root,update,closePanel:()=>{if(bubble.close())return true;if(closeQuickPicker())return true;if(panelIsOpen()){closePanel();return true;}const closed=childNav.close();panelChanged();return closed;},dispose(){closeQuickPicker(false);document.removeEventListener('pointerdown',quickOutside);window.removeEventListener('resize',positionQuickPicker);document.removeEventListener('scroll',positionQuickPicker,true);quick.remove();bubble.dispose();childNav.dispose();clearLongTaskPress();flushDraft();clearTimeout(draftTimer);composerObserver?.disconnect();backdropObserver?.disconnect();window.removeEventListener('resize',paintBackdrop);window.removeEventListener('resize',positionJump);for(const query of [wide,dual]){if(query.removeEventListener)query.removeEventListener('change',resized);else query.removeListener(resized);}document.removeEventListener('visibilitychange',visibility);document.removeEventListener('keydown',escapePanel);closePanel();}};
}
