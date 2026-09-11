/* Offline renderer + demo state. Nothing in this file calls a network API. */
'use strict';
const CAT=CATALOG.screens, BYID=Object.fromEntries(CAT.map(s=>[s.id,s]));
const params=new URLSearchParams(location.search), capture=params.has('capture');
const state={screen:'workspaces',width:Number(params.get('width')||412),scale:Number(params.get('scale')||1),mode:'prototype',host:'hetzner',root:'~/git',workspace:'pocketshell',path:'~/git/pocketshell',folder:'~/git/experiments',rootMode:false,engine:'claude',session:'Terminal',fields:{},drafts:{},extraWorkspaces:[],sessionOverrides:{},history:[],choices:{},order:[],dynamic:false,from:'',parent:'~/git',createIsWorkspace:true,fileName:'README.md',filePath:'~/git/pocketshell',extraFileFolders:[]};
const escapeHtml=(v)=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const e=escapeHtml;
function icon(name,cls=''){const paths=ICONS[name]||ICONS.info;return `<svg aria-hidden="true" class="icon ${cls}" viewBox="0 0 24 24">${paths.map(d=>`<path d="${d}"/>`).join('')}</svg>`;}
function goAttrs(route,extra=''){return route?`data-go="${e(route)}" ${extra}`:'';}
function iconButton(name,route,label){return `<button class="icon-button" ${goAttrs(route)} aria-label="${e(label)}">${icon(name)}</button>`;}
const agent={claude:{icon:'hexagon',label:'Claude'},codex:{icon:'code',label:'Codex'},opencode:{icon:'terminal',label:'OpenCode'},grok:{icon:'zap',label:'Grok'},shell:{icon:'terminal',label:'Terminal'}};
const agentFull={claude:'Claude Code',codex:'Codex',opencode:'OpenCode',grok:'Grok',shell:'Shell'};
function labelSession(){return state.session||'Terminal';}
function summary(sessions,unavailable=false){if(unavailable)return 'Status unavailable';if(!sessions.length)return 'No sessions';return sessions.slice(0,3).map((s,i)=>{const a=agent[s.kind]||{icon:'terminal',label:'Unknown'};return `${i?'<span class="summary-dot" aria-hidden="true">·</span>':''}<span class="mark">${s.kind==='shell'?'':icon(a.icon)}<span>${e(a.label)}${s.count>1?' ×'+s.count:''}</span></span>`;}).join('')+(sessions.length>3?`<span>+${sessions.length-3}</span>`:'');}
function renderButton(b){let label=b.label;if(b.route==='action:start-session')label='Start '+agentFull[state.engine];return `<button class="ps-button ${e(b.variant||'primary')}" ${goAttrs(b.route)}>${e(label)}</button>`;}
function renderHeader(s,sheet=false){let title=s.title,sub=s.subtitle;if(state.dynamic){if(['workspace','workspace-empty','workspace-actions','terminal','composer','dictation','attachment','reconnecting'].includes(s.id)){title=state.workspace;sub=s.id.startsWith('workspace')?`${state.host} · ${state.root}`:`${state.host} · ${s.id==='reconnecting'?'Reconnecting':'Connected'}`;}if(['host-tools','workspaces','workspace-search','host-empty','root-session'].includes(s.id))title=state.host;if(s.id==='root-actions')title=state.root;if(s.id==='terminal-actions')title=labelSession();if(s.id==='end-session')title='End '+labelSession()+'?';if(['file-actions','markdown','source'].includes(s.id))title=state.fileName;if(s.id==='editor')title='Edit '+state.fileName;if(s.id==='delete-file')title='Delete '+state.fileName+'?';}
return `<header class="header ${!s.back&&!sheet?'no-back':''}">${sheet?'':(s.back?iconButton('back','back','Back'):'')}<div class="header-copy"><h1>${e(title)}</h1>${sub?`<div class="subtitle">${s.status==='connected'?'<span class="status-dot"></span>':''}<span>${e(sub)}</span></div>`:''}</div>${sheet?iconButton('close','back','Close'):s.headerAction?iconButton(s.headerAction,s.headerRoute,s.headerAction==='more'?'More actions':s.headerAction==='plus'?'Add':'Refresh'):''}</header>`;}
function renderBlock(b,s,idx=0){
 const dyn=state.dynamic;const id=`f-${s.id}-${b.id||idx}`;let title=b.title,sub=b.subtitle;
 if(b.type==='row'){
  if(dyn&&s.id==='add-workspace'&&title==='Start session in ~/git')title='Start session in '+state.root;
  let tag=b.route?'button':'div';
  return `<${tag} class="ps-row ${b.danger?'danger':''}" ${goAttrs(b.route)} data-title="${e(title)}" data-icon="${e(b.icon||'')}" data-filter="${e(title)}">${b.icon?icon(b.icon,'row-leading'):''}<span class="row-main"><span class="row-title">${e(title)}</span>${sub?`<span class="row-subtitle">${e(sub)}</span>`:''}</span>${b.trailing?`<span class="trailing">${e(b.trailing)}</span>`:''}${b.route?icon('chevron','row-chevron'):''}</${tag}>`;
 }
 switch(b.type){
 case 'workspace':{
  let sessions=b.sessions;const key=b.path||(b.root||state._renderRoot||'~/git')+'/'+b.name;
  if(dyn&&state.sessionOverrides[key]){const agg={};state.sessionOverrides[key].forEach(x=>agg[x.kind]=(agg[x.kind]||0)+1);sessions=Object.entries(agg).map(([kind,count])=>({kind,count}));}
  return `<button class="ps-row workspace-row" ${goAttrs(b.route)} data-workspace="${e(b.name)}" data-path="${e(key)}" data-root="${e(b.root||state._renderRoot||'~/git')}" data-filter="${e(b.name)}"><span class="row-main"><span class="row-title">${e(b.name)}</span><span class="session-summary">${summary(sessions,s.id==='host-offline')}</span></span>${icon('chevron','row-chevron')}</button>`;
 }
 case 'section':{
  if(!b.title)return '<div class="section-space"></div>';
  const root=b.title.startsWith('~/')||b.title.startsWith('/');if(root)state._renderRoot=b.title;
  return `<div class="section-head">${root?`<button class="section-title root-button" data-go="root-actions" data-root="${e(b.title)}" aria-label="${e(b.title)} root actions">${e(b.title)}</button>`:`<span class="section-title">${e(b.title)}</span>`}${b.action?`<button class="add-link" ${goAttrs(b.route)} data-root="${e(b.title)}" aria-label="${e(b.action)} workspace in ${e(b.title)}">${icon('plus')}${e(b.action)}</button>`:''}</div>`;
 }
 case 'text':{
  let text=b.text;if(dyn&&['end-session','session-ended','remove-workspace'].includes(s.id))text=text.replaceAll('pocketshell',state.workspace).replaceAll('Terminal',labelSession()).replaceAll('hetzner',state.host);if(dyn&&s.id==='new-session'&&text==='In pocketshell')text='In '+state.workspace;
  if(dyn&&s.id==='create-file-folder'&&text.startsWith('Creates '))text=`Creates ${state.filePath}/${state.fields['file-folder-name']??'notes'} on ${state.host}.`;
  if(dyn&&s.id==='create-folder'&&text.startsWith('Creates '))text=`Creates ${state.parent}/${state.fields['new-folder-name']||'new-project'} on ${state.host}.`;
  return `<p class="${e(b.tone||'secondary')}" ${text.startsWith("Creates ")?'data-create-location':''}>${e(text)}</p>`;
 }
 case 'button':return renderButton(b);
 case 'search':return `<label class="search">${icon('search')}<input type="search" data-search="${e(b.id)}" aria-label="${e(b.placeholder)}" placeholder="${e(b.placeholder)}" value="${e(b.value||'')}" autocomplete="off" spellcheck="false"></label>`;
 case 'field':{
  let val=state.fields[b.id]??b.value;const multi=['multiline','password-multiline','editor'].includes(b.kind);const typ=b.kind==='number'?'number':b.kind.includes('password')?'password':'text';
  return `<label class="field" for="${e(id)}"><span>${e(b.label)}</span>${multi?`<textarea class="${b.kind==='editor'?'editor':''}" id="${e(id)}" data-field="${e(b.id)}" placeholder="${e(b.placeholder)}" spellcheck="false">${e(val)}</textarea>`:`<input id="${e(id)}" data-field="${e(b.id)}" type="${typ}" value="${e(val)}" placeholder="${e(b.placeholder)}" autocomplete="off" spellcheck="false">`}${b.helper?`<small>${e(b.helper)}</small>`:''}</label>`;
 }
 case 'location':{
  let path=b.path;if(dyn&&['add-workspace','root-actions'].includes(s.id))path=state.root;if(dyn&&['create-folder','create-folder-error'].includes(s.id))path=state.parent;if(dyn&&s.id==='folder-browser')path=state.folder;if(dyn&&['files','files-folder','files-parent','create-file-folder'].includes(s.id))path=state.filePath;
  return `<div class="location"><span>${e(path)}</span>${b.action?`<button class="text-action" ${goAttrs(b.route)}>${e(b.action)}</button>`:''}</div>`;
 }
 case 'disclosure':return `<details><summary>${e(b.title)}${icon('down')}</summary>${b.children.map((c,i)=>renderBlock(c,s,idx+'d'+i)).join('')}</details>`;
 case 'alert':return `<div class="alert ${e(b.tone)}" role="status"><strong class="alert-title">${e(b.title)}</strong>${b.text?`<p>${e(b.text)}</p>`:''}${b.action?`<button class="text-action" ${goAttrs(b.route)}>${e(b.action)}</button>`:''}</div>`;
 case 'code':return `<pre>${e(b.text)}</pre>`;
 case 'empty':return `<div class="empty">${b.icon?icon(b.icon):''}<h2>${e(b.title)}</h2><p>${e(b.text)}</p></div>`;
 case 'choice':{
  let selected=s.id==='new-session'?state.engine===b.value:state.choices[s.id]?state.choices[s.id]===b.title:b.selected;
  return `<button class="ps-row choice" role="radio" aria-checked="${!!selected}" data-choice="${e(b.value||b.title)}" data-title="${e(b.title)}" ${goAttrs(b.route)}>${b.icon?icon(b.icon,'row-leading'):''}<span class="row-main"><span class="row-title">${e(b.title)}</span>${b.subtitle?`<span class="row-subtitle">${e(b.subtitle)}</span>`:''}</span><span class="radio ${selected?'selected':''}"></span></button>`;
 }
 case 'toggle':return `<button class="ps-row" role="switch" aria-checked="${!!b.value}" data-toggle="${e(b.title)}"><span class="row-main"><span class="row-title">${e(b.title)}</span>${b.subtitle?`<span class="row-subtitle">${e(b.subtitle)}</span>`:''}</span><span class="toggle ${b.value?'on':''}"></span></button>`;
 case 'slider':return `<div class="range"><label for="${e(id)}"><span>${e(b.title)}</span><output>${b.value}${e(b.unit)}</output></label><input id="${e(id)}" data-unit="${e(b.unit)}" type="range" min="${b.min}" max="${b.max}" value="${b.value}" aria-label="${e(b.title)}"></div>`;
 case 'progress':return `<div class="progress-panel"><h2>${e(b.title)}</h2><div class="progress-rail"><div class="progress-fill indeterminate"></div></div><p class="secondary">${e(b.text)}</p></div>`;
 case 'tabs':return `<div class="tabs" role="tablist">${b.items.map(x=>`<button role="tab" aria-selected="${x.active}" class="${x.active?'active':''}" ${goAttrs(x.route)}>${e(x.label)}</button>`).join('')}</div>`;
 case 'document':return `<article class="document"><h2>${e(b.title)}</h2>${b.paragraphs.map(p=>`<p>${e(p)}</p>`).join('')}<h3>${e(b.heading)}</h3><pre>${e(b.code)}</pre><p>${e(b.after)}</p></article>`;
 case 'imagepreview':return `<div class="image-preview" data-zoom tabindex="0" role="button" aria-label="Zoom image"><img src="${REFERENCE_IMAGE}" alt="Approved flat PocketShell host workspace design"></div>`;
 case 'quota':return `<div class="quota"><h2>${icon(b.icon)}${e(b.title)}</h2><div class="progress-rail"><div class="progress-fill" style="width:${b.percent}%"></div></div><p>${e(b.detail)}</p></div>`;
 case 'transfer':return `<div class="transfer"><h2>${e(b.title)}</h2><div class="meta">${e(b.text)} · ${b.percent}%</div><div class="progress-rail"><div class="progress-fill" style="width:${b.percent}%"></div></div></div>`;
 case 'keygrid':return `<div class="key-grid">${b.keys.map(k=>`<button data-go="toast:${e(k)} sent (demo only)">${e(k)}</button>`).join('')}</div>`;
 case 'order':return `<div class="order-list">${(state.order.length?state.order:b.names).map((name,i)=>`<div class="order-row"><span class="row-title">${e(name)}</span><button class="icon-button" data-move="-1" data-index="${i}" aria-label="Move ${e(name)} up">${icon('up')}</button><button class="icon-button" data-move="1" data-index="${i}" aria-label="Move ${e(name)} down">${icon('down')}</button></div>`).join('')}</div>`;
 case 'sessionbar':return `<div class="session-bar"><button ${goAttrs(b.route)}><span class="session-name">${e(dyn?labelSession():b.label)}<small>${e(dyn?agentFull[state.engine]:b.program)}</small></span><span class="count">${dyn?getSessions().length||1:b.count}</span>${icon('down')}</button></div>`;
 case 'terminal':return `<div class="terminal-output" aria-label="Terminal output (example)">${b.lines.map(l=>`<span class="term-line ${l.style==='muted'?'dim':''}">${e(l.text)||' '}</span>`).join('')}</div>`;
 case 'launcher':return `<div class="launcher"><button class="open-composer" data-go="composer">${icon('edit')}<span>${b.disabled?'Edit local draft':'Write input…'}</span></button>${iconButton('mic','dictation','Dictate input')}${iconButton('keyboard','hotkeys','Terminal keys')}</div>`;
 case 'composer':{
  const draft=state.drafts[state.path]??b.draft;
  return `<div class="compose-panel"><div class="compose-target"><span>To ${e(labelSession())} · ${e(agentFull[state.engine])}</span>${iconButton('close','terminal','Close input')}</div>${b.mode==='attachment'?`<div class="attach-row">${icon('paperclip')}<span>workspace.png · Ready</span>${iconButton('close','composer','Remove attachment')}</div>`:''}${b.mode==='voice'?`<div class="voice">${icon('mic')}Listening <span class="meta">00:12</span></div><div class="voice-line"></div>`:''}<textarea class="draft" data-draft aria-label="Input draft" spellcheck="false">${e(draft)}</textarea><div class="compose-actions">${b.mode==='voice'?`<button class="mini-button" data-go="terminal">Cancel</button><div class="spacer"></div><button class="mini-button primary" data-go="composer">Stop and review</button>`:`${iconButton('plus','composer-tools','Add to input')}${iconButton('mic','dictation','Dictate input')}<div class="spacer"></div><button class="mini-button" data-go="action:paste">Paste</button><button class="mini-button primary" data-go="action:send">Send</button>`}</div></div>`;
 }
 case 'keyboard':return `<div class="keyboard" aria-hidden="true"><div class="keyboard-label">Android keyboard · System-owned</div>${['qwertyuiop','asdfghjkl','zxcvbnm'].map((r,i)=>`<div class="keyline" style="padding:0 ${i===1?12:i===2?36:0}px">${Array.from(r).map(c=>`<i>${c}</i>`).join('')}</div>`).join('')}<div class="keyline"><i>?123</i><i class="space-key">space</i><i>↵</i></div></div>`;
 default:return `<p>${e(b.type)}</p>`;
 }
}
function getSessions(){if(state.sessionOverrides[state.path])return state.sessionOverrides[state.path];if(state.workspace==='pocketshell')return[{name:'Terminal',kind:'claude'},{name:'Terminal 2',kind:'shell'}];if(state.workspace==='pocketshell-desktop')return[{name:'Terminal',kind:'shell'},{name:'Terminal 2',kind:'shell'}];if(state.workspace==='ml-experiments')return[{name:'Terminal',kind:'claude'},{name:'Terminal 2',kind:'codex'}];if(state.workspace==='client-projects')return[{name:'Terminal',kind:'shell'}];if(state.workspace==='personal-site')return[{name:'Terminal',kind:'opencode'}];return[];}
function renderBody(s){state._renderRoot='~/git';let blocks=[...s.blocks];
 if(state.dynamic&&s.id==='files'&&state.extraFileFolders.length)blocks=state.extraFileFolders.filter(f=>f.path.slice(0,f.path.lastIndexOf('/'))===state.filePath).map(f=>({type:'row',title:f.name,subtitle:'Folder',route:'files-folder',icon:'folder'})).concat(blocks);
 if(state.dynamic&&s.id==='file-actions'&&state.fileName.endsWith('.png'))blocks=blocks.filter(b=>!['Edit','Preview'].includes(b.title));
 if(state.dynamic&&s.id==='source'&&!state.fileName.endsWith('.md'))blocks=[{type:'code',text:'plugins {\n    id("com.android.application")\n    kotlin("android")\n}\n\nandroid {\n    namespace = "com.pocketshell"\n}'}];
 if(state.dynamic&&s.id==='workspaces'&&state.extraWorkspaces.length){blocks.push({type:'section',title:'Added in this preview'});state.extraWorkspaces.forEach(w=>blocks.push({type:'workspace',name:w.name,root:w.root,path:w.path,sessions:[],route:'workspace-empty'}));}
 if(state.dynamic&&['workspace','session-switch'].includes(s.id)){blocks=blocks.filter(b=>!(b.type==='row'&&b.route==='terminal'));blocks=getSessions().map(t=>({type:'row',title:t.name,subtitle:agentFull[t.kind]+' · Running',route:'terminal',icon:agent[t.kind]?.icon||'terminal'})).concat(blocks);}
 if(s.id==='folder-browser'){blocks=blocks.filter(b=>!(b.type==='row'&&['benchmarks','notes'].includes(b.title)));if(state.folder.endsWith('/experiments'))blocks.splice(2,0,{type:'row',title:'benchmarks',route:'action:drill'},{type:'row',title:'notes',route:'action:drill'});}
 return blocks.map((b,i)=>renderBlock(b,s,i)).join('');
}
function plainScreen(s){if(s.layout==='terminal'){
 const top=s.blocks.filter(b=>!['composer','keyboard'].includes(b.type)).map((b,i)=>b.type==='alert'?`<div class="terminal-alert">${renderBlock(b,s,i)}</div>`:renderBlock(b,s,i)).join('');
 const overlay=s.blocks.filter(b=>['composer','keyboard'].includes(b.type));
 return `<div class="screen terminal-screen">${renderHeader(s)}<div class="terminal-body">${top}${overlay.length?`<div class="input-overlay">${overlay.map((b,i)=>renderBlock(b,s,i)).join('')}</div>`:''}</div></div>`;
 }
 return `<div class="screen">${renderHeader(s)}<main class="content" aria-label="${e(s.title)} content">${renderBody(s)}</main>${s.footer.length?`<footer class="footer">${s.footer.map(renderButton).join('')}</footer>`:''}</div>`;
}
function renderScreen(s){if(s.layout==='sheet'){const base=BYID[s.base]||BYID.workspaces;return `<div class="screen"><div class="sheet-base" inert aria-hidden="true">${plainScreen(base)}</div><div class="scrim" data-go="back" aria-hidden="true"></div><section class="sheet" role="dialog" aria-modal="true" aria-label="${e(s.title)}"><div class="handle"></div>${renderHeader(s,true)}<div class="content">${renderBody(s)}</div>${s.footer.length?`<footer class="footer">${s.footer.map(renderButton).join('')}</footer>`:''}</section></div>`;}return plainScreen(s);}
function statusBar(){return `<div class="statusbar" aria-hidden="true"><span>9:41</span><span class="status-icons"><svg viewBox="0 0 16 16" fill="currentColor"><path d="M1 13h3V10H1zM5 13h3V7H5zM9 13h3V4H9zM13 13h2V1h-2z"/></svg>${icon('wifi')}<svg class="battery" viewBox="0 0 24 12" fill="none" stroke="currentColor"><rect x="1" y="1" width="19" height="10" rx="2"/><path d="M22 4v4" stroke-width="2"/><rect x="3" y="3" width="13" height="6" fill="currentColor" stroke="none"/></svg></span></div>`;}
function renderApp(s){return `<div class="app" data-screen="${e(s.id)}" style="--device-width:${state.width}px;--font-scale:${state.scale}">${statusBar()}${renderScreen(s)}<div class="navbar" aria-hidden="true"><span class="nav-pill"></span></div><div class="toast hidden" role="status"></div></div>`;}
function updateMeta(s){if(capture)return;document.querySelector('#bench-name').textContent=s.title;document.querySelector('#bench-group').textContent=`${s.group} / ${String(s.index).padStart(2,'0')}`;document.querySelector('#bench-description').textContent=s.description;document.querySelectorAll('.rail-link').forEach(el=>el.classList.toggle('active',el.dataset.screenLink===s.id));
 document.querySelector('#inspector').innerHTML=`<h3>The job</h3><p>${e(s.job)}</p><h3>Interaction contract</h3>${s.notes.map((n,i)=>`<div class="rule"><b>${String(i+1).padStart(2,'0')}</b><p>${e(n)}</p></div>`).join('')}<h3>Android mapping</h3><p><code>${e(s.source)}</code></p><p>Reuse the shared theme, rows, fields and sheets. Bind this state to the existing ViewModel; do not port this as a WebView.</p><h3>Related states</h3>${[...new Set([s.back,s.layout==='sheet'?s.base:'',...s.blocks.map(b=>b.route),...s.footer.map(b=>b.route)])].filter(x=>BYID[x]).slice(0,7).map(x=>`<button class="flow-link" data-screen-link="${e(x)}">${e(BYID[x].title)}</button>`).join('')}<div class="prototype-note">${e(CATALOG.disclaimer)}</div>`;
}
function paint(){const s=BYID[state.screen]||BYID.workspaces;if(capture){document.querySelector('#capture-mount').innerHTML=renderApp(s);return;}if(state.mode==='prototype'){document.querySelector('#app-mount').innerHTML=renderApp(s);document.querySelector('#device-caption').textContent=`${state.width} × 915 dp reference · ${Math.round(state.scale*100)}% type simulation`;updateMeta(s);} }
function navigate(to,el){
 if(!to)return;if(to==='back'){const s=BYID[state.screen];to=state.history.pop()||s.back||'workspaces';state.screen=to;location.hash=to;paint();return;}
 if(to.startsWith('toast:')){showToast(to.slice(6));return;}
 const from=state.screen;let replaceNav=false;
 
 if(to==='files'&&from==='workspace')state.filePath=state.path;
 if(to==='files-parent'){state.filePath=state.filePath.slice(0,state.filePath.lastIndexOf('/'))||'~';state.dynamic=true;}
 if(to==='files-folder'){if(el?.dataset.title)state.filePath=state.filePath+'/'+el.dataset.title;state.dynamic=true;}
 if(to==='files'&&from==='files-folder'){state.filePath=state.filePath.slice(0,state.filePath.lastIndexOf('/'))||'~';state.dynamic=true;}
 if(to==='files'&&from==='files-parent'){state.filePath=state.filePath+'/'+(el?.dataset.title||'pocketshell');state.dynamic=true;}
 if(to==='create-file-folder'){state.dynamic=true;}
 if(to==='action:create-file-folder'){
  const name=(state.fields['file-folder-name']??'notes').trim();
  if(!name||['.','..'].includes(name)||/[\\/\n\r]/.test(name)){formError('Enter one folder name without slashes.');return;}
  if(state.extraFileFolders.some(f=>f.path===state.filePath+'/'+name)){formError('This folder already exists. Choose another name.');return;}
  state.extraFileFolders.push({name,path:state.filePath+'/'+name});state.dynamic=true;to='files';
 }
 if(['source','markdown','image-view'].includes(to)&&el?.dataset.title){state.fileName=el.dataset.title;state.dynamic=true;}
 if(to==='file-actions'&&from==='image-view'){state.fileName='workspace.png';state.dynamic=true;}
 if(el?.dataset.root){state.root=el.dataset.root;state.parent=state.root;state.dynamic=true;}
 if(el?.dataset.workspace){state.workspace=el.dataset.workspace;state.path=el.dataset.path||state.root+'/'+state.workspace;state.rootMode=false;state.dynamic=true;to=getSessions().length?'workspace':'workspace-empty';}
 if(to==='action:drill'){state.folder+='/'+el.dataset.title;state.screen='folder-browser';state.dynamic=true;paint();return;}
 if(to==='action:create-workspace'){
  const name=(state.fields['new-folder-name']??'new-project').trim();
  if(!name||name==='.'||name==='..'||/[\\/\n\r]/.test(name)){formError('Enter one folder name without slashes.');return;}
  if(['pocketshell','pocketshell-desktop','aplexer'].includes(name)||state.extraWorkspaces.some(w=>w.name===name&&w.root===state.parent)){navigate('create-folder-error');return;}
  state.workspace=name;state.path=state.parent+'/'+name;state.rootMode=false;state.dynamic=true;state.extraWorkspaces.push({name,root:state.root,path:state.path});state.sessionOverrides[state.path]=[];state.history=['workspaces'];replaceNav=true;to=state.createIsWorkspace?'workspace-empty':'files';
 }
 if(to==='action:start-session'){let ses=[...getSessions()];const n=ses.length+1;state.session=state.fields['session-name']||('Terminal'+(n>1?' '+n:''));ses.push({name:state.session,kind:state.engine});state.sessionOverrides[state.path]=ses;state.dynamic=true;state.history=state.rootMode?['workspaces']:['workspaces','workspace'];replaceNav=true;to='terminal';}
 if(to==='action:reuse-prompt'){state.drafts[state.path]=el?.dataset.title||'Keep the session icons muted.';to='composer';}
 if(to==='action:insert-command'){state.drafts[state.path]=(state.drafts[state.path]||'')+(el?.dataset.title||'/help');to='composer';}
 if(to==='action:paste'||to==='action:send'){const send=to.endsWith('send');state.screen='terminal';paint();showToast(send?'Input + Enter sent (demo only)':'Text pasted without Enter (demo only)');location.hash='terminal';return;}
 if(to==='workspace-empty'&&from==='add-workspace'&&el?.dataset.title&&!el.dataset.title.startsWith('Create')){state.workspace=el.dataset.title;state.path=state.root+'/'+state.workspace;state.dynamic=true;state.sessionOverrides[state.path]=[];}
 if(to==='workspace-empty'&&from==='folder-browser'){state.workspace=state.folder.split('/').pop();state.path=state.folder;state.dynamic=true;state.sessionOverrides[state.path]=[];}
 if(to==='add-workspace'){state.parent=state.root;state.folder=state.root+'/experiments';state.createIsWorkspace=true;}
 if(to==='create-folder'){state.parent=from==='folder-browser'?state.folder:from==='file-tools'?state.path:state.root;state.createIsWorkspace=from!=='file-tools';}
 if(to==='new-session'&&(from==='root-actions'||(from==='add-workspace'&&el?.dataset.title?.startsWith('Start session')))){state.rootMode=true;state.workspace=state.root;state.path=state.root;state.dynamic=true;state.sessionOverrides[state.path]??=[];}
 if(to==='terminal'&&el?.dataset.title==='In this root'){state.rootMode=true;state.workspace=state.root;state.path=state.root;state.dynamic=true;}
 if(to==='terminal'&&el?.dataset.title?.startsWith('Terminal')){state.session=el.dataset.title;state.engine=getSessions().find(t=>t.name===state.session)?.kind||'shell';state.dynamic=true;}
 if(to==='session-ended'){state.sessionOverrides[state.path]=getSessions().filter(t=>t.name!==state.session);}
 if(to==='folder-browser'&&from==='files'&&el?.dataset.title){state.folder=state.path+'/'+el.dataset.title;state.dynamic=true;}
 if(!BYID[to]){showToast('This native handoff is described in the screen notes.');return;}
 if(from!==to&&!replaceNav)state.history.push(from);state.from=from;state.screen=to;state.mode='prototype';location.hash=to;showMode('prototype',false);paint();
}
let toastTimer;function showToast(text){clearTimeout(toastTimer);const box=document.querySelector(capture?'#capture-mount .toast':'#app-mount .toast');if(!box)return;box.textContent=text;box.classList.remove('hidden');toastTimer=setTimeout(()=>box.classList.add('hidden'),3200);}
function formError(msg){const root=document.querySelector(capture?'#capture-mount .sheet .content':'#app-mount .sheet .content');if(!root)return;root.querySelector('.form-error')?.remove();root.insertAdjacentHTML('beforeend',`<p class="form-error" role="alert">${e(msg)}</p>`);}
function reset(){Object.assign(state,{root:'~/git',workspace:'pocketshell',path:'~/git/pocketshell',parent:'~/git',folder:'~/git/experiments',createIsWorkspace:true,rootMode:false,engine:'claude',session:'Terminal',fields:{},drafts:{},extraWorkspaces:[],sessionOverrides:{},history:[],choices:{},order:[],dynamic:false,fileName:'README.md',filePath:'~/git/pocketshell',extraFileFolders:[]});}
function renderRail(query=''){const groups=[...new Set(CAT.map(s=>s.group))];document.querySelector('#rail-list').innerHTML=groups.map(g=>{const list=CAT.filter(s=>s.group===g&&`${s.title} ${s.id} ${s.source}`.toLowerCase().includes(query.toLowerCase()));return list.length?`<div class="rail-group">${e(g)}</div>${list.map(s=>`<button class="rail-link ${s.id===state.screen?'active':''}" data-screen-link="${e(s.id)}"><span>${String(s.index).padStart(2,'0')}</span>${e(s.title)}</button>`).join('')}`:'';}).join('');}
function showMode(mode,paintNow=true){if(capture)return;state.mode=mode;document.querySelectorAll('[data-mode]').forEach(b=>b.classList.toggle('active',b.dataset.mode===mode));document.querySelector('#prototype-view').hidden=mode!=='prototype';document.querySelector('#gallery-view').hidden=mode!=='gallery';document.querySelector('#system-view').hidden=mode!=='system';if(mode==='gallery')renderGallery();if(mode==='system')renderSystem();if(paintNow)paint();}
function renderGallery(){const prev={...state};state.dynamic=false;state.width=412;state.scale=1;document.querySelector('#gallery-grid').innerHTML=CAT.map(s=>`<button class="gallery-card" data-screen-link="${e(s.id)}"><div class="gallery-frame" aria-hidden="true">${renderApp(s)}</div><b>${String(s.index).padStart(2,'0')} / ${e(s.title)}</b><small>${e(s.layout)} · ${e(s.id)}</small></button>`).join('');Object.assign(state,prev);}
function renderSystem(){document.querySelector('#system-content').innerHTML=`<div class="eyebrow">PocketShell Quiet / 1.0.0</div><h1 class="bench-title">One system. Every screen.</h1><p>The approved host screen is the anchor. The same tokens, rows, type hierarchy, form fields, sheets and state vocabulary govern every surface. Browser layouts are a review tool; Jetpack Compose is the implementation target.</p><div class="spec-grid"><section class="spec-panel"><h2>Content before controls</h2><p>Host → root → workspace → session → terminal. No conversation mode. No duplicate paths. No per-row colored logos, nested cards, or competing dashboards.</p></section><section class="spec-panel"><h2>Quiet, not tiny</h2><p>20sp workspace names. 18sp body. 16sp metadata. Muted content remains legible. 48dp minimum targets; metadata marks are not buttons.</p></section></div><h2>Color roles</h2><div class="swatches">${Object.entries(TOKENS.color).filter(([k])=>k!=='scrim').map(([name,val])=>`<div class="swatch"><i style="background:${val}"></i><div>${e(name)}<code>${val}</code></div></div>`).join('')}</div><h2>Typography</h2><table class="system-table"><tr><th>Role</th><th>Size / leading</th><th>Weight</th></tr>${Object.entries(TOKENS.type).map(([role,t])=>`<tr><td>${role}</td><td>${t.sizeSp} / ${t.lineHeightSp} sp</td><td>${t.weight}</td></tr>`).join('')}</table><h2>Component contracts</h2><div class="spec-grid"><div class="spec-panel"><h2>Lists</h2><p>Flat row, hairline divider, 20dp outer gutter. Workspace names wrap; never shrink to fit. Session kinds form one muted summary under the name. One row is one tap target.</p></div><div class="spec-panel"><h2>Forms</h2><p>Persistent field labels. 56dp minimum fields. A single primary footer action. Supporting and error copy next to the input. More options starts collapsed.</p></div><div class="spec-panel"><h2>Sheets</h2><p>Shared 24dp corners, neutral surface, close at top-right. One sheet at a time. Independent scrolling and pinned action area. Native ModalBottomSheet owns modal semantics.</p></div><div class="spec-panel"><h2>Terminal</h2><p>Existing emulator. Fixed cell geometry. Input floats above the terminal and the keyboard. Never apply app-wide keyboard padding to the terminal viewport.</p></div></div><h2>Session identifiers</h2><p>The desktop maps ordinary shapes to agents: hexagon → Claude, code → Codex, terminal → OpenCode, bolt → Grok. This kit reuses that mapping and keeps names beside marks. A shell is labelled Terminal, without a competing logo. These are not vendor brand assets.</p><h2>What the prototype does not do</h2><p>${e(CATALOG.disclaimer)} Native system surfaces — keyboard, camera permissions, biometrics, document picker and Sharesheet — are contracts, not custom app screens.</p><h2>Android handoff</h2><p>See android/PocketShellTheme.kt, PocketShellComponents.kt, MockupCatalog.kt and AndroidHandoff.md. Browser CSS pixels model dp at the base viewport; Android text uses sp and native font scaling. Validate on an emulator rather than treating browser line breaks as a pixel-perfect oracle.</p>`;}
function handleClick(ev){const target=ev.target.closest('button,[data-go],[data-zoom]');if(!target)return;
 if(target.dataset.mode){showMode(target.dataset.mode);return;}
 if(target.dataset.screenLink){reset();state.screen=target.dataset.screenLink;state.mode='prototype';location.hash=state.screen;showMode('prototype');return;}
 if(target.hasAttribute('data-zoom')){target.classList.toggle('zoomed');return;}
 if(target.dataset.toggle){let on=target.getAttribute('aria-checked')!=='true';target.setAttribute('aria-checked',String(on));target.querySelector('.toggle').classList.toggle('on',on);if(target.dataset.toggle==='Discover services')navigate(on?'services-active':'services');return;}
 if(target.dataset.move){let arr=state.order.length?state.order:[...BYID.reorder.blocks.find(b=>b.type==='order').names];let i=Number(target.dataset.index),j=i+Number(target.dataset.move);if(j>=0&&j<arr.length)[arr[i],arr[j]]=[arr[j],arr[i]];state.order=arr;paint();return;}
 if(target.dataset.choice&&!target.dataset.go){if(state.screen==='new-session')state.engine=target.dataset.choice;else state.choices[state.screen]=target.dataset.title;paint();return;}
 if(target.dataset.go)navigate(target.dataset.go,target);
}
function handleInput(ev){const t=ev.target;if(t.dataset.field!==undefined)state.fields[t.dataset.field]=t.value;if(t.hasAttribute('data-draft'))state.drafts[state.path]=t.value;
 if(['new-folder-name','file-folder-name'].includes(t.dataset.field)){const p=document.querySelector('[data-create-location]');if(p)p.textContent='Creates '+(t.dataset.field==='file-folder-name'?state.filePath:state.parent)+'/'+t.value+' on '+state.host+'.';}
 if(t.type==='range'){t.parentElement.querySelector('output').textContent=t.value+t.dataset.unit;}
 if(t.dataset.search){const root=t.closest('.content'),q=t.value.toLowerCase();root.querySelectorAll('[data-filter]').forEach(el=>el.hidden=!el.dataset.filter.toLowerCase().includes(q));root.querySelectorAll('.section-head').forEach(h=>{let next=h.nextElementSibling,has=false;while(next&&!next.classList.contains('section-head')){if(next.matches('[data-filter]')&&!next.hidden)has=true;next=next.nextElementSibling;}h.hidden=!!q&&!has;});const visible=[...root.querySelectorAll('[data-filter]')].filter(el=>!el.hidden);root.querySelector('.no-results')?.remove();if(!visible.length&&q)root.insertAdjacentHTML('beforeend','<p class="secondary no-results">No matches. Try another name.</p>');}
 if(t.id==='rail-search')renderRail(t.value);
}
function init(){document.body.classList.toggle('capture',capture);document.addEventListener('click',handleClick);document.addEventListener('input',handleInput);document.addEventListener('keydown',ev=>{if(ev.key==='Escape')navigate('back');});state.screen=params.get('capture')||location.hash.slice(1)||'workspaces';if(!BYID[state.screen])state.screen='workspaces';
 if(!capture){renderRail();document.querySelector('#width-control').addEventListener('change',ev=>{state.width=Number(ev.target.value);paint();});document.querySelector('#scale-control').addEventListener('change',ev=>{state.scale=Number(ev.target.value);paint();});document.querySelector('#reset-control').onclick=()=>{reset();paint();};document.querySelector('#prev-control').onclick=()=>{reset();state.screen=CAT[Math.max(0,BYID[state.screen].index-2)].id;paint();};document.querySelector('#next-control').onclick=()=>{reset();state.screen=CAT[Math.min(CAT.length-1,BYID[state.screen].index)].id;paint();};document.querySelector('#frame-count').textContent=CAT.length+' SCREENS + STATES';}
 paint();window.PocketShell={catalog:CAT,tokens:TOKENS,state,reset,go:(id)=>{reset();state.screen=id;location.hash=id;paint();},render:paint};}
window.addEventListener('hashchange',()=>{const id=location.hash.slice(1);if(BYID[id]&&state.screen!==id){state.screen=id;paint();}});init();
