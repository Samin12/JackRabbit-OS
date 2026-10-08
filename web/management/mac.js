(function(){
// Mac Studio card: is the Mac bridge reachable, what can Voice do on the Mac (cua-driver, Accessibility,
// Screen Recording with its one fix command), which T3 project receives Mac tasks and general requests, and the
// Google account that Google links opened in Chrome use.
const body=document.querySelector("#mac-body"),stateEl=document.querySelector("#mac-state");
if(!body)return;
let busy=false,flash=null;
async function api(path,options={}){const headers={Accept:"application/json",...(options.headers||{})},csrf=window.__samCsrfToken;if(csrf&&options.method&&options.method!=="GET")headers["X-CSRF-Token"]=csrf;if(options.body)headers["Content-Type"]="application/json";const response=await fetch(path,{credentials:"same-origin",...options,headers});const value=await response.json().catch(()=>({}));if(!response.ok)throw new Error(value.error?.message||"This action could not be completed.");return value}
function el(tag,attrs={},...children){const node=document.createElement(tag);for(const[key,value]of Object.entries(attrs)){if(key==="text")node.textContent=value;else if(key==="class")node.className=value;else if(key==="style")node.style.cssText=value;else if(key.startsWith("on"))node.addEventListener(key.slice(2),value);else node.setAttribute(key,value)}node.append(...children.filter(Boolean));return node}
function setState(text,tone=""){if(!stateEl)return;stateEl.className="state "+tone;stateEl.querySelector("span").textContent=text}
function stat(label,value,detail){return el("article",{},el("small",{text:label}),el("strong",{text:value}),detail?el("small",{text:detail,style:"margin-top:6px"}):null)}
function message(text,tone="support"){return el("p",{class:tone,text:text||"",style:"margin:14px 0 0"})}
function fixPanel(fix){
  const command=String(fix||"").replace(/^Run on the Mac:\s*/,"");
  const code=el("code",{text:command,style:"display:block;padding:12px 14px;border:1px solid rgba(151,178,207,.32);border-radius:8px;overflow-wrap:anywhere;font-size:.86rem"});
  const copy=el("button",{class:"secondary compact",type:"button",text:"Copy command",onclick:async()=>{try{await navigator.clipboard.writeText(command);copy.textContent="Copied"}catch{copy.textContent="Select and copy it"}}});
  return el("div",{style:"display:grid;gap:10px;margin:0 0 28px"},
    el("strong",{text:"Turn on screen vision (one step on the Mac)"}),
    el("p",{class:"support",style:"margin:0",text:"Run this in Terminal on the Mac, then allow CuaDriver under Screen & System Audio Recording. Until then Voice reads windows as text instead of looking at the screen."}),
    code,copy)}
function picker(t3){
  const wrap=el("div",{style:"display:grid;gap:10px;max-width:560px"});
  wrap.append(el("strong",{text:"Orchestration project"}));
  if(!t3||!t3.connected){wrap.append(message("Pair T3 Code above to choose where Mac tasks go."));return wrap}
  const orchestration=t3.orchestration;
  if(!orchestration){wrap.append(message("T3 projects are still loading. Check again in a moment."));return wrap}
  const select=el("select",{"aria-label":"Orchestration project"});
  const auto=orchestration.source==="default"?orchestration.projectTitle:null;
  select.append(el("option",{value:"",text:auto?`Automatic (${auto})`:"Automatic"}));
  for(const project of orchestration.projects||[])select.append(el("option",{value:project.id,text:project.title}));
  select.value=orchestration.source==="setting"?orchestration.projectId:"";
  select.addEventListener("change",async()=>{if(busy)return;busy=true;select.disabled=true;try{await api("/v1/management/t3/settings",{method:"POST",body:JSON.stringify({orchestrationProjectId:select.value||null})});flash={text:"Saved. New Mac tasks go to "+(select.selectedOptions[0]?.textContent||"that project")+".",tone:"support"}}catch(error){flash={text:error.message,tone:"error"}}finally{busy=false;load()}});
  wrap.append(select,el("p",{class:"support",style:"margin:0;font-size:.9rem",text:"Voice starts Mac tasks and general requests here. Coding work goes to the project you name, a project the request mentions, or the most recently active one."}));
  return wrap}
function googleAccount(mac){
  // The Google account Voice opens Google Calendar, Gmail, Drive, Docs and Meet links in (authuser=).
  const account=mac.googleAccount;if(!account)return null;
  const input=el("input",{type:"email",name:"googleAccount",maxlength:"254",autocomplete:"off",spellcheck:"false","aria-label":"Google account",placeholder:account.fromCalendar||"name@example.com"});
  input.value=account.source==="setting"?account.email||"":"";
  const save=el("button",{class:"secondary compact",type:"submit",text:"Save"});
  const hint=account.email?`Voice opens Google Calendar, Gmail, Drive, Docs and Meet links in Chrome as ${account.email}${account.source==="calendar"?" (your calendar's account)":""}. Leave empty to use your calendar's account.`:"Enter your Google account so Calendar, Gmail and Drive links open in it (otherwise Chrome uses its first signed-in account).";
  const form=el("form",{style:"display:grid;gap:10px;max-width:560px;margin-top:28px",autocomplete:"off"},
    el("strong",{text:"Google account in Chrome"}),
    el("div",{style:"display:flex;gap:10px;align-items:center"},input,save),
    el("p",{class:"support",style:"margin:0;font-size:.9rem",text:hint}));
  form.addEventListener("submit",async event=>{event.preventDefault();if(busy)return;busy=true;save.disabled=true;try{const result=await api("/v1/management/mac",{method:"POST",body:JSON.stringify({googleAccount:input.value.trim()||null})});const email=result.googleAccount&&result.googleAccount.email;flash={text:email?`Saved. Google links open as ${email}.`:"Saved. Google links open in Chrome's first signed-in account.",tone:"support"}}catch(error){flash={text:error.message,tone:"error"}}finally{busy=false;load()}});
  return form}
function render(mac,t3){
  body.className="";body.replaceChildren();
  if(!mac.configured){setState("Not set up","");body.append(message(mac.message||"Connect the Mac bridge first (Heptabase journal > Connect through your Mac)."));return}
  const caps=mac.capabilities||{},driver=caps.driver||{},permissions=caps.permissions||{};
  if(!mac.reachable){setState("Unreachable","error")}
  else if(!driver.available||permissions.accessibility===false){setState("Needs setup","error")}
  else setState(permissions.screenRecording?"Ready":"Ready · no screen vision","ready");
  const title=caps.computer||"Your Mac";
  body.append(el("p",{class:"support",style:"margin:18px 0 0",text:`${title} · ${mac.url||""}`}));
  body.append(el("div",{class:"stats",style:"margin:18px 0 28px"},
    stat("Bridge",mac.reachable?"Reachable":"Unreachable",mac.reachable?(mac.bridgeVersion?`version ${mac.bridgeVersion}`:""):(mac.message||"")),
    stat("Control",!mac.reachable?"—":driver.available?(permissions.accessibility?"On":"No Accessibility"):"cua-driver missing",driver.version?`cua-driver ${driver.version}${driver.daemon===false?" (not running)":""}`:""),
    stat("Screen vision",!mac.reachable?"—":permissions.screenRecording?"On":"Off",permissions.screenRecording?"Voice can look at the screen":"Screen Recording not allowed")));
  if(mac.reachable&&caps.screenRecordingFix)body.append(fixPanel(caps.screenRecordingFix));
  body.append(picker(t3));
  const account=googleAccount(mac);if(account)body.append(account);
  if(flash){body.append(message(flash.text,flash.tone));flash=null}
  body.append(el("div",{class:"actions",style:"justify-content:flex-start;margin-top:22px"},el("button",{class:"secondary",type:"button",text:"Check again",onclick:load})))}
async function load(){
  try{
    const [mac,t3]=await Promise.all([api("/v1/management/mac"),api("/v1/management/t3").catch(()=>null)]);
    render(mac,t3)
  }catch(error){body.className="notice error";body.textContent=error.message;setState("Unavailable","error")}}
document.addEventListener("sam:page",event=>{if(event.detail==="connections")load()});
})();
