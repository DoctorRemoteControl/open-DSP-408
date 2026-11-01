import { DspSession, fieldView, meterReading, gainValueAt, nextGainValue } from './model.mjs';

const session=new DspSession();
const $=id=>document.getElementById(id);
const cards=new Map(), drafts=new Map(), baselines=new Map();
let structure='',filter='all',snapshotTimer,meterTimer,lastDeviceRead=0,pollingEpoch=0;
const names={gain:'Gain',mute:'Mute',phase:'Phase',delay:'Delay'};
const text=(element,value)=>{if(element.textContent!==String(value))element.textContent=String(value);};
const format=(value,digits=1)=>typeof value==='number'&&Number.isFinite(value)?value.toFixed(digits).replace('-','−'):'—';
const failure=error=>{if(!session.notice)session.notice={tone:'error',message:error.message};render();};

function ensureCards() {
  const channels=session.capabilities?.channels||[];
  const key=`${session.generation}:${channels.map(c=>c.id).join('|')}`;
  if(key===structure)return;
  structure=key;cards.clear();drafts.clear();baselines.clear();$('channels').replaceChildren();
  for(const [kind,title] of [['inputs','INPUTS'],['outputs','OUTPUTS']]) {
    const group=document.createElement('section');group.className='channel-group';group.dataset.group=kind;
    const heading=document.createElement('div');heading.className='group-header';
    const h2=document.createElement('h2');h2.textContent=title;const count=document.createElement('span');count.className='count';
    heading.append(h2,count);const grid=document.createElement('div');grid.className='channel-grid';
    const selected=channels.filter(c=>(c.index<4?'inputs':'outputs')===kind);count.textContent=selected.length;
    for(const channel of selected) {
      const card=$('channel-template').content.firstElementChild.cloneNode(true);card.dataset.channel=channel.id;
      const control=(selector)=>card.querySelector(selector);
      const name=channel.displayName||channel.id;const suffix=encodeURIComponent(channel.id);
      text(control('.channel-name'),name);text(control('.channel-index'),String(channel.index+1).padStart(2,'0'));
      text(control('.channel-kind'),kind==='inputs'?'IN':'OUT');
      const range=control('.gain-slider');range.id=`gain-${suffix}`;range.setAttribute('aria-label',`${name} gain`);control('.gain-label').htmlFor=range.id;
      const delay=control('.delay-input');delay.id=`delay-${suffix}`;delay.setAttribute('aria-label',`${name} delay in milliseconds`);control('.delay-label').htmlFor=delay.id;
      control('.level-meter').setAttribute('aria-label',`${name} level`);
      control('.mute-toggle').setAttribute('aria-label',`${name} mute`);control('.phase-toggle').setAttribute('aria-label',`${name} phase inversion`);
      control('.delay-apply').setAttribute('aria-label',`Apply ${name} delay`);
      const draft=(parameter,value)=>{
        const key=`${channel.id}:${parameter}`;
        if(!drafts.has(key))drafts.set(key,{baseline:baselines.has(key)?baselines.get(key):fieldView(session.channel(channel.id),parameter).value,value});
        return drafts.get(key);
      };
      for(const [input,parameter] of [[range,'gain'],[delay,'delay']]) {
        input.addEventListener('focus',()=>baselines.set(`${channel.id}:${parameter}`,fieldView(session.channel(channel.id),parameter).value));
        input.addEventListener('blur',()=>{if(!drafts.has(`${channel.id}:${parameter}`))baselines.delete(`${channel.id}:${parameter}`);});
      }
      range.addEventListener('input',()=>{
        try {
          const value=gainValueAt(Number(range.value),session.capability(channel.id,'gain'));
          range.value=value;draft('gain',value).value=value;renderCard(channel.id);
        } catch(error){failure(error);}
      });
      range.addEventListener('keydown',event=>{
        if(!['ArrowRight','ArrowUp','ArrowLeft','ArrowDown','PageUp','PageDown','Home','End'].includes(event.key))return;
        event.preventDefault();if(!session.canControl(channel.id,'gain'))return;
        const capability=session.capability(channel.id,'gain');
        let value=Number(range.value);
        if(event.key==='Home')value=capability.minimum;
        else if(event.key==='End')value=capability.maximum;
        else {
          const direction=['ArrowRight','ArrowUp','PageUp'].includes(event.key)?1:-1;
          for(let step=0;step<(event.key.startsWith('Page')?10:1);step++)value=nextGainValue(value,direction,capability);
        }
        if(value===Number(range.value))return;
        range.value=value;range.dispatchEvent(new Event('input',{bubbles:true}));range.dispatchEvent(new Event('change',{bubbles:true}));
      });
      range.addEventListener('change',()=>{
        const value=gainValueAt(Number(range.value),session.capability(channel.id,'gain'));range.value=value;
        const edit=draft('gain',value);edit.value=value;
        void submit(channel.id,'gain',edit.value,edit.baseline);
      });
      delay.addEventListener('input',()=>{draft('delay',delay.value).value=delay.value;});
      control('.delay-form').addEventListener('submit',event=>{
        event.preventDefault();const edit=draft('delay',delay.value);
        void submit(channel.id,'delay',delay.valueAsNumber,edit.baseline);
      });
      for(const parameter of ['mute','phase'])control(`.${parameter}-toggle`).addEventListener('click',()=>{
        const baseline=fieldView(session.channel(channel.id),parameter).value;
        void submit(channel.id,parameter,!baseline,baseline);
      });
      cards.set(channel.id,card);grid.append(card);
    }
    if(selected.length){group.append(heading,grid);$('channels').append(group);}
  }
}

