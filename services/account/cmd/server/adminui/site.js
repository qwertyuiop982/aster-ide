"use strict";
(() => {
 const $ = id => document.getElementById(id);
 const state = {csrf:"", username:"", page:1, query:"", total:0, tab:"users", user:null, pending:null, submitting:false};
 let entryToken = new URLSearchParams(location.hash.slice(1)).get("token") || "";
 if (location.hash) history.replaceState(null, "", location.pathname);
 let toastTimer, searchVersion=0;
 function message(id, text) { $(id).textContent=text||""; $(id).hidden=!text; }
 function toast(text,bad=false) { clearTimeout(toastTimer); $("toast").textContent=text; $("toast").classList.toggle("bad",bad); $("toast").hidden=false; toastTimer=setTimeout(()=>$("toast").hidden=true,5000); }
 function node(tag, cls, text) { const e=document.createElement(tag); if(cls)e.className=cls; if(text!==undefined)e.textContent=text; return e; }
 function cell(row,text,cls="") { const e=node("td",cls,text);row.append(e);return e; }
 function units(value) { const s=String(value);if(!/^-?\d+(\.\d{1,3})?$/.test(s))throw Error("积分数值无效。");const neg=s.startsWith("-");const [whole,frac=""]=s.replace(/^-/ ,"").split(".");return (BigInt(whole)*1000n+BigInt(frac.padEnd(3,"0")))*(neg?-1n:1n); }
 function credits(value) { const n=typeof value==="bigint"?value:units(value);const v=n<0n?-n:n;return (n<0n?"-":"")+(v/1000n).toString().replace(/\B(?=(\d{3})+(?!\d))/g,",")+"."+(v%1000n).toString().padStart(3,"0"); }
 function rawCredits(value){const v=value<0n?-value:value;return(value<0n?"-":"")+(v/1000n)+"."+(v%1000n).toString().padStart(3,"0");}
 function date(value){return new Intl.DateTimeFormat("zh-CN",{timeZone:"Asia/Shanghai",year:"numeric",month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit",hour12:false}).format(new Date(value));}
 function showLogin(note="") { state.csrf="";state.user=null;state.pending=null;if($("account-dialog").open)$("account-dialog").close();$("app-view").hidden=true;$("login-view").hidden=false;$("password").value="";$("login-submit").disabled=!entryToken;$("link-status").textContent=entryToken?"专属管理链接已载入，请输入管理员凭据。":"请使用含 token 的完整管理链接重新打开本页。";message("login-error",note); }
 async function request(path,{method="GET",body,login=false}={}) {
  const headers={Accept:"application/json"};
  if(method!=="GET"){headers["Content-Type"]="application/json";if(login)headers["X-Aster-Admin-Token"]=entryToken;else headers["X-Aster-CSRF"]=state.csrf;}
  let res;try{res=await fetch("/api/v1/admin/"+path,{method,headers,credentials:"same-origin",cache:"no-store",body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(12000)});}catch(e){throw Error("连接未完成，请重试；重复提交不会重复调整积分。");}
  const data=await res.json();
  if(!res.ok){if(res.status===401&&!login&&path!=="session")showLogin("登录已过期，请使用管理链接重新登录。");throw Error(data.error?.message||"操作失败，请稍后重试。");}
  return data;
 }
 async function summary(){const data=await request("summary");$("stat-users").textContent=Number(data.users).toLocaleString("zh-CN");$("stat-balance").textContent=credits(data.balance);$("stat-reserved").textContent=credits(data.reserved);$("stat-usage").textContent=credits(data.today_usage);}
 function accountButton(user){const b=node("button","account-link",user.name);b.type="button";b.title=user.name;b.addEventListener("click",()=>openAccount(user.id).catch(e=>toast(e.message,true)));return b;}
 async function users(){
  const version=++searchVersion;const data=await request("users?"+new URLSearchParams({q:state.query,page:state.page}));if(version!==searchVersion)return;
  state.total=data.total;$("users-body").replaceChildren();
  for(const user of data.items){const row=node("tr");const who=cell(row);who.append(accountButton(user),node("small","subline","#"+user.id+" · "+(user.providers||"账户")));const status=cell(row);status.append(node("span","badge"+(user.status==="active"?"":" inactive"),user.status==="active"?"正常":user.status));cell(row,credits(user.balance),"numeric");cell(row,credits(user.available),"numeric");cell(row,credits(user.reserved),"numeric");const action=cell(row);const b=node("button","button secondary","管理 →");b.type="button";b.setAttribute("aria-label","管理账户 "+user.name);b.addEventListener("click",()=>openAccount(user.id).catch(e=>toast(e.message,true)));action.append(b);$("users-body").append(row);}
  $("users-empty").hidden=data.items.length>0;$("page-info").textContent=`共 ${data.total} 个账户 · 第 ${data.page} / ${Math.max(1,Math.ceil(data.total/data.page_size))} 页`;$("previous-page").disabled=state.page<=1;$("next-page").disabled=state.page*data.page_size>=data.total;
 }
 async function audit(){const data=await request("adjustments");$("audit-body").replaceChildren();for(const item of data.items){const row=node("tr");const who=cell(row);who.append(accountButton({id:item.user_id,name:item.name}),node("small","subline","#"+item.user_id));cell(row,(units(item.points)>0n?"+":"")+credits(item.points),"numeric "+(units(item.points)>0n?"positive":"negative"));cell(row,credits(item.balance),"numeric");const reason=cell(row,item.reason,"reason-cell");reason.title=item.request_id;const at=cell(row,item.admin);at.append(node("small","subline",date(item.created_at)));$("audit-body").append(row);}$("audit-empty").hidden=data.items.length>0;}
 async function refresh(){message("page-error","");$("refresh").disabled=true;try{await Promise.all([summary(),state.tab==="users"?users():audit()]);$("updated-at").textContent="最近更新 "+date(new Date());}catch(e){message("page-error",e.message);}finally{$("refresh").disabled=false;}}
 async function enter(session){state.csrf=session.csrf;state.username=session.username;entryToken="";$("password").value="";$("admin-name").textContent=session.username;$("login-view").hidden=true;$("app-view").hidden=false;await refresh();}
 function resetAdjustment(){state.pending=null;$("adjust-form").reset();$("adjust-form").hidden=false;$("review").hidden=true;$("adjust-fields").disabled=false;message("adjust-error","");$("balance-preview").textContent="最多三位小数，单次不超过 1,000,000 积分。";}
 async function openAccount(id){
  if(state.submitting)return;const data=await request("users/"+encodeURIComponent(id));state.user=data.user;resetAdjustment();$("account-title").textContent=data.user.name;$("account-subtitle").textContent="#"+data.user.id+" · "+(data.user.providers||"账户")+" · 注册于 "+date(data.user.created_at);
  for(const field of ["balance","available","reserved"])$("detail-"+field).textContent=credits(data.user[field]);
  $("ledger-body").replaceChildren();const kinds={registration:"注册赠送",checkin:"每日签到",usage:"模型消耗",admin_adjustment:"管理员调整"};
  for(const item of data.ledger){const row=node("tr");const kind=cell(row,kinds[item.kind]||item.kind);kind.append(node("small","subline",date(item.created_at)));cell(row,(units(item.points)>0n?"+":"")+credits(item.points),"numeric "+(units(item.points)>0n?"positive":"negative"));const reason=cell(row,item.reason||item.reference,"reason-cell");if(item.admin)reason.append(node("small","subline",item.admin));$("ledger-body").append(row);}$("ledger-empty").hidden=data.ledger.length>0;if(!$("account-dialog").open)$("account-dialog").showModal();
 }
 function adjustment(){
  const value=$("amount").value.trim();if(!/^(0|[1-9]\d{0,6})(\.\d{1,3})?$/.test(value))throw Error("请输入最多三位小数的正数。");let amount=units(value);if(amount<=0n||amount>1000000000n)throw Error("单次调整范围为 0.001 至 1,000,000 积分。");if(document.querySelector('input[name="adjust-mode"]:checked').value==="subtract")amount=-amount;
  const after=units(state.user.balance)+amount;if(after<units(state.user.reserved))throw Error("可用积分不足，不能扣减预占积分。");return{amount,after};
 }
 function preview(){if(!state.user||!$("amount").value.trim())return;try{const {after}=adjustment();$("balance-preview").textContent="调整后总余额："+credits(after)+" 积分";}catch(e){$("balance-preview").textContent=e.message;}}
 $("login-form").addEventListener("submit",async e=>{e.preventDefault();if(!entryToken){showLogin("请使用完整的管理链接。");return;}message("login-error","");$("login-submit").disabled=true;$("login-submit").textContent="正在登录…";try{const session=await request("login",{method:"POST",login:true,body:{username:$("username").value.trim(),password:$("password").value}});await enter(session);}catch(error){message("login-error",error.message);}finally{$("login-submit").disabled=!entryToken;$("login-submit").textContent="登录控制台 ↗";}});
 $("logout").addEventListener("click",async()=>{try{await request("logout",{method:"POST",body:{}});showLogin();toast("已退出登录。");}catch(e){toast(e.message,true);}});
 $("refresh").addEventListener("click",refresh);
 $("search-form").addEventListener("submit",e=>{e.preventDefault();state.query=$("search").value.trim();state.page=1;refresh();});
 $("clear-search").addEventListener("click",()=>{$("search").value="";state.query="";state.page=1;refresh();});
 $("previous-page").addEventListener("click",()=>{if(state.page>1){state.page--;refresh();}});$("next-page").addEventListener("click",()=>{if(state.page*25<state.total){state.page++;refresh();}});
 for(const tab of ["users","audit"])$(tab+"-tab").addEventListener("click",()=>{state.tab=tab;for(const name of ["users","audit"]){$(name+"-tab").setAttribute("aria-selected",String(name===tab));$(name+"-panel").hidden=name!==tab;}refresh();});
 $("close-account").addEventListener("click",()=>{if(!state.submitting)$("account-dialog").close();});$("account-dialog").addEventListener("cancel",e=>{if(state.submitting)e.preventDefault();});
 $("amount").addEventListener("input",preview);document.querySelectorAll('input[name="adjust-mode"]').forEach(input=>input.addEventListener("change",preview));
 $("adjust-form").addEventListener("submit",e=>{e.preventDefault();message("adjust-error","");try{const {amount,after}=adjustment();const reason=$("reason").value.trim();if(Array.from(reason).length<2||Array.from(reason).length>200)throw Error("请填写 2 至 200 字的调整原因。");state.pending={request_id:crypto.randomUUID(),points:rawCredits(amount),reason,user_id:state.user.id};$("review-account").textContent=state.user.name+" · #"+state.user.id;$("review-amount").textContent=(amount>0n?"+":"")+credits(amount)+" 积分";$("review-amount").className="review-amount "+(amount>0n?"positive":"negative");$("review-balance").textContent="总余额："+credits(state.user.balance)+" → "+credits(after);$("review-reason").textContent=reason;$("confirm-adjustment").textContent=amount>0n?"确认增加积分":"确认扣减积分";$("adjust-form").hidden=true;$("review").hidden=false;$("confirm-adjustment").focus();}catch(error){message("adjust-error",error.message);}});
 $("back-adjustment").addEventListener("click",()=>{if(state.submitting)return;state.pending=null;$("review").hidden=true;$("adjust-form").hidden=false;message("adjust-error","");});
 $("confirm-adjustment").addEventListener("click",async()=>{if(state.submitting||!state.pending)return;state.submitting=true;for(const id of ["confirm-adjustment","back-adjustment","close-account"])$(id).disabled=true;message("adjust-error","");try{const {user_id,...body}=state.pending;const result=await request("users/"+user_id+"/adjustments",{method:"POST",body});state.pending=null;$("account-dialog").close();toast(result.replayed?"本次调整已成功处理，未重复记账。":"积分调整已成功入账。");await refresh();}catch(e){message("adjust-error",e.message);}finally{state.submitting=false;for(const id of ["confirm-adjustment","back-adjustment","close-account"])$(id).disabled=false;}});
 addEventListener("hashchange",()=>{const token=new URLSearchParams(location.hash.slice(1)).get("token")||"";if(location.hash)history.replaceState(null,"",location.pathname);if(token){entryToken=token;if($("app-view").hidden)showLogin();}});
 showLogin();request("session").then(enter).catch(()=>{});
})();