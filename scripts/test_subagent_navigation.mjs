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
  constructor(tag,cls='',text=''){this.tag=tag;this.className=cls;this.textContent=text;this.children=[];this.dataset={};this.listeners={};this.attributes={};this.scrollTop=0;this.hidden=false;}
  get firstChild(){return this.children[0]||null;}
  get nextSibling(){if(!this.parentElement)return null;const a=this.parentElement.children;return a[a.indexOf(this)+1]||null;}
  append(...nodes){for(const n of nodes)this.insertBefore(n,null);}
  insertBefore(node,before){if(node===before)return;node.remove();const i=before?this.children.indexOf(before):this.children.length;assert.ok(i>=0);this.children.splice(i,0,node);node.parentElement=this;}
  remove(){if(this.parentElement){const a=this.parentElement.children;a.splice(a.indexOf(this),1);this.parentElement=null;}}
  replaceChildren(...nodes){for(const n of [...this.children])n.remove();this.append(...nodes);}
  setAttribute(k,v){this.attributes[k]=String(v);}
  addEventListener(k,f){this.listeners[k]=f;}
  removeEventListener(k){delete this.listeners[k];}
  click(){this.listeners.click?.({target:this});}
  focus(){active=this;}
  contains(n){return n===this||this.children.some(c=>c.contains(n));}
}
const document={createElement:t=>new Node(t),createElementNS:(ns,t)=>new Node(t),addEventListener(){},removeEventListener(){},get activeElement(){return active;}};
const built=buildSync({stdin:{contents:"export {subagentNavigator} from './agent/app/src/main/assets/agent/bubbles.js'; export {createMessageRenderer} from './agent/app/src/main/assets/agent/message-renderer.js';",resolveDir:root},bundle:true,format:'cjs',platform:'node',write:false});
const sandbox={module:{exports:{}},exports:{},document,requestAnimationFrame:f=>f(),matchMedia:()=>({matches:true})};sandbox.exports=sandbox.module.exports;vm.runInNewContext(built.outputFiles[0].text,sandbox);
const {subagentNavigator,createMessageRenderer}=sandbox.module.exports;
const host=new Node('root'),main=new Node('main'),viewport=new Node('viewport'),composer=new Node('composer'),messages=new Node('messages');host.append(main);main.append(viewport,composer);viewport.append(messages);
let state={session:'parent',busy:true,reducedMotion:true,messages:[{id:'same',role:'assistant',text:'parent answer'}],subagents:[]};
const dom={el:(...args)=>new Node(...args),icon:name=>new Node('svg',name),richText:(node,text)=>{node.textContent=text;}};
const render=createMessageRenderer(messages,()=>{},dom);
let nav;
function update(){const child=nav.update();render(child?.id||state.session,child?.messages||state.messages,child?.label||'Parent');}
nav=subagentNavigator(host,main,viewport,composer,()=>state,update,()=>{},()=>{});
const find=(node,cls)=>node.className.split(' ').includes(cls)?node:node.children.map(c=>find(c,cls)).find(Boolean);
update();assert.equal(find(host,'agent-count').textContent,'0');
state.subagents=[{id:'child',label:'Worker',status:'running',running:true,messages:[{id:'same',role:'assistant',text:'child answer'},{id:'tool-1',role:'tool',text:'unified_exec · 运行中\n参数\npwd'}]}];update();assert.equal(find(host,'agent-count').textContent,'1');
find(host,'agent-bubble').click();const list=find(host,'agent-list');list.children.find(c=>c.dataset.child==='child').click();assert.equal(nav.selected,'child');assert.equal(composer.hidden,true);assert.equal(messages.firstChild.children[1].textContent,'child answer');assert.equal(messages.children[1].tag,'details');
state.subagents[0].messages[1].text='unified_exec · 完成\n结果\n/project';state.subagents[0].messages.push({id:'late',role:'assistant',text:'late child result'});update();assert.equal(messages.children.length,3);assert.match(messages.children[1].children[0].children[1].textContent,/完成/);
nav.close();assert.equal(nav.selected,'');assert.equal(composer.hidden,false);assert.equal(messages.firstChild.children[1].textContent,'parent answer');assert.equal(messages.children.length,1);
state.subagents.push({id:'grandchild',label:'Grandchild',status:'running',messages:[{id:'same',role:'assistant',text:'grandchild answer'}]});update();assert.equal(find(host,'agent-count').textContent,'2');find(host,'agent-bubble').click();find(host,'agent-list').children.find(c=>c.dataset.child==='grandchild').click();assert.equal(messages.firstChild.children[1].textContent,'grandchild answer');
state={session:'other',messages:[],subagents:[],reducedMotion:true};update();assert.equal(nav.selected,'');assert.equal(messages.children.length,0);nav.dispose();
console.log('PASS: Agent count, real navigator selection, child tool rendering, late updates, parent return, grandchild selection and session isolation.');