function renderCard(id) {
  const card=cards.get(id);if(!card)return;
  const channel=session.channel(id);const query=selector=>card.querySelector(selector);
  const pending=session.pending?.channelId===id&&session.pending.deviceId===session.deviceId;
  card.classList.toggle('pending',pending);card.setAttribute('aria-busy',String(pending));
  const level=meterReading(session.meters?{...session.meters,connected:session.connected&&session.serverReachable}:null,id);
  card.classList.toggle('meter-stale',!level.fresh);
  query('.level-meter').value=level.value??-60;
  text(query('.meter-number'),`${format(level.value)} dB`);
  text(query('.meter-state'),level.fresh?(level.clip?'Clipping':level.limiting?'Limiter active':'Live level'):level.value===null?'Awaiting confirmed level':'Last reading · stale');
  query('.meter-state').classList.toggle('hot',level.clip||level.limiting);
  const gain=fieldView(channel,'gain'),gainDraft=drafts.get(`${id}:gain`);
  const range=query('.gain-slider'),gainCap=session.capability(id,'gain');
  range.disabled=!session.canControl(id,'gain');range.min=gainCap?.minimum??-60;range.max=gainCap?.maximum??12;
  if(gainDraft)range.value=gainDraft.value;else range.value=gain.value??range.min;
  const displayed=gainDraft?gainDraft.value:gain.value;
  text(query('.gain-value'),`${format(displayed)} dB`);
  range.setAttribute('aria-valuetext',`${format(displayed)} decibels`);
  text(query('.gain-evidence'),pending&&session.pending.parameter==='gain'?'Sending…':gainDraft?'Preview':gain.confirmed?'Read back':gain.value===null?'Unknown':'Unconfirmed');
  for(const parameter of ['mute','phase']) {
    const field=fieldView(channel,parameter),button=query(`.${parameter}-toggle`),cap=session.capability(id,parameter);
    button.disabled=!session.canControl(id,parameter);button.setAttribute('aria-pressed',String(field.value===true));
    text(button,parameter==='mute'?(field.value===true?'Muted':field.value===false?'Mute':'Set mute'):`Phase ${field.value===null?'—':field.value?'180°':'0°'}`);
    button.title=session.verifyChanges?(cap?.readbackSupported?'Write and verify from the device.':'No confirmed readback mapping is available.'):'Wait for the device ACK without an extra readback.';
  }
  const delay=query('.delay-input'),delayField=fieldView(channel,'delay'),delayCap=session.capability(id,'delay');
  const delayDraft=drafts.get(`${id}:delay`);
  delay.disabled=!session.canControl(id,'delay');query('.delay-apply').disabled=delay.disabled;
  delay.min=delayCap?.minimum??0;delay.max=delayCap?.maximum??680;
  if(delayDraft){if(document.activeElement!==delay)delay.value=delayDraft.value;}
  else if(document.activeElement!==delay)delay.value=delayField.value===null?'':Number(delayField.value.toFixed(4));
  let message,tone='';
  const outcome=session.lastResult;
  if(pending)message=`${names[session.pending.parameter]} · waiting for device response…`;
  else if(outcome?.channelId===id) {
    message=`${names[outcome.parameter]} · ${{VERIFIED:'read back',ACKNOWLEDGED:'ACK received',REJECTED:'not sent',UNCERTAIN:'outcome unknown'}[outcome.status]}`;
    if(outcome.status==='VERIFIED'||outcome.status==='ACKNOWLEDGED')message+=` · ${typeof outcome.normalizedValue==='boolean'?(outcome.normalizedValue?'on':'off'):format(outcome.normalizedValue,outcome.parameter==='delay'?3:1)}`;
    tone=outcome.status==='VERIFIED'?'success':outcome.status==='UNCERTAIN'||outcome.status==='REJECTED'?'error':'';
  } else if(gainDraft)message='Gain preview · release to send';
  else if(!session.connected)message='Connect the device to enable controls';
  else if(!session.capabilities?.firmwareRecognized)message='Firmware profile is not recognized';
  else if(session.uncertain){message='Refresh required before another write';tone='error';}
  else message=session.verifyChanges?'Readback verification enabled':'Fast control · ACK response';
  const feedback=query('.card-feedback');text(feedback,message);feedback.className=`card-feedback ${tone}`;
}

