import {el, icon, button} from './dom.js';

/** A WebView-owned choice list. No HTML select or platform picker is involved. */
export function choiceList(label, choices, selected, choose) {
  const list=el('div','choice-list');
  list.setAttribute('role','listbox');list.setAttribute('aria-label',label);
  list.id='selection-options';
  for(const item of choices) {
    const node=button('',()=>choose(item.id),'choice-option');
    node.setAttribute('role','option');node.setAttribute('aria-selected',String(item.id===selected));
    node.dataset.value=item.id;node.disabled=item.disabled===true;node.tabIndex=-1;
    const mark=el('span','choice-mark');mark.append(icon(item.icon||'sliders-horizontal'));
    const text=el('span','choice-copy');text.append(el('span','choice-title',item.label));
    if(item.detail)text.append(el('span','choice-detail',item.detail));
    node.append(mark,text,icon(item.id===selected?'check':'circle'));list.append(node);
  }
  const enabled=()=>Array.from(list.querySelectorAll('button:not(:disabled)'));
  const first=list.querySelector('[aria-selected=true]:not(:disabled)')||enabled()[0];
  if(first)first.tabIndex=0;
  list.addEventListener('keydown',event=>{
    if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
    const items=enabled();if(!items.length)return;event.preventDefault();
    const index=items.indexOf(document.activeElement);
    const next=event.key==='Home'?0:event.key==='End'?items.length-1:(Math.max(0,index)+(event.key==='ArrowDown'?1:-1)+items.length)%items.length;
    for(const item of items)item.tabIndex=-1;items[next].tabIndex=0;items[next].focus();
  });
  return list;
}
