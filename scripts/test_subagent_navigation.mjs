// Production navigator + renderer integration on a structural DOM (not a device test).
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import vm from 'node:vm';
const root=process.cwd();
const require=createRequire(resolve(root,'research/pi-agent/package.json'));
const {buildSync}=require('esbuild');
let active;
class Node {
  constructor(tag,cls='',text=''){
    this.tag=tag;this.className=cls;this.textContent=text;this.children=[];this.dataset={};this.listeners={};this.attributes={};
    this.scrollTop=0;this.hidden=false;this.isConnected=true;this.offsetWidth=240;this.offsetHeight=140;this.value='';
    this.style={setProperty(key,value){this[key]=value;}};
    this.classList={
      contains:name=>this.className.split(' ').includes(name),
      add:name=>{if(!this.classList.contains(name))this.className+=' '+name;},
      toggle:(name,force)=>{if(force)this.classList.add(name);else this.className=this.className.split(' ').filter(x=>x!==name).join(' ');}
    };
  }
  get firstChild(){return this.children[0]||null;}
  get nextSibling(){if(!this.parentElement)return null;const a=this.parentElement.children;return a[a.indexOf(this)+1]||null;}
  append(...nodes){for(const n of nodes)this.insertBefore(n,null);}
  insertBefore(node,before){if(node===before)return;node.remove();const i=before?this.children.indexOf(before):this.children.length;assert.ok(i>=0);this.children.splice(i,0,node);node.parentElement=this;}
  remove(){if(this.parentElement){const a=this.parentElement.children;a.splice(a.indexOf(this),1);this.parentElement=null;}}
  replaceChildren(...nodes){for(const n of [...this.children])n.remove();this.append(...nodes);}
  setAttribute(k,v){this.attributes[k]=String(v);}
  getAttribute(k){return this.attributes[k]??null;}
  hasAttribute(k){return k in this.attributes;}
  removeAttribute(k){delete this.attributes[k];}
  getContext(){return null;}
  getBoundingClientRect(){
    const rect=this.rect||[0,0,400,700],left=parseFloat(this.style.left)||rect[0],top=parseFloat(this.style.top)||rect[1];
    return {left,top,width:rect[2],height:rect[3],right:left+rect[2],bottom:top+rect[3]};
  }
  getClientRects(){return this.hidden?[]:[this.getBoundingClientRect()];}
  matches(selector){
    if(selector.endsWith(':not(:disabled)'))return !this.disabled&&this.matches(selector.slice(0,-16));
    if(selector[0]==='.')return this.classList.contains(selector.slice(1));
    if(selector[0]==='['){const [key,value]=selector.slice(1,-1).split('=');const attr=key.startsWith('data-')?this.dataset[key.slice(5)]:this.getAttribute(key);return value?attr===value:attr!=null;}
    return this.tag===selector;
  }
  querySelectorAll(selector){
    const parts=selector.split(',');
    return this.children.flatMap(child=>[...(parts.some(part=>child.matches(part))?[child]:[]),...child.querySelectorAll(selector)]);
  }
  querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
  addEventListener(k,f){this.listeners[k]=f;}
  removeEventListener(k){delete this.listeners[k];}
  dispatch(kind,props={}){this.listeners[kind]?.({target:this,currentTarget:this,preventDefault(){},...props});}
  click(){if(!this.disabled)this.dispatch('click',{detail:1});}
  focus(){active=this;}
  contains(n){return n===this||this.children.some(c=>c.contains(n));}
}
const document={createElement:t=>new Node(t),createElementNS:(ns,t)=>new Node(t),
  createDocumentFragment:()=>new Node('fragment'),createTextNode:t=>new Node('text','',t),
  addEventListener(){},removeEventListener(){},get activeElement(){return active;}};
const locale=process.argv.includes('--english')?'en':'zh-Hans';
const window={AsterBoot:{locale},addEventListener(){},removeEventListener(){}};
const timers=new Map();let timerId=0;
const built=buildSync({stdin:{contents:"export {subagentNavigator} from './agent/app/src/main/assets/agent/bubbles.js'; export {createMessageRenderer} from './agent/app/src/main/assets/agent/message-renderer.js'; export {createPage} from './agent/app/src/main/assets/agent/components.js'; export {default as profile} from './agent/app/src/main/assets/agent/engines/pi.js';",resolveDir:root},bundle:true,format:'cjs',platform:'node',write:false});
const sandbox={module:{exports:{}},exports:{},document,window,innerWidth:400,innerHeight:700,
  requestAnimationFrame:f=>f(),matchMedia:()=>({matches:false,addListener(){},removeListener(){}}),
  setTimeout:f=>{timers.set(++timerId,f);return timerId;},clearTimeout:id=>timers.delete(id)};