function render() {
  const selector=$('device-select');
  const optionsKey=JSON.stringify(session.devices.map(d=>[d.id,d.ip,d.port]));
  if(selector.dataset.options!==optionsKey) {
    selector.replaceChildren(...session.devices.map(device=>{const option=document.createElement('option');option.value=device.id;option.textContent=device.id+(device.defaultDevice?' · Default':'');return option;}));
    selector.dataset.options=optionsKey;
  }
  selector.value=session.deviceId||'';selector.disabled=!session.devices.length||!!session.pending||session.actionBusy;
  const device=session.devices.find(d=>d.id===session.deviceId);
  text($('device-address'),device?`${device.ip}:${device.port}`:'No device selected');
  text($('server-status'),session.serverReachable?'Server online':session.devices.length?'Server unreachable':'Finding server');
  $('server-status').className=`connection-pill ${session.serverReachable?'online':'offline'}`;
  text($('device-status'),session.actionBusy?'Working…':session.connected?'Connected':'Disconnected');
  $('device-status').className=session.connected?'ready':'';
  text($('firmware'),session.capabilities?.deviceVersion||'Not identified');
  text($('snapshot-status'),session.cache.phase==='READY'?'Ready':session.cache.phase==='STALE'?'Changed':session.cache.phase.toLowerCase());
  $('snapshot-status').className=session.cache.ready?'ready':'stale';
  const connection=$('connection-button');text(connection,session.actionBusy?'Please wait…':session.connected?'Disconnect':'Connect');
  connection.disabled=!device||!session.serverReachable||session.actionBusy||!!session.pending;
  connection.classList.toggle('disconnect',session.connected);
  $('refresh-button').disabled=!session.connected||!session.serverReachable||session.actionBusy||!!session.pending;
  $('verify-changes').checked=session.verifyChanges;$('verify-changes').disabled=!!session.pending||session.actionBusy;
  let notice=session.error?{tone:'error',message:session.error}:session.notice;
  if(session.uncertain)notice={tone:'error',message:'Write outcome uncertain. Reconnect if disconnected, then Refresh before changing another value.'};
  if(!notice&&session.connected&&session.capabilities&&!session.capabilities.firmwareRecognized)notice={tone:'notice',message:'This firmware profile is not recognized. Monitoring is available; controls are disabled.'};
  $('notice').hidden=!notice;if(notice){text($('notice'),notice.message);$('notice').className=`notice ${notice.tone}`;}
  $('cache-note').hidden=session.cache.phase!=='STALE';
  text($('cache-note'),'Changes do not wait for a full synchronization. Refresh reads all device settings only when you request it.');
  ensureCards();for(const id of cards.keys())renderCard(id);
  for(const group of $('channels').children)group.hidden=filter!=='all'&&group.dataset.group!==filter;
  text($('channel-count'),cards.size||'—');
  const empty=cards.size===0; $('empty-state').hidden=!empty;
  if(empty) {
    text($('empty-title'),session.error?'Server unavailable':session.devices.length?'Reading processor state':'No processors available');
    text($('empty-description'),session.error?'Check that this browser can reach your DSP server, then retry.':session.devices.length?'Waiting for the server’s channel and capability information.':'Configured processors will appear here. The page never connects a device automatically.');
  }
  $('retry-button').hidden=!session.error;
  if(session.error&&!empty) {
    $('cache-note').hidden=false;text($('cache-note'),'Controls are paused until the server is reachable again. Displayed readings may be old.');
  }
  const timing=session.lastInteractionTiming;
  text($('timing-status'),timing?`Last change: ${Math.round(timing.totalMs)} ms${timing.backendMs===null?'':` · server ${Math.round(timing.backendMs)} ms`}`:'');
  $('timing-status').title='Total includes browser, network and response handling. Server time includes queueing and the DSP; their difference is not a pure Wi-Fi measurement.';
  text($('footer-status'),session.connected?`${session.deviceId} · ${cards.size} channels · local network`:'Local network · device disconnected');
}

