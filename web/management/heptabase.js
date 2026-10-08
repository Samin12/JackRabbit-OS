(function(){
// Heptabase journal: the Mac bridge (Heptabase CLI on the Mac, preferred) or OAuth, status, queue, settings,
// a one-line test entry, disconnect.
const root=document.querySelector("#heptabase-journal"),stateEl=document.querySelector("#heptabase-state");
if(!root)return;
let view=null,auth=null,poll=null,busy=false,flash="",notice=null;
async function api(path,options={}){const headers={Accept:"application/json",...(options.headers||{})},csrf=window.__samCsrfToken;if(csrf&&options.method&&options.method!=="GET")headers["X-CSRF-Token"]=csrf;if(options.body)headers["Content-Type"]="application/json";const response=await fetch(path,{credentials:"same-origin",...options,headers});const body=await response.json().catch(()=>({}));if(!response.ok)throw new Error(body.error?.message||"This action could not be completed.");return body}
const post=(path,body={})=>api(path,{method:"POST",body:JSON.stringify(body)});
function el(tag,attrs={},...children){const node=document.createElement(tag);for(const[key,value]of Object.entries(attrs)){if(key==="text")node.textContent=value;else if(key==="class")node.className=value;else if(key==="style")node.style.cssText=value;else if(key.startsWith("on"))node.addEventListener(key.slice(2),value);else node.setAttribute(key,value)}node.append(...children.filter(Boolean));return node}
function setState(text,tone=""){if(!stateEl)return;stateEl.className="state "+tone;stateEl.querySelector("span").textContent=text}
function when(iso){if(!iso)return"Never";const date=new Date(iso);if(Number.isNaN(date.getTime()))return"—";const diff=Math.round((Date.now()-date.getTime())/60000);if(diff<1)return"Just now";if(diff<60)return`${diff} min ago`;return date.toLocaleString([], {month:"short",day:"numeric",hour:"numeric",minute:"2-digit"})}
function stat(label,value){return el("article",{},el("small",{text:label}),el("strong",{text:value}))}
function message(text,tone="support"){return el("p",{class:tone,text:text||""})}
function noticeFor(card){if(notice){card.append(message(notice.text,notice.tone));notice=null}return card}
async function load(){try{view=await api("/v1/management/heptabase");render()}catch(error){root.className="notice error";root.textContent=error.message;setState("Unavailable","error")}}
function render(){
  root.className="";root.replaceChildren();
  if(view.mode==="mac"){setState("Connected through your Mac","ready");root.append(noticeFor(macCard()),settingsForm());return}
  const connected=view.connected,reconnect=view.needsReconnect;
  setState(reconnect?"Reconnect needed":connected?"Connected":"Not connected",reconnect?"error":connected?"ready":"");
  const card=el("article",{class:"card aqua"});
  if(!connected||reconnect){
    card.append(el("p",{class:"eyebrow",text:reconnect?"ACTION NEEDED":"CONNECT"}),
      el("h2",{text:reconnect?"Reconnect Heptabase":"Connect your Heptabase journal"}),
      message(reconnect?(view.lastError||"Heptabase access ended. Entries are kept and will sync after you reconnect."):"R1 gets its own Heptabase grant. You click Allow once in Heptabase; tokens stay encrypted on the R1."));
    if(auth)card.append(authPanel());
    else card.append(el("div",{class:"actions",style:"justify-content:flex-start;gap:12px"},
      el("button",{text:reconnect?"Reconnect with Mac helper":"Connect with Mac helper",onclick:()=>start("loopback")}),
      el("button",{class:"secondary",text:"Use this browser",onclick:()=>start("device")})));
  }
  if(connected){
    const q=view.queue||{};
    if(!reconnect)card.append(el("p",{class:"eyebrow",text:"CONNECTED"}),el("h2",{text:"Writing to your Heptabase journal"}),
      message(`Dates and times follow ${view.settings?.timezone||"America/New_York"}. Entries wait on the R1 when offline and sync in order.`));
    card.append(el("div",{class:"stats",style:"margin:22px 0 26px"},
      stat("Last written",when(view.lastSentAt)),
      stat("Waiting",waiting(q)),
      stat("Problems",q.failed?`${q.failed} failed`:"None")));
    if(view.lastError&&!reconnect)card.append(message(view.lastError,"error"));
    const actions=el("div",{class:"actions",style:"justify-content:flex-start;gap:12px"});
    if(q.failed)actions.append(el("button",{class:"secondary",text:"Retry failed entries",onclick:()=>act("/v1/management/heptabase/retry","Retrying.")}));
    if(!reconnect&&!auth)actions.append(el("button",{class:"secondary",text:"Reconnect",onclick:()=>start("loopback")}));
    actions.append(el("button",{class:"secondary danger",text:"Disconnect",onclick:disconnect}));
    card.append(actions);
    if(auth&&!reconnect)card.append(authPanel());
    if(!view.refreshAvailable)card.append(message("Heptabase granted no refresh token, so access ends within 48 hours. Reconnect to restore it.","error"));
  }
  root.append(noticeFor(card),bridgeForm(),settingsForm());
}
function waiting(q){return`${q.pending||0} queued${q.uncertain?` · ${q.uncertain} checking`:""}${q.held?` · ${q.held} awaiting words`:""}${q.paused?" · paused":""}`}
const BRIDGE_PROBLEMS={
  heptabase_app_unavailable:"The Heptabase app is not running on the Mac, or its CLI is off (Heptabase > Settings > AI Features). Entries wait on the R1.",
  bridge_unreachable:"The R1 cannot reach the bridge. Check that the Mac is awake and on this network.",
  bridge_unauthorized:"The bridge rejected the R1's token. Connect the Mac bridge again with the current token.",
  bridge_token_unavailable:"The R1 cannot read its bridge token. Connect the Mac bridge again.",
  heptabase_cli_missing:"The Heptabase CLI is not installed on the Mac (Heptabase > Settings > AI Features).",
  heptabase_cli_timeout:"Heptabase on the Mac did not answer in time. R1 retries."};
function macCard(){
  const b=view.bridge||{},q=view.queue||{};
  const app=b.reachable===false?"Mac unreachable":b.appReachable===true?"Running":b.appReachable===false?"Not running":"Not checked";
  const card=el("article",{class:"card aqua"},
    el("p",{class:"eyebrow",text:"CONNECTED"}),
    el("h2",{text:"Connected through your Mac (Heptabase app)"}),
    message(`R1 writes through the SamRabbit bridge at ${b.url}, which uses the Heptabase app's CLI. No Heptabase sign-in needed. While the app is closed, entries wait on the R1 and sync in order.`),
    el("div",{class:"stats",style:"margin:22px 0 26px"},
      stat("Last written",when(view.lastSentAt)),
      stat("Waiting",waiting(q)),
      stat("Heptabase app",app+(b.cliVersion?` · CLI ${b.cliVersion}`:""))));
  if(b.lastError)card.append(message(BRIDGE_PROBLEMS[b.lastError]||`Last problem: ${b.lastError}.`,"error"));
  if(q.failed&&view.lastError)card.append(message(view.lastError,"error"));
  const actions=el("div",{class:"actions",style:"justify-content:flex-start;gap:12px"},
    el("button",{class:"secondary",text:"Check Mac",onclick:()=>act("/v1/management/heptabase/bridge/check","Checked the Mac.")}),
    el("button",{class:"secondary",text:"Write a test line",onclick:testEntry}));
  if(q.failed)actions.append(el("button",{class:"secondary",text:"Retry failed entries",onclick:()=>act("/v1/management/heptabase/retry","Retrying.")}));
  actions.append(el("button",{class:"secondary danger",text:"Disconnect Mac",onclick:disconnectBridge}));
  card.append(actions,message("The test line adds “R1 journal connected through the Mac” to today's journal, once per day."));
  return card;
}
function bridgeForm(){
  const url=el("input",{name:"bridgeUrl",inputmode:"url",spellcheck:"false",placeholder:"http://192.168.1.183:3780",maxlength:"300",required:"",autocomplete:"off"});
  const token=el("input",{name:"token",type:"password",spellcheck:"false",maxlength:"512",required:"",autocomplete:"off"});
  const status=message("");
  const form=el("form",{class:"card t3-form",autocomplete:"off",style:"margin-top:36px"},
    el("p",{class:"eyebrow",text:"NO SIGN-IN"}),el("h2",{text:"Connect through your Mac"}),
    message("Uses the Heptabase app's CLI on your Mac instead of a Heptabase sign-in. On the Mac, run companion/mac-bridge/install.sh: it prints the address, and the token is in ~/.config/samrabbit/bridge-token."),
    el("div",{class:"fields"},el("label",{},"Bridge address",url),el("label",{},"Bridge token",token)),
    status,el("div",{class:"actions",style:"justify-content:flex-start"},el("button",{text:"Connect Mac bridge"})));
  form.addEventListener("submit",async event=>{event.preventDefault();if(busy)return;busy=true;status.className="support";status.textContent="Checking the Mac…";try{view=await post("/v1/management/heptabase/bridge",{bridgeUrl:url.value.trim(),token:token.value.trim()});token.value="";notice={text:"Connected through your Mac.",tone:"support"};render()}catch(error){status.className="error";status.textContent=error.message}finally{busy=false}});
  return form;
}
async function testEntry(){try{const result=await post("/v1/management/heptabase/test-entry");view=await api("/v1/management/heptabase");notice={tone:"support",text:result.state==="sent"?(result.duplicate?`Already written today: “${result.text}”.`:`Written to ${result.date}: “${result.text}”.`):`${result.duplicate?"Already queued":"Queued"} for ${result.date}: “${result.text}”. It syncs when the Mac answers.`}}catch(error){notice={text:error.message,tone:"error"}}render()}
async function disconnectBridge(){if(!confirm(`Disconnect the Mac bridge? Queued entries stay on the R1.${view.oauth?.connected?" Your Heptabase sign-in takes over.":""}`))return;await act("/v1/management/heptabase/bridge/disconnect","Mac bridge disconnected.")}
function authPanel(){
  const link=el("a",{class:"button",href:auth.authorizationUrl,target:"_blank",rel:"noreferrer noopener",text:"Open Heptabase Allow screen"});
  const command=`python3 companion/mac-bridge/heptabase-connect.py --r1 ${location.origin} --pairing-code <code on the R1>`;
  const steps=auth.mode==="loopback"
    ?[el("li",{},"Easiest: on the Mac, from your SamRabbit checkout, run ",el("code",{text:command,style:"user-select:all;color:var(--sam-text)"})," (it starts its own connection)."),
      el("li",{text:"Or, with a helper already listening on "+auth.redirectUri+", open the link below in Chrome."}),
      el("li",{text:"Click Allow in Heptabase. This page updates by itself."})]
    :[el("li",{text:"Open the link below in this browser and click Allow."}),
      el("li",{text:"Heptabase sends you back to the R1, which finishes the connection."}),
      el("li",{text:"Return to this tab; it updates by itself."})];
  const expires=new Date(auth.expiresAt);
  return el("div",{class:"subcard",style:"margin-top:24px"},
    el("p",{class:"eyebrow",text:"WAITING FOR ALLOW"}),
    el("ol",{class:"support",style:"margin:0 0 18px;padding-left:20px;line-height:1.7"},...steps),
    el("div",{class:"actions",style:"justify-content:flex-start;gap:12px;margin-top:0"},link,el("button",{class:"secondary",text:"Cancel",onclick:()=>{stopPolling();auth=null;render()}})),
    message(`Link expires at ${expires.toLocaleTimeString([], {hour:"numeric",minute:"2-digit"})}. It works once.`));
}
function settingsForm(){
  const s=view.settings||{};
  const toggle=(name,label,hint)=>el("label",{class:"switch-label",style:"align-items:flex-start;padding:14px 0;border-bottom:1px solid rgba(151,178,207,.2)"},
    Object.assign(el("input",{type:"checkbox",name}),{checked:!!s[name]}),
    el("span",{},el("strong",{text:label,style:"display:block;font-weight:500"}),el("small",{class:"support",text:hint,style:"display:block;margin-top:4px"})));
  const tz=el("input",{name:"timezone",list:"heptabase-zones",value:s.timezone||"America/New_York",maxlength:"64",autocomplete:"off"});
  const zones=el("datalist",{id:"heptabase-zones"},...["America/New_York","America/Chicago","America/Denver","America/Los_Angeles","Europe/London","UTC"].map(value=>el("option",{value})));
  const note=message(view.timezoneSource==="fallback"?"Using the built-in Eastern time rule (zone data unavailable).":"");
  const status=message(flash);flash="";
  const form=el("form",{class:"card",style:"margin-top:36px"},
    el("p",{class:"eyebrow",text:"WHAT GETS WRITTEN"}),el("h2",{text:"Journal settings"}),
    message("Only your own words and factual action lines are written. R1 never adds its own prose."),
    toggle("autoSessions","Record every voice conversation in my journal","Off (default): only what you ask R1 to add goes in your journal. On: at the end of each voice session, your words are added with times."),
    toggle("includeActions","Include actions","In recorded conversations: short lines such as “Task added” or “Calendar: added …”."),
    toggle("includeAssistant","Include assistant replies","Off keeps the journal to your voice only."),
    toggle("redactSecrets","Redact secrets","Replace keys, tokens and spoken codes with [redacted]."),
    el("label",{style:"margin-top:18px;max-width:340px"},"Timezone for journal dates",tz,zones),note,
    el("div",{class:"actions",style:"justify-content:flex-start"},el("button",{text:"Save journal settings"})),status);
  form.addEventListener("submit",async event=>{event.preventDefault();const data=new FormData(form);const body={timezone:String(data.get("timezone")||"").trim()};for(const key of["autoSessions","includeActions","includeAssistant","redactSecrets"])body[key]=data.get(key)==="on";try{view=await post("/v1/management/heptabase/settings",body);flash="Saved.";render()}catch(error){status.className="error";status.textContent=error.message}});
  return form;
}
async function start(mode){if(busy)return;busy=true;try{const started=await post("/v1/management/heptabase/connect/start",{redirect:mode});auth={...started,mode};render();startPolling()}catch(error){root.prepend(message(error.message,"error"))}finally{busy=false}}
function startPolling(){stopPolling();poll=setInterval(async()=>{if(!auth||Date.now()>new Date(auth.expiresAt).getTime()){stopPolling();auth=null;await load();return}try{const next=await api("/v1/management/heptabase");if(next.connected&&!next.needsReconnect&&next.connectedAt!==view?.connectedAt){stopPolling();auth=null;view=next;render()}}catch(_){}} ,3000)}
function stopPolling(){if(poll)clearInterval(poll);poll=null}
async function act(path,done){try{view=await post(path);notice={text:done,tone:"support"}}catch(error){notice={text:error.message,tone:"error"}}render()}
async function disconnect(){if(!confirm("Disconnect Heptabase? R1 revokes its access. Queued entries stay on the R1 until you reconnect."))return;await act("/v1/management/heptabase/disconnect","Disconnected.")}
document.addEventListener("sam:page",event=>{if(event.detail==="connections")load();else stopPolling()});
if(document.querySelector('[data-panel="connections"].active'))load();
})();
