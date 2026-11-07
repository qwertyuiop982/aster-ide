import {el,icon,richText} from './dom.js';

/** Incremental DOM reconciliation. Message IDs are local to a session/thread. */
export function createMessageRenderer(container,request,dom={el,icon,richText}) {
  const records=new Map();
  let previousScope=null;
  return function render(scope,items,assistantLabel) {
    let changed=scope!==previousScope,previous=null;
    const retained=new Set();
    for(const item of items) {
      const key=JSON.stringify([scope,item.id,item.role]);
      retained.add(key);
      let record=records.get(key);
      const tool=item.role==='tool'||item.role==='system';
      if(!record) {
        const row=dom.el(tool?'details':'article','message '+(tool?'tool-event':item.role==='user'?'user':'assistant'));
        const body=dom.el('div','message-body');
        let summary=null,label=null;
        if(tool) {
          const heading=dom.el('summary');summary=dom.el('span');
          heading.append(dom.icon('terminal'),summary,dom.icon('chevron-right'));row.append(heading,body);
        } else {
          if(item.role!=='user'){label=dom.el('div','assistant-label');row.append(label);}
          row.append(body);
        }
        record={row,body,summary,label,text:null};records.set(key,record);changed=true;
      }
      if(record.label&&record.label.textContent!==assistantLabel){record.label.textContent=assistantLabel;changed=true;}
      if(record.text!==item.text) {
        if(tool) {
          const heading=item.text.split('\n',1)[0];
          record.summary.textContent=heading.slice(0,110);
          record.row.dataset.failed=String(heading.includes(' · 失败'));
        }
        if(item.role==='user')record.body.textContent=item.text;
        else dom.richText(record.body,item.text,text=>request('copy',{text}));
        record.text=item.text;changed=true;
      }
      if(record.row.parentElement!==container||record.row.previousElementSibling!==previous) {
        container.insertBefore(record.row,previous?previous.nextSibling:container.firstChild);changed=true;
      }
      previous=record.row;
    }
    for(const [key,record] of records)if(!retained.has(key)){record.row.remove();records.delete(key);changed=true;}
    previousScope=scope;
    return changed;
  };
}
