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
  async request(path, {method='GET',body,timeout=5000,requestKey,preferImmediate=false}={}) {
    const started=this.clock();let backendMs=null,status=0;
    const abort=new AbortController();
    const timer=setTimeout(()=>abort.abort(),timeout);
    try {
      const response=await this.fetcher(path, {method,credentials:'same-origin',cache:'no-store',
        headers:{'Accept':'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),...(requestKey?{'Idempotency-Key':requestKey}:{}),...(preferImmediate?{Prefer:'wait=0'}:{})},
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
    this._uncertainDevices=new Set();this._mutationRevision=0;
    this.api=api;this.devices=[];this.deviceId=null;this.generation=0;this.listeners=new Set();
    this.pending=null;this.actionBusy=false;this._snapshotFlight=null;this._meterFlight=null;this._devicesFlight=null;
    this._reset();
  }
  _reset() {
    this.capabilities=null;this.channels=[];this.cache={phase:'EMPTY',ready:false};this.meters=null;
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
      if(!Array.isArray(data.dsps))throw new Error('Device list is unavailable.');
      this.devices=data.dsps.filter(device=>typeof device.id==='string'&&device.id.length>0);
      if(!this.devices.some(device=>device.id===this.deviceId)) {
        this.selectDevice(this.devices.find(device=>device.id===data.defaultDsp)?.id || this.devices[0]?.id || null);
      }
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
      &&this.capabilities.firmwareRecognized&&cap?.writable&&!this.uncertain&&!this.pending&&!this.actionBusy
      &&(parameter!=='gain'||gainBands(cap).length>0)&&(!this.verifyChanges||cap.readbackSupported)&&(fieldView(this.channel(id),parameter).value!==null||parameter==='mute'));
  }
  async pollSnapshot() {
    if(!this.deviceId||this.pending)return;
    if(this._snapshotFlight)return this._snapshotFlight;
    const generation=this.generation,id=this.deviceId,revision=this._mutationRevision;
    const run=(async()=>{
      try {
        const [caps,channels,cache]=await Promise.all([
          this.api.request(this._path('/capabilities',id)),this.api.request(this._path('/channels',id)),
          this.api.request(this._path('/settings-cache',id))]);
        if(!this._current(generation)||this.pending||revision!==this._mutationRevision)return;
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
    if(this.pending||this.actionBusy)throw new Error('Another operation is in progress.');
    if(!this.canControl(id,parameter))throw new Error('This control is unavailable. Check connection, capabilities and recovery status.');
    const cap=this.capability(id,parameter);
    if(cap.valueType==='boolean'?typeof value!=='boolean':!numeric(value)||value<cap.minimum||value>cap.maximum)throw new Error('Enter a valid value within the supported range.');
    const device=this.deviceId,generation=this.generation;
    const token={deviceId:device,channelId:id,parameter};this._mutationRevision++;this.pending=token;this.notice=null;this._emit();
    const started=this.api.clock?this.api.clock():performance.now();
    let transmitted=false,requestKey=null;
    try {
      const current=await this.api.request(this._path('/channels',device),{timeout:1500});
      if(!this._current(generation))return null;
      if(!current.connected){this.connected=false;throw new Error('The device disconnected before the write.');}
      const observed=fieldView(current.channels?.find(c=>c.id===id),parameter).value;
      this.channels=current.channels;
      if((observed===null&&!(parameter==='mute'&&expected===null&&value===true))||!Object.is(observed,expected))throw new Error('This value changed elsewhere. Review the current value and try again.');
      const request={channelId:id,parameter,value,verify:this.verifyChanges};
      requestKey=Array.from(crypto.getRandomValues(new Uint8Array(16)),byte=>byte.toString(16).padStart(2,'0')).join('');
      transmitted=true;
      let response;
      try {response=await this.api.request(this._path('/parameters/write',device),{method:'POST',body:request,timeout:2000,requestKey,preferImmediate:true});}
      catch(error) {if(STATUSES.has(error.body?.status))response=error.body;else throw error;}
      this._validateOutcome(response,request,device);
      if(response.status==='UNCERTAIN')this._uncertainDevices.add(device);
      if(!this._current(generation))return null;
      this.lastResult=response;
      if(response.status==='UNCERTAIN')this.uncertain=true;
      if(response.status!=='REJECTED')this.cache={...this.cache,phase:'STALE',ready:false};
      if(response.status==='VERIFIED'||response.status==='ACKNOWLEDGED') {
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
