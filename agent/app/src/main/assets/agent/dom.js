import {icons} from './icons.js';
export function el(tag, className = '', text) {
  const node = document.createElement(tag); node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
export function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  for (const [key, value] of Object.entries({viewBox:'0 0 24 24',fill:'none',stroke:'currentColor','stroke-width':'1.65','stroke-linecap':'round','stroke-linejoin':'round','aria-hidden':'true',focusable:'false',class:'icon'})) svg.setAttribute(key,value);
  for (const [tag, attributes] of icons[name] || icons.circle) {
    const child = document.createElementNS(svg.namespaceURI, tag);
    for (const [key,value] of Object.entries(attributes)) child.setAttribute(key,value);
    svg.append(child);
  }
  return svg;
}
export function button(label, action, className = '', symbol) {
  const node = el('button',className); node.type='button';
  if(symbol) node.append(icon(symbol));
  if(label) node.append(el('span','button-label',label));
  node.addEventListener('click',action);
  return node;
}
export function iconButton(label, name, action, className='') {
  const node=button('',action,'icon-button '+className,name);
  node.setAttribute('aria-label',label);node.title=label;return node;
}
export function section(title, className='') {
  const node=el('section','info-section '+className);node.append(el('h2','section-heading',title));return node;
}
export function inlineText(target,text) {
  // Only emphasis and inline code. Untrusted HTML and links remain text.
  const parts=String(text).split(/(`[^`\n]+`|\*\*[^*\n]+\*\*)/g);
  for(const part of parts) {
    if(part.startsWith('`')&&part.endsWith('`')) target.append(el('code','inline-code',part.slice(1,-1)));
    else if(part.startsWith('**')&&part.endsWith('**')) target.append(el('strong','',part.slice(2,-2)));
    else target.append(document.createTextNode(part));
  }
}
export function richText(target,text,copy) {
  const fragment=document.createDocumentFragment();
  const chunks=String(text).split(/```/);
  chunks.forEach((chunk,index)=>{
    if(index%2) {
      const nl=chunk.indexOf('\n');const language=nl<0?'':chunk.slice(0,nl).trim();
      const code=nl<0?chunk:chunk.slice(nl+1).replace(/\n$/,'');
      const block=el('div','code-block'),bar=el('div','code-header');
      bar.append(el('span','',language.slice(0,48)||'代码'),iconButton('复制代码','copy',()=>copy(code)));
      block.append(bar,el('pre','code-content',code));fragment.append(block);
    } else {
      let paragraph=null,list=null;
      for(const line of chunk.split('\n')) {
        if(!line.trim()){paragraph=null;list=null;continue;}
        const heading=line.match(/^(#{1,4})\s+(.+)/),bullet=line.match(/^\s*(?:[-*]|\d+\.)\s+(.+)/);
        if(heading){paragraph=null;list=null;const h=el('h3');inlineText(h,heading[2]);fragment.append(h);}
        else if(bullet){paragraph=null;if(!list){list=el('ul');fragment.append(list);}const li=el('li');inlineText(li,bullet[1]);list.append(li);}
        else {list=null;if(!paragraph){paragraph=el('p');fragment.append(paragraph);}else paragraph.append(document.createTextNode('\n'));inlineText(paragraph,line);}
      }
    }
  });
  target.replaceChildren(fragment);
}
