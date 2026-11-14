import fs from 'node:fs';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';
const base=fileURLToPath(new URL('./assets/', import.meta.url));
const THREE=await import('file://'+base+'vendor/three.module.js');
const source=fs.readFileSync(base+'scene.js','utf8');
global.document={createElement:()=>({width:0,height:0,getContext:()=>({createLinearGradient:()=>({addColorStop(){}}),fillRect(){},fillText(){}})})};
const mod=source.replace("'./vendor/three.module.js'",JSON.stringify('file://'+base+'vendor/three.module.js'))+'\nexport {makeDevice};';
const {makeDevice}=await import('data:text/javascript;base64,'+Buffer.from(mod).toString('base64'));
const phones=['xiaomi','huawei','pixel','samsung'].map(k=>makeDevice(k,null,null));
const match=source.match(/const layouts = mobile \? ([\s\S]+?) : ([\s\S]+?);/);
const results=[];
for(const [i,mode] of ['mobile','desktop'].entries()) {
 const layouts=vm.runInNewContext('('+match[i+1]+')',{PI:Math.PI});
 const boxes=phones.map((p,j)=>{ const [x,y,z,rx,ry,rz]=layouts[j];p.position.set(x,y,z);p.rotation.set(rx,ry,rz);p.updateMatrixWorld(true);return new THREE.Box3().setFromObject(p,false);});
 const collisions=[];let clearance=Infinity;
 for(let a=0;a<4;a++)for(let b=a+1;b<4;b++){
  if(boxes[a].intersectsBox(boxes[b]))collisions.push([phones[a].name,phones[b].name]);
  const gap=['x','y','z'].map(k=>Math.max(0,boxes[a].min[k]-boxes[b].max[k],boxes[b].min[k]-boxes[a].max[k]));clearance=Math.min(clearance,Math.hypot(...gap));
 }
 results.push({mode,collisions,clearance,bounds:boxes.map((b,j)=>({name:phones[j].name,min:b.min.toArray(),max:b.max.toArray()}))});
}
console.log(JSON.stringify(results,null,2));
if(results.some(r=>r.collisions.length))process.exitCode=1;