import test from 'node:test';
import assert from 'node:assert/strict';
import { DspSession, ApiError, HttpApi, fieldView, meterReading } from '../../main/resources/web/model.mjs';

const clone = value => structuredClone(value);
const channel = {id:'out8',displayName:'Out8',index:11,gainDb:-6,muted:false,phaseInverted:false,delayMs:20,
  gainConfirmedFromDevice:true,muteConfirmedFromDevice:true,phaseConfirmedFromDevice:true,delayConfirmedFromDevice:true};
const capability = name => ({name,valueType:['mute','phase'].includes(name)?'boolean':'number',
  writable:true,readbackSupported:true,minimum:name==='gain'?-60:0,maximum:name==='gain'?12:680,resolution:name==='gain'?[{fromInclusive:-60,toInclusive:-20,step:0.5},{fromInclusive:-20,toInclusive:12,step:0.1}]:[]});
function fixture() {
  const calls=[];
  const api={ async request(path, options={}) {
    calls.push({path,...options});
    if(path==='/api/v1/dsps')return {defaultDsp:'main',dsps:[{id:'main',ip:'127.0.0.1',port:9761}]};
    if(path.endsWith('/capabilities'))return {dspId:'main',connected:true,firmwareRecognized:true,deviceVersion:'FIR408 V0113P',channels:[{id:'out8',parameters:['gain','mute','phase','delay'].map(capability)}]};
    if(path.endsWith('/channels'))return {connected:true,channels:[clone(channel)]};
    if(path.endsWith('/settings-cache'))return {phase:'READY',ready:true,cachedBlockCount:32};
    if(path.endsWith('/refresh'))return {ok:true};
    if(path.endsWith('/parameters/write'))return result(options.body);
    throw new Error('Unexpected path '+path);
  }};
  return {api,calls,session:new DspSession(api)};
}
function result(body, status=body.verify===false?'ACKNOWLEDGED':'VERIFIED') {
  return {requestId:'request-1',dspId:'main',channelId:body.channelId,parameter:body.parameter,
    requestedValue:body.value,normalizedValue:body.value,status,acknowledged:status!=='REJECTED',
    transmitted:status!=='REJECTED',readback:status==='VERIFIED'?{value:body.value,receiveRevision:2,observedAtEpochMs:100,blockIndex:'1C'}:null,
    errorCode:status==='UNCERTAIN'?'readback_mismatch':null};
}
async function ready(f=fixture(), verify=true) { await f.session.loadDevices(); await f.session.pollSnapshot();f.session.setVerification(verify);return f; }
const deferred=()=> {let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};};

