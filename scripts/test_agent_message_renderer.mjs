// Runs production renderer code with a small structural DOM, without npm downloads.
import assert from 'node:assert/strict';
import {createMessageRenderer,createToolExpansionState} from '../agent/app/src/main/assets/agent/message-renderer.js';
let richCalls=0,created=0,moves=0;
class Node {
  constructor(tag,cls='',text=''){this.tag=tag;this.className=cls;this.textContent=text;this.children=[];this.parentElement=null;this.dataset={};this.listeners={};created++;}
  get firstChild(){return this.children[0]||null;}
  get nextSibling(){if(!this.parentElement)return null;const a=this.parentElement.children;return a[a.indexOf(this)+1]||null;}
  get previousElementSibling(){if(!this.parentElement)return null;const a=this.parentElement.children;return a[a.indexOf(this)-1]||null;}
  append(...nodes){for(const node of nodes)this.insertBefore(node,null);}
  insertBefore(node,before){if(node===before)return;if(node.parentElement)node.remove();const i=before?this.children.indexOf(before):this.children.length;assert.ok(i>=0);this.children.splice(i,0,node);node.parentElement=this;moves++;}
  remove(){if(this.parentElement){const a=this.parentElement.children;a.splice(a.indexOf(this),1);this.parentElement=null;}}
  addEventListener(kind,fn){this.listeners[kind]=fn;}
  toggle(open){this.open=open;this.listeners.toggle?.();}
}
const dom={el:(...args)=>new Node(...args),icon:name=>new Node('svg',name),richText:(body,text,copy)=>{richCalls++;body.textContent=text;body.copy=copy;}};
const host=new Node('div');let copied;
const render=createMessageRenderer(host,(command,args)=>{copied={command,...args};},dom);
const msg=(id,role,text)=>({id,role,text});
let cases=0;
function test(name,body){body();cases++;console.log('PASS:',name);}
test('initial assistant message',()=>{assert.equal(render('parent',[msg('same','assistant','hello')],'Main'),true);assert.equal(host.children.length,1);assert.equal(host.firstChild.children[0].textContent,'Main');});
test('unchanged snapshots do no DOM or rich-text work',()=>{const before=[created,moves,richCalls];assert.equal(render('parent',[msg('same','assistant','hello')],'Main'),false);assert.deepEqual([created,moves,richCalls],before);});
test('same-length replacement is rendered',()=>{render('parent',[msg('same','assistant','world')],'Main');assert.equal(host.firstChild.children[1].textContent,'world');});
test('child with duplicate message ID has independent DOM and label',()=>{const old=host.firstChild;render('child',[msg('same','assistant','world')],'Worker');assert.notEqual(host.firstChild,old);assert.equal(old.parentElement,null);assert.equal(host.firstChild.children[0].textContent,'Worker');});
test('role changes recreate proper tool structure',()=>{render('child',[msg('same','tool','shell · 失败\noutput')],'Worker');assert.equal(host.firstChild.tag,'details');assert.equal(host.firstChild.dataset.failed,'true');assert.equal(host.firstChild.children[0].children[1].textContent,'shell · 失败');});
test('tool completion updates summary and failure flag',()=>{render('child',[msg('same','tool','shell · 完成\nresult')],'Worker');assert.equal(host.firstChild.dataset.failed,'false');assert.equal(host.firstChild.children[0].children[1].textContent,'shell · 完成');});
test('tool to user role does not retain details element',()=>{render('child',[msg('same','user','literal <script>')],'Worker');assert.equal(host.firstChild.tag,'article');assert.equal(host.firstChild.children.length,1);assert.equal(host.firstChild.children[0].textContent,'literal <script>');});
test('reordering and deletion preserve matching records',()=>{const rows=[msg('a','assistant','A'),msg('b','assistant','B')];render('child',rows,'Worker');const [a,b]=host.children;render('child',[rows[1],rows[0]],'Worker');assert.deepEqual(host.children,[b,a]);render('child',[rows[0]],'Worker');assert.deepEqual(host.children,[a]);assert.equal(b.parentElement,null);});
test('updated display labels refresh existing nodes',()=>{const old=host.firstChild;render('child',[msg('a','assistant','A')],'Renamed');assert.equal(host.firstChild,old);assert.equal(old.children[0].textContent,'Renamed');});
test('copy callback preserves command boundary',()=>{host.firstChild.children[1].copy('copied');assert.deepEqual(copied,{command:'copy',text:'copied'});});
test('empty transcript removes all prior nodes',()=>{assert.equal(render('child',[],'Worker'),true);assert.equal(host.children.length,0);assert.equal(render('child',[],'Worker'),false);});
test('session switches do not leak transcript nodes',()=>{for(let i=0;i<100;i++){render('session-'+i,[msg('same','assistant','x')],'Worker');assert.equal(host.children.length,1);}});
test('in-place text mutation is detected',()=>{const row=msg('same','assistant','before');render('mutation',[row],'Worker');row.text='after';assert.equal(render('mutation',[row],'Worker'),true);assert.equal(host.firstChild.children[1].textContent,'after');});
test('switching Agents restores only that transcript’s expanded tool output',()=>{
  const running=msg('tool','tool','shell · 运行中\n参数\npwd');
  const completed=msg('tool','tool','shell · 完成\n结果\n/project');
  render('workspace/parent/child', [running], 'Child');
  // Switching in the same frame must capture open before the browser's toggle event.
  host.firstChild.open=true;
  render('workspace/parent', [running], 'Parent');
  assert.equal(host.firstChild.open,false);
  render('workspace/parent/child', [completed], 'Child');
  assert.equal(host.firstChild.open,true);
  assert.match(host.firstChild.children[1].textContent,/\/project/);
  host.firstChild.toggle(false);
  render('workspace/other-parent/child', [running], 'Other child');
  host.firstChild.toggle(true);
  render('workspace/parent/child', [completed], 'Child');
  assert.equal(host.firstChild.open,false);
});
test('page recreation reuses tool expansion without reusing DOM nodes',()=>{
  const expansion=createToolExpansionState(),scope='workspace/engine/session/child';
  const items=[msg('call','tool','shell · 完成\nresult')];
  const first=new Node('div'), second=new Node('div');
  createMessageRenderer(first,()=>{},dom,expansion)(scope,items,'Child');
  first.firstChild.toggle(true);
  createMessageRenderer(second,()=>{},dom,expansion)(scope,items,'Child');
  assert.equal(second.firstChild.open,true);
  assert.notEqual(second.firstChild,first.firstChild);
});
console.log(JSON.stringify({success:true,cases,created,moves,richCalls}));