async function submit(id,parameter,value,baseline) {
  const focused=document.activeElement,generation=session.generation;
  try {await session.write(id,parameter,value,baseline);}catch(error){failure(error);}
  finally{
    drafts.delete(`${id}:${parameter}`);baselines.delete(`${id}:${parameter}`);render();
    if(generation===session.generation&&document.activeElement===document.body&&focused?.isConnected&&!focused.disabled)focused.focus({preventScroll:true});
  }
}
async function readSnapshot(epoch) {
  if(document.hidden||epoch!==pollingEpoch)return;
  try {
    if(!session.deviceId||Date.now()-lastDeviceRead>10000){await session.loadDevices();lastDeviceRead=Date.now();}
    await session.pollSnapshot();
  } catch { /* The model publishes its connection/error state. */ }
  finally{if(!document.hidden&&epoch===pollingEpoch)snapshotTimer=setTimeout(()=>readSnapshot(epoch),1500);}
}
async function readMeters(epoch) {
  if(document.hidden||epoch!==pollingEpoch)return;
  try{await session.pollMeters();}catch{/* The next bounded poll can recover; writes are never retried. */}
  finally{if(!document.hidden&&epoch===pollingEpoch)meterTimer=setTimeout(()=>readMeters(epoch),400);}
}
function stopPolling(){pollingEpoch++;clearTimeout(snapshotTimer);clearTimeout(meterTimer);}
function startPolling(){stopPolling();const epoch=pollingEpoch;void readSnapshot(epoch);void readMeters(epoch);}
$('device-select').addEventListener('change',()=>{session.selectDevice($('device-select').value);startPolling();});
$('connection-button').addEventListener('click',()=>{void session.connection(session.connected?'disconnect':'connect').catch(failure);});
$('refresh-button').addEventListener('click',()=>{void session.refreshDevice().catch(failure);});
$('verify-changes').addEventListener('change',()=>session.setVerification($('verify-changes').checked));
$('retry-button').addEventListener('click',()=>{lastDeviceRead=0;startPolling();});
for(const button of document.querySelectorAll('[data-filter]'))button.addEventListener('click',()=>{
  filter=button.dataset.filter;for(const tab of document.querySelectorAll('[data-filter]'))tab.setAttribute('aria-pressed',String(tab===button));render();
});
document.addEventListener('visibilitychange',()=>{if(document.hidden)stopPolling();else startPolling();});
window.addEventListener('pagehide',stopPolling);
window.addEventListener('pageshow',event=>{if(event.persisted)startPolling();});
session.subscribe(render);render();startPolling();
