'use strict';
const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.hash.slice(1));
let token = params.get('token') || sessionStorage.getItem('pocketshell-ui-mock-token') || '';
if (params.has('token')) {
  sessionStorage.setItem('pocketshell-ui-mock-token', token);
  history.replaceState(null, '', location.pathname);
}
let catalog = [], selected = null, catalogRevision = 0, shownRevision = -1;
let showLog = false, objectURLs = [], initialSelectionDone = false;
const human = value => value.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/_/g, ' ');
async function api(path, body) {
  const options = {headers:{'X-UI-Mock-Token':token}, cache:'no-store'};
  if (body !== undefined) { options.method='POST'; options.headers['Content-Type']='application/json'; options.body=JSON.stringify(body); }
  const response = await fetch(path, options);
  if (response.status===401) { $('auth').hidden=false; throw new Error('Нужен актуальный токен из адреса сервера.'); }
  if (!response.ok) { const err=await response.json(); throw new Error(err.error || `HTTP ${response.status}`); }
  $('auth').hidden=true;
  return response;
}
function report(error) { $('error').textContent=String(error.message || error); $('error').hidden=false; }
function drawCatalog() {
  const query=$('search').value.toLowerCase();
  const filtered=catalog.filter(c => `${c.class_name} ${c.method} ${c.label}`.toLowerCase().includes(query));
  $('cases').replaceChildren(); let group='';
  for(const c of filtered) {
    const name=c.class_name.split('.').pop();
    if(name!==group) { const title=document.createElement('div');title.className='group';title.textContent=human(name.replace(/Renders$/, ''));$('cases').append(title);group=name; }
    const button=document.createElement('button');button.textContent=human(c.method);
    button.setAttribute('aria-current', String(c.id===selected));
    const info=document.createElement('small');info.textContent=c.kind==='ui-kit-example' ? 'UI-kit: пример, может повторять экран' : c.label;button.append(info);
    button.onclick=async()=>{try{await api('/api/select',{case_id:c.id});localStorage.setItem('pocketshell-ui-mock-case',c.id);}catch(e){report(e);}};
    $('cases').append(button);
  }
  $('count').textContent=`${filtered.length} из ${catalog.length} поддерживаемых render-сценариев`;
}
async function refreshCatalog() {
  const data=await (await api('/api/catalog')).json(); catalog=data.cases;catalogRevision=data.revision;
  $('warnings').textContent=data.warnings.join('\n');drawCatalog();
  if(!initialSelectionDone){initialSelectionDone=true;const saved=localStorage.getItem('pocketshell-ui-mock-case');if(saved&&catalog.some(c=>c.id===saved))await api('/api/select',{case_id:saved});}
}
function clearImages() { $('gallery').replaceChildren();for(const url of objectURLs)URL.revokeObjectURL(url);objectURLs=[]; }
async function showImages(state) {
  if(state.rendered_case!==state.selected){clearImages();shownRevision=-1;return;}
  if(state.image_revision===shownRevision)return;
  const next=[];
  try {
    for(let i=0;i<state.images.length;i++) {
      const response=await api(`/api/image?index=${i}&revision=${state.image_revision}`);
      const url=URL.createObjectURL(await response.blob());
      next.push({url,info:state.images[i]});
    }
    clearImages();
    for(const item of next){objectURLs.push(item.url);const figure=document.createElement('figure');const image=document.createElement('img');image.src=item.url;image.alt=`Рендер ${item.info.name}`;const caption=document.createElement('figcaption');caption.textContent=`${item.info.name} · ${item.info.width} × ${item.info.height} px`;figure.append(image,caption);$('gallery').append(figure);}
    shownRevision=state.image_revision;
  } catch(e){for(const item of next)URL.revokeObjectURL(item.url);throw e;}
}
async function poll() {
  try {
    const state=await (await api('/api/state')).json();
    if(state.catalog_revision!==catalogRevision)await refreshCatalog();
    if(selected!==state.selected){selected=state.selected;drawCatalog();}
    const current=catalog.find(c=>c.id===selected);
    $('title').textContent=current ? human(current.method) : 'Выберите сценарий';
    $('source').textContent=current ? current.source : '';
    const labels={idle:'Ожидание',queued:'Изменения в очереди',building:'Компиляция и рендер',ready:'Рендер обновлён',error:'Ошибка рендера'};
    $('status').textContent=labels[state.phase]||state.phase;
    $('error').hidden=!state.error;$('error').textContent=state.error;
    $('gallery').classList.toggle('stale',state.stale);
    $('banner').textContent=state.stale ? 'Результат ещё не обновлён. Показанное ранее изображение не подтверждает последнее изменение.' : 'Актуальный статический рендер. Для другого состояния выберите сценарий слева; клики по изображению не передаются приложению.';
    $('timing').textContent=state.last_seconds===null ? '' : `Последний завершённый рендер: ${state.last_seconds} с`;
    await showImages(state);
    if(showLog){const log=await(await api('/api/log')).json();$('log').textContent=log.lines.join('\n');}
  } catch(error){$('status').textContent='Нет актуального результата';$('gallery').classList.add('stale');report(error);}
  setTimeout(poll,1000);
}
$('search').oninput=drawCatalog;
$('rebuild').onclick=async()=>{try{await api('/api/rebuild',{});}catch(e){report(e);}};
$('log-button').onclick=()=>{showLog=!showLog;$('log').hidden=!showLog;$('log-button').setAttribute('aria-expanded',String(showLog));};
$('zoom').onclick=()=>{const fit=$('gallery').classList.toggle('fit');$('zoom').setAttribute('aria-pressed',String(!fit));};
poll();