sandbox.exports=sandbox.module.exports;vm.runInNewContext(built.outputFiles[0].text,sandbox);
const {subagentNavigator,createMessageRenderer,createPage,profile}=sandbox.module.exports;
const host=new Node('root'),main=new Node('main'),viewport=new Node('viewport'),composer=new Node('composer'),messages=new Node('messages');host.append(main);main.append(viewport,composer);viewport.append(messages);
let state={session:'parent',busy:true,reducedMotion:true,messages:[{id:'same',role:'assistant',text:'parent answer'}],subagents:[]};
const dom={el:(...args)=>new Node(...args),icon:name=>new Node('svg',name),richText:(node,text)=>{node.textContent=text;}};
const render=createMessageRenderer(messages,()=>{},dom);
let nav;
function update(){const child=nav.update();render(JSON.stringify([state.session,child?.id||'']),child?.messages||state.messages,child?.label||'Parent');}
const sent=[];
nav=subagentNavigator(host,main,viewport,composer,()=>state,update,(...args)=>sent.push(args),()=>{});
const find=(node,cls)=>node.className.split(' ').includes(cls)?node:node.children.map(c=>find(c,cls)).find(Boolean);
viewport.rect=[0,72,400,540];find(host,'agent-bubble').rect=[336,120,54,64];find(host,'agent-dock').rect=[336,120,54,64];
update();assert.equal(find(host,'agent-count').textContent,'0');
state.subagents=[{id:'child',label:'Worker',status:'running',running:true,messages:[{id:'same',role:'assistant',text:'child answer'},{id:'tool-1',role:'tool',text:'unified_exec · 运行中\n参数\npwd'}]}];update();assert.equal(find(host,'agent-count').textContent,'1');
find(host,'agent-bubble').click();const list=find(host,'agent-list');list.children.find(c=>c.dataset.child==='child').click();assert.equal(nav.selected,'child');assert.equal(composer.hidden,true);assert.equal(messages.firstChild.children[1].textContent,'child answer');assert.equal(messages.children[1].tag,'details');
state.subagents[0].messages[1].text='unified_exec · 完成\n结果\n/project';state.subagents[0].messages.push({id:'late',role:'assistant',text:'late child result'});update();assert.equal(messages.children.length,3);assert.match(messages.children[1].children[0].children[1].textContent,/完成/);
nav.close();assert.equal(nav.selected,'');assert.equal(composer.hidden,false);assert.equal(messages.firstChild.children[1].textContent,'parent answer');assert.equal(messages.children.length,1);
state.subagents.push({id:'grandchild',label:'Grandchild',status:'running',messages:[{id:'same',role:'assistant',text:'grandchild answer'}]});update();assert.equal(find(host,'agent-count').textContent,'2');find(host,'agent-bubble').click();find(host,'agent-list').children.find(c=>c.dataset.child==='grandchild').click();assert.equal(messages.firstChild.children[1].textContent,'grandchild answer');
state={session:'other',messages:[],subagents:[state.subagents[1]],reducedMotion:true};update();assert.equal(nav.selected,'');assert.equal(messages.children.length,0);
const trigger=find(host,'agent-bubble'),dock=find(host,'agent-dock');
function press(x,y){trigger.dispatch('pointerdown',{pointerId:1,button:0,clientX:x,clientY:y});}
function move(x,y){trigger.dispatch('pointermove',{pointerId:1,clientX:x,clientY:y});}
function tick(){for(const [id,callback] of [...timers]){timers.delete(id);callback();}}
press(350,135);move(100,250);tick();trigger.dispatch('pointerup',{pointerId:1});trigger.click();assert.equal(sent.length,0);assert.equal(nav.isOpen,false);
press(350,135);tick();assert.equal(dock.dataset.dragging,'true');assert.equal(sent[0][0],'bubble_hold');
move(-50,900);trigger.dispatch('pointerup',{pointerId:1});trigger.click();assert.equal(nav.isOpen,false);
assert.equal(sent[1][0],'bubble_position');assert.equal(sent[1][1].x,0);assert.equal(sent[1][1].y,1);
assert.equal(dock.style.left,'10px');assert.equal(dock.style.top,'540px');
nav.dispose();

const actions=[];
const chat=createPage(profile,(command,data)=>actions.push([command,data]),{text:'',dirty:false});
const chatState={workspace:'workspace',engine:'pi',session:'chat',uiLayout:'chat',imagesSupported:true,reducedMotion:true};
chat.update(chatState);
assert.equal(find(chat.root,'task-input').placeholder,locale==='en'?'Ask Pi…':'询问 Pi…');
const plus=find(chat.root,'context-picker'),menu=find(chat.root,'attachment-options');
assert.equal(menu,undefined);
const commands=['pick_images','pick_documents','context_files'];
for(const [index,command] of commands.entries()){
  plus.click();
  assert.equal(plus.getAttribute('aria-expanded'),'true');
  const items=find(chat.root,'attachment-options').children;
  const expected=locale==='en'?['Images','Files','Workspace files']:['图片','文件','工作区文件'];
  assert.deepEqual(items.map(node=>node.children.at(-1).textContent),expected);
  items[index].click();assert.equal(actions.at(-1)[0],command);
  assert.equal(plus.getAttribute('aria-expanded'),'false');
}
const form=find(chat.root,'composer'),submit=find(chat.root,'send-button');
assert.equal(submit.disabled,true);form.dispatch('submit');assert.notEqual(actions.at(-1)[0],'send');
chat.update({...chatState,attachments:[{id:'picture',name:'diagram.jpg',image:true}]});
assert.equal(submit.disabled,false);form.dispatch('submit');
assert.equal(actions.at(-1)[0],'send');assert.equal(actions.at(-1)[1].text,'');
find(chat.root,'attachment-remove').click();assert.equal(actions.at(-1)[0],'remove_attachment');assert.equal(actions.at(-1)[1].id,'picture');
chat.update({...chatState,imagesSupported:false});
plus.click();assert.equal(find(chat.root,'attachment-options').firstChild.disabled,true);
chat.dispose();
console.log(`PASS (${locale}): scoped child navigation, tool updates, clamped persisted drag, three picker actions and image-only send.`);
