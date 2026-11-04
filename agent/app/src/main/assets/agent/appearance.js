const DEFAULT_LEFT='#713BC4',DEFAULT_RIGHT='#365DB7';
const color=(value,fallback)=>/^#[0-9a-f]{6}$/i.test(value||'')?value.toUpperCase():fallback;
const rgb=hex=>[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16));
const mix=(a,b,t)=>a.map((v,i)=>Math.round(v+(b[i]-v)*t));
const shade=(a,amount)=>a.map(v=>Math.floor(v*(1-amount)));
const hex=a=>'#'+a.map(v=>v.toString(16).padStart(2,'0')).join('');
const luminance=a=>a.map(v=>{v/=255;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
export function syncCodexCanvas(root){
  if(!root?.isConnected)return;
  const canvas=root.getBoundingClientRect();
  root.style.setProperty('--nebula-canvas-width',canvas.width+'px');
  root.style.setProperty('--nebula-canvas-height',canvas.height+'px');
  for(const node of root.querySelectorAll('.utility-dialog,.thinking-bubble,.agent-list-panel,.quick-picker')){
    if(!node.getClientRects().length)continue;
    const box=node.getBoundingClientRect();
    node.style.setProperty('--nebula-canvas-x',(canvas.left-box.left)+'px');
    node.style.setProperty('--nebula-canvas-y',(canvas.top-box.top)+'px');
  }
}
export function applyCodexAppearance(root,state){
  const config=state.codexAppearance||{},left=color(config.leftColor,DEFAULT_LEFT),right=color(config.rightColor,DEFAULT_RIGHT),all=state.engine==='codex'&&config.wholeInterface===true;
  const changed=root.dataset.nebulaAll!==String(all);root.dataset.nebulaAll=String(all);
  const key=left+right;
  if(root.dataset.nebulaPalette!==key){
    root.dataset.nebulaPalette=key;
    const a=rgb(left),b=rgb(right),average=mix(a,b,.5),samples=Array.from({length:17},(_,i)=>luminance(mix(a,b,i/16)));
    const dark=Math.min(...samples.map(v=>1.05/(v+.05)))>=Math.min(...samples.map(v=>(v+.05)/.05)),neutral=dark?[0,0,0]:[255,255,255],ink=dark?[255,255,255]:[12,12,16];
    // Every line is a darker shade of the user's colors, independent of text contrast.
    let line=shade(average,.42);const limit=Math.min(...samples)*.48;
    for(let i=0;i<256&&luminance(line)>limit;i++)line=shade(line,.08);
    const values={
      '--nebula-from':left,'--nebula-to':right,'--nebula-from-rgb':a.join(','),'--nebula-to-rgb':b.join(','),
      '--nebula-scheme':dark?'dark':'light','--nebula-ink':hex(ink),'--nebula-muted':hex(mix(ink,average,.1)),
      '--nebula-line':hex(line),'--nebula-line-from':hex(shade(a,.42)),'--nebula-line-to':hex(shade(b,.42)),
      '--nebula-rail-from':hex(shade(a,.58)),'--nebula-rail-to':hex(shade(b,.58)),
      '--nebula-accent':hex(average),'--nebula-accent-ink':luminance(average)<.42?'#FFFFFF':'#0C0C10',
      '--nebula-bg':hex(average),'--nebula-soft':hex(mix(average,neutral,.12)),
      '--nebula-hover':dark?'rgba(0,0,0,.16)':'rgba(0,0,0,.06)',
      '--nebula-surface-from':left,'--nebula-surface-to':right,
      '--nebula-panel-from':hex(mix(a,neutral,.12)),'--nebula-panel-to':hex(mix(b,neutral,.12)),
      '--nebula-bubble-from':left,'--nebula-bubble-to':right,
      '--nebula-control':hex(mix(average,neutral,.2)),
      '--nebula-highlight':dark?'rgba(255,255,255,.16)':'rgba(0,0,0,.08)'
    };
    for(const [name,value] of Object.entries(values))root.style.setProperty(name,value);
  }
  if(all||changed)syncCodexCanvas(root);
}