test('page initialization uses cached GETs and never connects, refreshes or writes hardware',async()=>{
 const f=await ready();assert.equal(f.session.connected,true);assert.equal(f.session.channels[0].gainDb,-6);
 assert.ok(f.calls.every(c=>!c.method||c.method==='GET'));
 assert.ok(!f.calls.some(c=>c.path.endsWith('/refresh')));
});
test('verified write preflights the displayed baseline and marks full snapshot stale',async()=>{
 const f=await ready();const r=await f.session.write('out8','gain',-12,-6);
 assert.equal(r.status,'VERIFIED');assert.equal(f.session.cache.phase,'STALE');
 assert.equal(f.session.channels[0].gainDb,-12);assert.equal(f.session.channels[0].gainConfirmedFromDevice,true);
 assert.deepEqual(f.calls.at(-1).body,{channelId:'out8',parameter:'gain',value:-12,verify:true});
});
test('unknown firmware, offline, unknown values and unsupported parameters cannot transmit',async()=>{
 for(const change of [s=>s.capabilities.firmwareRecognized=false,s=>s.connected=false,s=>s.channels[0].gainDb=null,s=>s.capabilities.channels[0].parameters[0].writable=false]) {
  const f=await ready();change(f.session);await assert.rejects(f.session.write('out8','gain',-12,-6));
  assert.equal(f.calls.filter(c=>c.method==='POST').length,0);
 }
});
test('blank, nonfinite, boolean-as-number and out-of-range values reject before requests',async()=>{
 for(const value of ['',null,true,NaN,Infinity,13,-61]) {
  const f=await ready();const count=f.calls.length;await assert.rejects(f.session.write('out8','gain',value,-6));assert.equal(f.calls.length,count);
 }
});
test('optional strict verification disables unsupported readback; normal ACK controls stay unconfirmed',async()=>{
 const f=await ready();f.session.capabilities.channels[0].parameters.find(p=>p.name==='mute').readbackSupported=false;
 await assert.rejects(f.session.write('out8','mute',true,false));
 const original=f.api.request;f.api.request=async(p,o)=>p.endsWith('/parameters/write')?result(o.body,'ACKNOWLEDGED'):original(p,o);
 f.session.setVerification(false);const r=await f.session.write('out8','mute',true,false);
 assert.equal(r.status,'ACKNOWLEDGED');assert.equal(f.session.channels[0].muted,true);assert.equal(f.session.channels[0].muteConfirmedFromDevice,false);
});
test('concurrent edit is visible and does not issue a POST',async()=>{
 const f=await ready();const original=f.api.request;
 f.api.request=async(p,o)=>p.endsWith('/channels')?{connected:true,channels:[{...channel,gainDb:-3}]}:original(p,o);
 await assert.rejects(f.session.write('out8','gain',-12,-6),/changed elsewhere/i);
 assert.equal(f.session.channels[0].gainDb,-3);assert.equal(f.calls.filter(c=>c.method==='POST').length,0);
});
test('a lost POST reply is uncertain, is not retried, and blocks further writes until explicit full refresh',async()=>{
 const f=await ready();const original=f.api.request;let posts=0;
 f.api.request=async(p,o)=> {if(p.endsWith('/parameters/write')){posts++;throw new ApiError('Connection lost',0,null);}return original(p,o);};
 await assert.rejects(f.session.write('out8','gain',-12,-6));assert.equal(f.session.uncertain,true);
 await assert.rejects(f.session.write('out8','gain',-10,-6));assert.equal(posts,1);
 await f.session.pollSnapshot();assert.equal(f.session.uncertain,true);
 await f.session.refreshDevice();assert.equal(f.session.uncertain,false);
});
test('structured UNCERTAIN on an HTTP error preserves readback and blocks more writes',async()=>{
 const f=await ready();const original=f.api.request;
 f.api.request=async(p,o)=> {if(p.endsWith('/parameters/write'))throw new ApiError('Mismatch',409,result(o.body,'UNCERTAIN'));return original(p,o);};
 const r=await f.session.write('out8','gain',-12,-6);assert.equal(r.status,'UNCERTAIN');assert.equal(f.session.uncertain,true);
});
test('definite REJECTED overload preserves old value and is not uncertain',async()=>{
 const f=await ready();const original=f.api.request;
 f.api.request=async(p,o)=> {if(p.endsWith('/parameters/write'))throw new ApiError('Full',503,result(o.body,'REJECTED'));return original(p,o);};
 const r=await f.session.write('out8','gain',-12,-6);assert.equal(r.status,'REJECTED');assert.equal(f.session.channels[0].gainDb,-6);assert.equal(f.session.uncertain,false);
});
test('only one browser write is admitted and an in-flight old-device outcome cannot touch a new device',async()=>{
 const f=await ready();const hold=deferred();const original=f.api.request;
 f.api.request=async(p,o)=>p.endsWith('/parameters/write')?hold.promise:original(p,o);
 const writing=f.session.write('out8','gain',-12,-6);await new Promise(r=>setImmediate(r));
 await assert.rejects(f.session.write('out8','gain',-10,-6),/progress|busy/i);
 f.session.setVerification(false);f.session.selectDevice('second');assert.equal(f.session.verifyChanges,false);
 hold.resolve(result({channelId:'out8',parameter:'gain',value:-12}));await writing;
 assert.equal(f.session.deviceId,'second');assert.equal(f.session.channels.length,0);assert.equal(f.session.lastResult,null);
});
test('delayed old-device GET is ignored and snapshot polls never overlap for one generation',async()=>{
 const f=await ready();const hold=deferred();const original=f.api.request;let reads=0;
 f.api.request=async(p,o)=> {if(p.endsWith('/channels')){reads++;return hold.promise;}return original(p,o);};
 const a=f.session.pollSnapshot();const b=f.session.pollSnapshot();assert.equal(reads,1);
 f.session.selectDevice('second');hold.resolve({connected:true,channels:[channel]});await Promise.all([a,b]);
 assert.equal(f.session.channels.length,0);assert.equal(f.session.deviceId,'second');
});
test('malformed success and a result for another device must never be displayed as success',async()=>{
 for(const bad of [{status:'VERIFIED'},{...result({channelId:'out8',parameter:'gain',value:-12}),dspId:'other'}]) {
  const f=await ready();const original=f.api.request;
  f.api.request=async(p,o)=>p.endsWith('/parameters/write')?bad:original(p,o);
  await assert.rejects(f.session.write('out8','gain',-12,-6));assert.equal(f.session.uncertain,true);
 }
});
test('a successful refresh response without a READY snapshot does not clear uncertainty',async()=>{
 const f=await ready();f.session.uncertain=true;const original=f.api.request;
 f.api.request=async(p,o)=>p.endsWith('/settings-cache')?{phase:'STALE',ready:false}:original(p,o);
 await assert.rejects(f.session.refreshDevice());assert.equal(f.session.uncertain,true);
});
test('unknown fields and stale/unmapped meters render as unknown rather than zero or live',()=>{
 assert.equal(fieldView({...channel,gainDb:null},'gain').value,null);
 const envelope={connected:true,meters:{observedAtEpochMs:1000,rawPayloadConfirmedFromDevice:true,channels:[{id:'out8',levelDb:-12,levelConfirmedFromDevice:true,channelMappingConfirmed:true,levelCalibrationConfirmed:true}]}};
 assert.equal(meterReading(envelope,'out8',1500).value,-12);assert.equal(meterReading(envelope,'out8',6000).fresh,false);
 envelope.meters.channels[0].channelMappingConfirmed=false;assert.equal(meterReading(envelope,'out8',1500).value,null);
});
test('HTTP transport uses same-origin JSON, no-store reads and retains error outcome bodies',async()=>{
 let captured;const api=new HttpApi(async(p,o)=>{captured=o;return {ok:false,status:503,json:async()=>({status:'REJECTED',errorCode:'dsp_overloaded'})};});
 await assert.rejects(api.request('/api/v1/dsps/main/parameters/write',{method:'POST',body:{value:-6}}),e=>e instanceof ApiError&&e.status===503&&e.body.status==='REJECTED');
 assert.equal(captured.credentials,'same-origin');assert.equal(captured.headers['Content-Type'],'application/json');assert.equal(captured.cache,'no-store');
});

