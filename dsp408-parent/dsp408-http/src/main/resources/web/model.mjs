const FIELDS = {gain:'gainDb',mute:'muted',phase:'phaseInverted',delay:'delayMs'};
const CONFIRMED = {gain:'gainConfirmedFromDevice',mute:'muteConfirmedFromDevice',phase:'phaseConfirmedFromDevice',delay:'delayConfirmedFromDevice'};
const DIRTY = {gain:'gainDirty',mute:'muteDirty',phase:'phaseDirty',delay:'delayDirty'};
const STATUSES = new Set(['VERIFIED','ACKNOWLEDGED','REJECTED','UNCERTAIN']);
const numeric = value => typeof value === 'number' && Number.isFinite(value);

export class ApiError extends Error {
  constructor(message, status=0, body=null) { super(message);this.name='ApiError';this.status=status;this.body=body; }
}

export class HttpApi {
  constructor(fetcher=globalThis.fetch.bind(globalThis),clock=()=>performance.now()) { this.fetcher=fetcher;this.clock=clock;this.lastWriteTiming=null; }
  async request(path, {method='GET',body,timeout=5000,requestKey,preferImmediate=false,expectedEtag}={}) {
    const started=this.clock();let backendMs=null,status=0;
    const abort=new AbortController();
    const timer=setTimeout(()=>abort.abort(),timeout);
    try {
      const response=await this.fetcher(path, {method,credentials:'same-origin',cache:'no-store',
        headers:{'Accept':'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),...(requestKey?{'Idempotency-Key':requestKey}:{}),...(preferImmediate?{Prefer:'wait=0'}:{}),...(expectedEtag?{'If-Match':expectedEtag}:{})},
        body:body===undefined?undefined:JSON.stringify(body),signal:abort.signal});
      status=response.status;
      const timing=response.headers?.get('Server-Timing')?.match(/(?:^|,)\s*backend\s*;\s*dur=([0-9.]+)/);
      if(timing&&Number.isFinite(Number(timing[1])))backendMs=Number(timing[1]);
      let data;
      try { data=await response.json(); } catch { throw new ApiError('The server returned an unreadable response.',response.status); }
      if(!response.ok)throw new ApiError(data.message || `Server returned HTTP ${response.status}.`,response.status,data);
      return data;
    } catch(error) {
      if(error instanceof ApiError)throw error;
      throw new ApiError(error.name==='AbortError'?'The server response timed out.':'The server could not be reached.');
    } finally {
      clearTimeout(timer);
      if(method==='POST'&&path.endsWith('/parameters/write')) {
        const totalMs=Math.max(0,this.clock()-started);
        this.lastWriteTiming={requestKey,totalMs,backendMs,outsideBackendMs:backendMs===null?null:Math.max(0,totalMs-backendMs),status};
      }
    }
  }
}

export function fieldView(channel, parameter) {
  const raw=channel?.[FIELDS[parameter]];
  const known=['mute','phase'].includes(parameter)?typeof raw==='boolean':numeric(raw);
  return {value:known?raw:null,confirmed:known&&channel?.[CONFIRMED[parameter]]===true,dirty:channel?.[DIRTY[parameter]]===true};
}

export function meterReading(envelope, id, now=Date.now()) {
  const meters=envelope?.meters;
  const value=meters?.channels?.find(channel=>channel.id===id);
  const known=value?.levelConfirmedFromDevice===true && value?.channelMappingConfirmed===true
    && value?.levelCalibrationConfirmed===true && numeric(value.levelDb);
  const age=numeric(meters?.observedAtEpochMs)?now-meters.observedAtEpochMs:Infinity;
  const fresh=known && envelope.connected===true && meters.rawPayloadConfirmedFromDevice===true && age>=-1000 && age<=2500;
  return {value:known?value.levelDb:null,fresh,clip:fresh&&value.clipActive===true,limiting:fresh&&value.limiterActive===true};
}

function gainBands(capability) {
  return (capability?.resolution||[]).filter(band=>numeric(band.fromInclusive)&&numeric(band.toInclusive)
    &&numeric(band.step)&&band.step>0&&band.fromInclusive<=band.toInclusive).sort((a,b)=>a.fromInclusive-b.fromInclusive);
}
export function gainValueAt(value,capability) {
  if(!numeric(value)||value<capability.minimum||value>capability.maximum)throw new Error('Gain is outside the supported range.');
  const band=gainBands(capability).find(band=>value>=band.fromInclusive&&value<=band.toInclusive);
  if(!band)throw new Error('Gain resolution is unavailable.');
  const normalized=band.fromInclusive+Math.round((value-band.fromInclusive)/band.step)*band.step;
  return Number(Math.max(capability.minimum,Math.min(capability.maximum,normalized)).toFixed(10));
}
export function nextGainValue(value,direction,capability) {
  if(direction!==1&&direction!==-1)throw new Error('Gain direction must be one step.');
  const current=gainValueAt(value,capability);
  const band=gainBands(capability).find(band=>direction>0
    ?current>=band.fromInclusive&&current<band.toInclusive
    :current>band.fromInclusive&&current<=band.toInclusive);
  if(!band)return current;
  return gainValueAt(Math.max(capability.minimum,Math.min(capability.maximum,current+direction*band.step)),capability);
}

/** Owns one browser's selected device. All GETs are cache reads, never physical read commands. */
export class DspSession {
  constructor(api=new HttpApi()) {
    this._uncertainDevices=new Set();this._mutationRevision=0;this._retiredInstances=new Set();this._nextWriteAt=0;this.streamStatus='idle';
    this.api=api;this.devices=[];this.deviceId=null;this.generation=0;this.listeners=new Set();
    this.pending=null;this.actionBusy=false;this._snapshotFlight=null;this._meterFlight=null;this._devicesFlight=null;
    this._reset();
  }
  _reset() {
    this.instanceId=null;this.revision=null;this._ackFloor=null;this.controlStateSupported=null;this.conflict=null;this.capabilities=null;this.channels=[];this.cache={phase:'EMPTY',ready:false};this.meters=null;
    this.connected=false;this.serverReachable=false;this.snapshotHealthy=false;
    this.lastInteractionTiming=null;this.verifyChanges=false;this.uncertain=this._uncertainDevices.has(this.deviceId);this.lastResult=null;this.notice=null;this.error=null;
  }
  subscribe(listener) {this.listeners.add(listener);return ()=>this.listeners.delete(listener);}
  _emit() {for(const listener of this.listeners)listener(this);}
  _path(suffix, id=this.deviceId) {if(!id)throw new Error('Select a device first.');return `/api/v1/dsps/${encodeURIComponent(id)}${suffix}`;}
  _current(generation) {return generation===this.generation;}
  async loadDevices() {
    if(this._devicesFlight)return this._devicesFlight;
    const run=(async()=>{
    try {
      const data=await this.api.request('/api/v1/dsps');
      this.receiveDevices(data);
      this._emit();return this.devices;
    } catch(error) {this.error=error.message;this.serverReachable=false;this._emit();throw error;}
    })();
    this._devicesFlight=run;
    try{return await run;}finally{if(this._devicesFlight===run)this._devicesFlight=null;}
  }
  selectDevice(id) {
    this.deviceId=id;this.generation++;this._reset();this._snapshotFlight=null;this._meterFlight=null;this._emit();
  }
  setVerification(enabled) {this.verifyChanges=enabled===true;this._emit();}
  capability(id, parameter) {return this.capabilities?.channels?.find(c=>c.id===id)?.parameters?.find(p=>p.name===parameter);}
  channel(id) {return this.channels.find(c=>c.id===id);}
  canControl(id, parameter) {
    const cap=this.capability(id,parameter);
    return !!(this.snapshotHealthy&&this.serverReachable&&this.connected&&this.capabilities?.connected
      &&this.capabilities.firmwareRecognized&&cap?.writable&&!this.uncertain&&!this.pending&&!this.actionBusy&&!this.rateLimited&&['idle','live','fallback'].includes(this.streamStatus)
      &&(parameter!=='gain'||gainBands(cap).length>0)&&(!this.verifyChanges||cap.readbackSupported)&&(fieldView(this.channel(id),parameter).value!==null||parameter==='mute'));
  }
  get rateLimited() {return performance.now()<this._nextWriteAt;}
  receiveDevices(data) {
    if(!Array.isArray(data?.dsps))throw new Error('Device list is unavailable.');
    this.serverReachable=true;this.error=null;
    this.devices=data.dsps.filter(device=>typeof device.id==='string'&&device.id.length>0);
    if(!this.devices.some(device=>device.id===this.deviceId)) {
      const next=this.devices.find(device=>device.id===data.defaultDsp)?.id||this.devices[0]?.id||null;
      if(next!==this.deviceId)this.selectDevice(next);
    }
    this._emit();
  }
  receiveState(data) {
    if(data?.dspId!==this.deviceId)return false;
    if(typeof data.instanceId!=='string'||!data.instanceId||!Number.isSafeInteger(data.revision)||data.revision<0
      ||!Array.isArray(data.channels)||!Array.isArray(data.capabilities?.channels)||typeof data.settingsCache?.phase!=='string')throw new Error('The selected device returned incomplete state.');
    if(this._retiredInstances.has(data.instanceId))return false;
    if(this.instanceId&&data.instanceId!==this.instanceId) {
      this._retiredInstances.add(this.instanceId);
      this.selectDevice(this.deviceId);
      this.notice={tone:'notice',message:'The processor instance changed. Previous drafts were cleared.'};
    }
    if(this.instanceId===data.instanceId&&(data.revision<this.revision||(this._ackFloor!==null&&data.revision<=this._ackFloor)))return false;
    this.instanceId=data.instanceId;this.revision=data.revision;this.controlStateSupported=true;this._ackFloor=null;
    this.capabilities=data.capabilities;this.channels=data.channels;this.cache=data.settingsCache;
    this.connected=data.connected===true&&data.capabilities.connected===true;
    this.serverReachable=true;this.snapshotHealthy=true;this.error=null;this._emit();return true;
  }
  receiveMeters(data) {
    if(data?.dspId!==this.deviceId)throw new Error('Meter data belongs to another device.');
    this.meters=data;if(data.connected===false)this.connected=false;this._emit();
  }
  setStreamStatus(status) {
    this.streamStatus=status;
    if(!['idle','live','fallback'].includes(status))this.snapshotHealthy=false;
    this._emit();
  }
  removeInstance(data) {
    if(data?.dspId!==this.deviceId||(this.instanceId&&data.instanceId!==this.instanceId))return;
    if(this.instanceId)this._retiredInstances.add(this.instanceId);
    this.selectDevice(null);this.notice={tone:'notice',message:'The selected processor was removed. Select a current processor.'};this._emit();
  }
  async _readSnapshot(id, timeout=5000) {
    const generation=this.generation;
    if(this.controlStateSupported!==false) {
      try{return {state:await this.api.request(this._path('/control-state',id),{timeout})};}
      catch(error){
        if(![404,405,501].includes(error.status))throw error;
        if(!this._current(generation))return {};
        this.controlStateSupported=false;
      }
    }
    const [caps,channels,cache]=await Promise.all([
      this.api.request(this._path('/capabilities',id),{timeout}),this.api.request(this._path('/channels',id),{timeout}),
      this.api.request(this._path('/settings-cache',id),{timeout})]);
    return {caps,channels,cache};
  }
  async pollSnapshot() {
    if(!this.deviceId||this.pending)return;
    if(this._snapshotFlight)return this._snapshotFlight;
    const generation=this.generation,id=this.deviceId,revision=this._mutationRevision;
    const run=(async()=>{
      try {
        const snapshot=await this._readSnapshot(id);
        if(!this._current(generation)||this.pending||revision!==this._mutationRevision)return;
        if(snapshot.state){this.receiveState(snapshot.state);return;}
        const {caps,channels,cache}=snapshot;
        if(caps.dspId!==id||!Array.isArray(caps.channels)||!Array.isArray(channels.channels)||typeof cache.phase!=='string')throw new Error('The selected device returned incomplete state.');
        this.capabilities=caps;this.channels=channels.channels;this.cache=cache;
        this.connected=channels.connected===true&&caps.connected===true;
        this.serverReachable=true;this.snapshotHealthy=true;this.error=null;
      } catch(error) {
        if(this._current(generation)&&revision===this._mutationRevision&&!this.pending){this.serverReachable=false;this.snapshotHealthy=false;this.error=error.message;}
        throw error;
      } finally {if(this._current(generation))this._emit();}
    })();
    this._snapshotFlight=run;
    try {return await run;} finally {if(this._snapshotFlight===run)this._snapshotFlight=null;}
  }
  async pollMeters() {
    if(!this.deviceId||this._meterFlight)return;
    const generation=this.generation,id=this.deviceId;
    const run=this.api.request(this._path('/meters',id));this._meterFlight=run;
    try {
      const meters=await run;
      if(this._current(generation)) {
        if(meters.dspId!==id)throw new Error('Meter data belongs to another device.');
        this.meters=meters;if(meters.connected===false)this.connected=false;this._emit();
      }
    } catch(error) {
      if(this._current(generation)){this.serverReachable=false;this.error=error.message;this._emit();}
      throw error;
    } finally {if(this._meterFlight===run)this._meterFlight=null;}
  }
  async connection(action) {
    if(!['connect','disconnect','reconnect'].includes(action)||this.pending||this.actionBusy)throw new Error('Another operation is in progress.');
    const id=this.deviceId,generation=this.generation;this.actionBusy=true;this.notice=null;this._emit();
    try {
      await this.api.request(this._path(`/connection/${action}`,id),{method:'POST',body:{},timeout:60000});
      if(this._current(generation))await this.pollSnapshot();
    } catch(error) {if(this._current(generation)){this.error=error.message;this.snapshotHealthy=false;}throw error;}
    finally {this.actionBusy=false;this._emit();}
  }
  async refreshDevice() {
    if(this.pending||this.actionBusy)throw new Error('Another operation is in progress.');
    const id=this.deviceId,generation=this.generation;this.actionBusy=true;this._emit();
    try {
      await this.api.request(this._path('/refresh',id),{method:'POST',body:{},timeout:60000});
      if(!this._current(generation))return;
      // A read started before this explicit refresh cannot certify its result.
      const earlier=this._snapshotFlight;if(earlier)await earlier.catch(()=>{});
      if(!this._current(generation))return;
      await this.pollSnapshot();
      if(!this._current(generation))return;
      if(!this.cache.ready)throw new Error('The full snapshot is not READY yet.');
      this._uncertainDevices.delete(id);this.uncertain=false;this.lastResult=null;this.notice={tone:'success',message:'Full device snapshot refreshed.'};
    } catch(error) {if(this._current(generation))this.error=error.message;throw error;}
    finally {this.actionBusy=false;this._emit();}
  }
  async write(id, parameter, value, expected=fieldView(this.channel(id),parameter).value) {
    if(this.pending||this.actionBusy||this.rateLimited)throw new Error('Another operation is busy. Please wait briefly.');
    if(!this.canControl(id,parameter))throw new Error('This control is unavailable. Check connection, capabilities and recovery status.');
    const cap=this.capability(id,parameter);
    if(cap.valueType==='boolean'?typeof value!=='boolean':!numeric(value)||value<cap.minimum||value>cap.maximum)throw new Error('Enter a valid value within the supported range.');
    const device=this.deviceId,generation=this.generation,instance=this.instanceId;
    const token={deviceId:device,channelId:id,parameter};this._mutationRevision++;this.pending=token;this.notice=null;this.conflict=null;this._emit();
    const started=this.api.clock?this.api.clock():performance.now();
    let transmitted=false,requestKey=null,writeRevision=null;
    try {
      let current,expectedEtag;
      if(this.controlStateSupported!==false) {
        const snapshot=await this._readSnapshot(device,1500);
        if(!this._current(generation))return null;
        if(snapshot.state) {
          current=snapshot.state;
          this.receiveState(current);
          if(!this._current(generation)||this.instanceId!==instance)return null;
          if(current.revision<this.revision)throw new Error('Newer state arrived while checking this edit. Review the current value.');
          writeRevision=current.revision;expectedEtag=`"${current.instanceId}.${current.revision}"`;
        } else current=snapshot.channels;
      } else current=await this.api.request(this._path('/channels',device),{timeout:1500});
      if(!this._current(generation))return null;
      if(!current.connected){this.connected=false;throw new Error('The device disconnected before the write.');}
      const observed=fieldView(current.channels?.find(c=>c.id===id),parameter).value;
      this.channels=current.channels;
      if((observed===null&&!(parameter==='mute'&&expected===null&&value===true))||!Object.is(observed,expected)) {
        this.conflict={channelId:id,parameter,value,current:observed};
        throw new Error('This value changed elsewhere. Review the current value and try again.');
      }
      const freshCapability=this.capability(id,parameter);
      if(!['idle','live','fallback'].includes(this.streamStatus)||!this.capabilities?.firmwareRecognized||!freshCapability?.writable||(!freshCapability.readbackSupported&&this.verifyChanges))throw new Error('This control is unavailable with the current live connection or firmware capabilities.');
      const request={channelId:id,parameter,value,verify:this.verifyChanges};
      requestKey=Array.from(crypto.getRandomValues(new Uint8Array(16)),byte=>byte.toString(16).padStart(2,'0')).join('');
      transmitted=true;this._nextWriteAt=performance.now()+150;setTimeout(()=>this._emit(),160);
      let response;
      try {response=await this.api.request(this._path('/parameters/write',device),{method:'POST',body:request,timeout:2000,requestKey,preferImmediate:true,expectedEtag});}
      catch(error) {
        if(error.status===412&&error.body?.errorCode==='revision_conflict'&&error.body.transmitted===false) {
          transmitted=false;
          if(this._current(generation)) {
            this.receiveState(error.body.current);
            this.conflict={channelId:id,parameter,value,current:fieldView(this.channel(id),parameter).value};
          }
          throw new Error('This value changed elsewhere. Your command was not sent. Review the current value before trying again.');
        }
        if(STATUSES.has(error.body?.status))response=error.body;else throw error;
      }
      this._validateOutcome(response,request,device);
      if(response.status==='UNCERTAIN')this._uncertainDevices.add(device);
      if(!this._current(generation))return null;
      this.lastResult=response;
      if(response.status==='UNCERTAIN')this.uncertain=true;
      if(response.status!=='REJECTED'&&(writeRevision===null||this.revision<=writeRevision))this.cache={...this.cache,phase:'STALE',ready:false};
      if((response.status==='VERIFIED'||response.status==='ACKNOWLEDGED')&&(writeRevision===null||this.revision<=writeRevision)) {
        if(writeRevision!==null)this._ackFloor=writeRevision;
        const channel=this.channel(id);
        channel[FIELDS[parameter]]=response.normalizedValue;
        channel[CONFIRMED[parameter]]=response.status==='VERIFIED';
        channel[DIRTY[parameter]]=response.status!=='VERIFIED';
      }
      this.notice={tone:response.status==='VERIFIED'?'success':response.status==='ACKNOWLEDGED'?'notice':'error',
        message:response.message||{VERIFIED:'Fresh device readback verified.',ACKNOWLEDGED:'ACK only — value has not been read back.',REJECTED:'The write was rejected before transmission.',UNCERTAIN:'Outcome uncertain. Reconnect if needed, then refresh before another write.'}[response.status]};
      return response;
    } catch(error) {
      if(transmitted)this._uncertainDevices.add(device);
      if(this._current(generation)) {
        if(transmitted){this.uncertain=true;this.cache={...this.cache,phase:'STALE',ready:false};}
        this.notice={tone:'error',message:transmitted?'The write outcome is unknown. Do not retry; reconnect if needed, then refresh.':error.message};
        if(!transmitted&&error instanceof ApiError){this.serverReachable=false;this.snapshotHealthy=false;}
      }
      throw error;
    } finally {
      if(this._current(generation)) {
        const totalMs=(this.api.clock?this.api.clock():performance.now())-started;
        const post=transmitted&&this.api.lastWriteTiming?.requestKey===requestKey?this.api.lastWriteTiming:null;
        this.lastInteractionTiming={totalMs,backendMs:post?.backendMs??null,
          outsideBackendMs:post?.backendMs==null?null:Math.max(0,totalMs-post.backendMs)};
      }
      this._mutationRevision++;if(this.pending===token)this.pending=null;this._emit();
    }
  }
  _validateOutcome(response,request,device) {
    if(!response||!STATUSES.has(response.status)||response.dspId!==device||response.channelId!==request.channelId
      ||response.parameter!==request.parameter||typeof response.requestId!=='string'||!response.requestId)throw new Error('Unrecognized write outcome.');
    if(['VERIFIED','ACKNOWLEDGED'].includes(response.status)) {
      if(response.acknowledged!==true||response.transmitted!==true
        ||(typeof request.value==='boolean'?typeof response.normalizedValue!=='boolean':!numeric(response.normalizedValue)))throw new Error('Invalid ACK outcome.');
    }
    if(request.verify&&response.status==='ACKNOWLEDGED')throw new Error('Requested readback was not verified.');
    if(response.status==='VERIFIED'&&(!request.verify||!response.readback||!Object.is(response.readback.value,response.normalizedValue)
      ||!(response.readback.receiveRevision>0)||!(response.readback.observedAtEpochMs>0)))throw new Error('Readback confirmation is incomplete.');
    if(response.status==='REJECTED'&&(response.acknowledged!==false||response.transmitted!==false))throw new Error('Rejection has uncertain transmission.');
  }
}

/** One visible-page stream. Reconnection only reads cached state and never replays commands. */
export class LiveUpdates {
  constructor(session,{EventSource=globalThis.EventSource,visible=()=>!document.hidden}={}) {
    this.session=session;this.EventSource=EventSource;this.visible=visible;this.active=false;this.epoch=0;this.supported=false;this.fallback=false;
    session.subscribe(()=>{if(this.active&&this.binding!==session.generation)this._open();});
  }
  start() {this.stop();if(!this.visible())return;this.active=true;this._open();}
  stop() {
    this.active=false;this.epoch++;this.source?.close();this.source=null;
    clearTimeout(this.retry);clearTimeout(this.snapshotTimer);clearTimeout(this.meterTimer);clearInterval(this.watchdog);
    this.session.setStreamStatus('paused');
  }
  _current(epoch,generation) {return this.active&&this.visible()&&this.epoch===epoch&&this.session.generation===generation;}
  _open() {
    if(!this.active||!this.visible())return;
    this.source?.close();clearTimeout(this.retry);clearTimeout(this.snapshotTimer);clearTimeout(this.meterTimer);clearInterval(this.watchdog);
    const epoch=++this.epoch,generation=this.session.generation;this.binding=generation;
    if(this.fallback||!this.EventSource){this.fallback=true;this._poll(epoch,generation);return;}
    this.session.setStreamStatus('connecting');
    const path='/api/v1/events'+(this.session.deviceId?'?dspId='+encodeURIComponent(this.session.deviceId):'');
    const source=this.source=new this.EventSource(path);let lastEvent=Date.now(),failed=false;
    const current=()=>this._current(epoch,generation)&&this.source===source;
    const fail=async()=>{
      if(!current()||failed)return;failed=true;source.close();this.source=null;clearInterval(this.watchdog);
      this.session.setStreamStatus('reconnecting');
      // A target can disappear while this page is hidden, before a removal event is possible.
      try{await this.session.loadDevices();}catch{/* Reconnect remains bounded while the server is unavailable. */}
      if(!this._current(epoch,generation))return;
      if(!this.supported) {
        const unsupported=await this._unsupported();if(!this._current(epoch,generation))return;
        if(unsupported){this.fallback=true;this._open();return;}
      }
      this.retry=setTimeout(()=>{if(this._current(epoch,generation))this._open();},1000);
    };
    for(const name of ['devices','state','meters','heartbeat','removed'])source.addEventListener(name,event=>{
      if(!current())return;
      try {
        const data=JSON.parse(event.data);lastEvent=Date.now();this.supported=true;
        if(name==='devices')this.session.receiveDevices(data);
        else if(name==='state'){if(this.session.receiveState(data)&&current())this.session.setStreamStatus('live');}
        else if(name==='meters')this.session.receiveMeters(data);
        else if(name==='removed'){this.session.removeInstance(data);if(current())void fail();}
        if(name==='devices'&&!this.session.deviceId&&current())this.session.setStreamStatus('live');
      } catch {void fail();}
    });
    source.onerror=()=>{void fail();};
    this.watchdog=setInterval(()=>{if(current()&&Date.now()-lastEvent>15000)void fail();},1000);
  }
  async _unsupported() {
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),1500);
    try {
      const response=await this.session.api.fetcher('/api/v1/events',{headers:{Accept:'text/event-stream'},credentials:'same-origin',cache:'no-store',signal:controller.signal});
      controller.abort();return [404,405,501].includes(response.status);
    } catch {return false;}finally{clearTimeout(timer);}
  }
  _poll(epoch,generation) {
    this.session.setStreamStatus('fallback');
    const snapshot=async()=>{
      if(!this._current(epoch,generation))return;
      try {if(!this.session.deviceId||!this.lastDeviceRead||Date.now()-this.lastDeviceRead>10000){await this.session.loadDevices();this.lastDeviceRead=Date.now();}
        if(this._current(epoch,generation))await this.session.pollSnapshot();}catch{/* State exposes the read failure. */}
      if(this._current(epoch,generation))this.snapshotTimer=setTimeout(snapshot,1500);
    };
    const meters=async()=>{
      if(!this._current(epoch,generation))return;
      try{await this.session.pollMeters();}catch{/* A bounded cache poll can recover. */}
      if(this._current(epoch,generation))this.meterTimer=setTimeout(meters,400);
    };
    void snapshot();void meters();
  }
}