test('an old in-flight snapshot cannot overwrite a later verified write',async()=>{
 const f=await ready();const hold=deferred();const original=f.api.request;let first=true;
 f.api.request=async(p,o)=>{if(p.endsWith('/channels')&&first){first=false;return hold.promise;}return original(p,o);};
 const poll=f.session.pollSnapshot();await f.session.write('out8','gain',-12,-6);
 hold.resolve({connected:true,channels:[clone(channel)]});await poll;
 assert.equal(f.session.channels[0].gainDb,-12);assert.equal(f.session.cache.phase,'STALE');
});
test('uncertainty belongs to its device and cannot be cleared by switching away and back',async()=>{
 const f=await ready();const hold=deferred();const original=f.api.request;
 f.api.request=async(p,o)=>p.endsWith('/parameters/write')?hold.promise:original(p,o);
 const writing=f.session.write('out8','gain',-12,-6);await new Promise(r=>setImmediate(r));
 f.session.selectDevice('second');hold.resolve(result({channelId:'out8',parameter:'gain',value:-12},'UNCERTAIN'));await writing;
 assert.equal(f.session.uncertain,false);f.session.selectDevice('main');assert.equal(f.session.uncertain,true);
});
test('an ACK-only response cannot fulfill a request which required verification',async()=>{
 const f=await ready();const original=f.api.request;
 f.api.request=async(p,o)=>p.endsWith('/parameters/write')?result(o.body,'ACKNOWLEDGED'):original(p,o);
 await assert.rejects(f.session.write('out8','gain',-12,-6));assert.equal(f.session.uncertain,true);
});

test('normal controls wait for ACK only, never read back or force a full refresh',async()=>{
 const f=await ready(fixture(),false);assert.equal(f.session.verifyChanges,false);
 const r=await f.session.write('out8','gain',-12,-6);assert.equal(r.status,'ACKNOWLEDGED');
 assert.equal(f.calls.at(-1).body.verify,false);assert.equal(f.session.channels[0].gainConfirmedFromDevice,false);
 assert.equal(f.calls.filter(c=>c.method==='POST').length,1);assert.equal(f.session.cache.phase,'STALE');
});
test('unknown mute state allows an explicit set-mute command, never an implicit unmute',async()=>{
 const f=await ready(fixture(),false);f.session.channels[0].muted=null;
 const original=f.api.request;f.api.request=async(p,o)=>p.endsWith('/channels')?{connected:true,channels:[{...channel,muted:null}]}:original(p,o);
 await assert.rejects(f.session.write('out8','mute',false,null));
 const r=await f.session.write('out8','mute',true,null);assert.equal(r.status,'ACKNOWLEDGED');
 assert.equal(f.session.channels[0].muteConfirmedFromDevice,false);
});

test('interactive writes have one stable request key and bounded browser waits',async()=>{
 const f=await ready(fixture(),false);await f.session.write('out8','gain',-12,-6);
 const post=f.calls.at(-1);assert.equal(post.preferImmediate,true);assert.match(post.requestKey,/^[0-9a-f]{32}$/);assert.equal(post.timeout,2000);
});
test('transport separates backend duration from the total browser roundtrip',async()=>{
 let now=0,captured;
 const api=new HttpApi(async(p,o)=>{captured=o;now=280;return {ok:true,status:200,headers:{get:()=> 'backend;dur=60.000'},json:async()=>({status:'ACKNOWLEDGED'})};},()=>now);
 await api.request('/api/v1/dsps/main/parameters/write',{method:'POST',body:{value:-6},requestKey:'a'.repeat(32),preferImmediate:true});
 assert.equal(captured.headers['Idempotency-Key'],'a'.repeat(32));assert.equal(captured.headers.Prefer,'wait=0');
 assert.equal(api.lastWriteTiming.totalMs,280);assert.equal(api.lastWriteTiming.backendMs,60);assert.equal(api.lastWriteTiming.outsideBackendMs,220);
});

test('restarting visibility polling coalesces the initial device-list request',async()=>{
 const f=fixture(),hold=deferred();const original=f.api.request;let count=0;
 f.api.request=async(p,o)=>{if(p==='/api/v1/dsps'){count++;return hold.promise;}return original(p,o);};
 const pending=Array.from({length:10},()=>f.session.loadDevices());assert.equal(count,1);
 hold.resolve({defaultDsp:'main',dsps:[{id:'main'}]});await Promise.all(pending);assert.equal(f.session.generation,1);
});

test('control feedback timing includes cached preflight and resets on device switch',async()=>{
 const f=await ready(fixture(),false);let now=0;f.api.clock=()=>now;const original=f.api.request;
 f.api.request=async(path,options)=>{
  if(path.endsWith('/channels'))now+=250;
  if(path.endsWith('/parameters/write')){now+=80;f.api.lastWriteTiming={requestKey:options.requestKey,backendMs:60};}
  return original(path,options);
 };
 await f.session.write('out8','gain',-12,-6);
 assert.equal(f.session.lastInteractionTiming.totalMs,330);assert.equal(f.session.lastInteractionTiming.backendMs,60);
 f.session.selectDevice('second');assert.equal(f.session.lastInteractionTiming,null);
});

test('an old explicit refresh cannot clear uncertainty after automatic device-list selection',async()=>{
 const f=await ready(fixture(),false),hold=deferred(),entered=deferred();const original=f.api.request;
 let members=['main','second'],slow=false;
 f.api.request=async(path,options)=>{
  if(path==='/api/v1/dsps')return {defaultDsp:members[0],dsps:members.map(id=>({id}))};
  const device=path.split('/')[4];
  if(path.endsWith('/capabilities'))return {...await original(path,options),dspId:device};
  if(device==='second'&&path.endsWith('/parameters/write'))return {...result(options.body,'UNCERTAIN'),dspId:'second'};
  if(device==='main'&&path.endsWith('/refresh'))slow=true;
  if(device==='main'&&path.endsWith('/channels')&&slow){entered.resolve();return hold.promise;}
  return original(path,options);
 };
 await f.session.loadDevices();f.session.selectDevice('second');await f.session.pollSnapshot();
 await f.session.write('out8','gain',-12,-6);assert.equal(f.session.uncertain,true);
 f.session.selectDevice('main');await f.session.pollSnapshot();const refreshing=f.session.refreshDevice();await entered.promise;
 members=['second'];await f.session.loadDevices();await f.session.pollSnapshot();assert.equal(f.session.uncertain,true);
 hold.resolve({connected:true,channels:[clone(channel)]});await refreshing;
 assert.equal(f.session.deviceId,'second');assert.equal(f.session.uncertain,true);assert.equal(f.session.canControl('out8','gain'),false);
 assert.notEqual(f.session.notice?.message,'Full device snapshot refreshed.');
});

test('gain resolution follows capability bands across a shared boundary',async()=>{
 const {gainValueAt,nextGainValue}=await import('../../main/resources/web/model.mjs');
 const cap={minimum:-60,maximum:12,resolution:[{fromInclusive:-60,toInclusive:-20,step:0.5},{fromInclusive:-20,toInclusive:12,step:0.1}]};
 assert.equal(gainValueAt(-24.9,cap),-25);assert.equal(gainValueAt(-12.34,cap),-12.3);
 assert.equal(nextGainValue(-20.5,1,cap),-20);assert.equal(nextGainValue(-20,1,cap),-19.9);
 assert.equal(nextGainValue(-19.9,-1,cap),-20);assert.equal(nextGainValue(-20,-1,cap),-20.5);
 assert.equal(nextGainValue(-60,-1,cap),-60);assert.equal(nextGainValue(12,1,cap),12);
});
