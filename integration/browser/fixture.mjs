import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../../dsp408-parent/dsp408-http/src/main/resources/web/',import.meta.url);
const mime={'.html':'text/html','.mjs':'text/javascript','.css':'text/css','.svg':'image/svg+xml'};
const fields={gain:'gainDb',mute:'muted',phase:'phaseInverted',delay:'delayMs'};
const confirmed={gain:'gainConfirmedFromDevice',mute:'muteConfirmedFromDevice',phase:'phaseConfirmedFromDevice',delay:'delayConfirmedFromDevice'};
const names=['InA','InB','InC','InD','Out1','Out2','Out3','Out4','Out5','Out6','Out7','Out8'];

/** Controlled HTTP API fixture, never a DSP emulator or hardware reference. */
export async function fixture() {
 const delivered=new Map();
 const state={operations:[],linkJitter:[],linkIndex:0,replyDelayMs:0,channelDelayMs:0,backendDelayMs:0,requests:[],writeMode:'verified',staleMeters:false,unknownFirmware:false,failReads:false,devices:{}};
 for(const id of ['main','second'])state.devices[id]={connected:true,cache:'READY',channels:names.map((name,index)=>({id:index<4?'in'+name.at(-1).toLowerCase():'out'+(index-3),displayName:name,index,
  gainDb:id==='main'?-6:-3,muted:false,phaseInverted:false,delayMs:20,gainConfirmedFromDevice:true,
  muteConfirmedFromDevice:index===3||index===11,phaseConfirmedFromDevice:true,delayConfirmedFromDevice:true}))};
 const server=http.createServer(async(req,res)=>{
  try {
   const path=new URL(req.url,'http://fixture').pathname;let body;
   if(req.method==='POST'){let bytes='';for await(const data of req)bytes+=data;body=JSON.parse(bytes||'{}');}
   state.requests.push({path,method:req.method,body});
   if(path.startsWith('/api/')&&state.linkJitter.length) {
    if(!await wait(state.linkJitter[state.linkIndex++%state.linkJitter.length],res))return;
   }
   const backendStarted=performance.now();
   const send=async(data,status=200)=>{
    const headers={'Content-Type':'application/json','Cache-Control':'no-store'};
    if(path.endsWith('/parameters/write')) {
     headers['Server-Timing']=`backend;dur=${(performance.now()-backendStarted).toFixed(3)}`;
     if(state.writeMode==='lost'){req.socket.destroy();return;}
     if(!await wait(state.replyDelayMs,res))return;
    }
    if(!res.destroyed){res.writeHead(status,headers);res.end(JSON.stringify(data));}
   };
   if(path==='/api/v1/dsps')return send({defaultDsp:'main',dsps:Object.entries(state.devices).map(([id,d])=>({id,ip:'192.0.2.'+(id==='main'?'10':'11'),port:9761,defaultDevice:id==='main',connected:d.connected}))});
   if(path.startsWith('/api/v1/dsps/')) {
    if(state.failReads&&req.method==='GET')return send({message:'Server unavailable'},503);
    const [,id,suffix]=path.match(/^\/api\/v1\/dsps\/([^/]+)(\/.*)$/)||[];
    const d=state.devices[id];if(!d)return send({message:'Unknown device'},404);
    if(suffix==='/capabilities')return send({dspId:id,connected:d.connected,firmwareRecognized:!state.unknownFirmware,deviceVersion:state.unknownFirmware?'FIR408 unknown':'FIR408 V0113P',channels:d.channels.map(c=>({...c,parameters:Object.keys(fields).map(name=>({name,valueType:name==='gain'||name==='delay'?'number':'boolean',minimum:name==='gain'?-60:0,maximum:name==='gain'?12:680,resolution:name==='gain'?[{fromInclusive:-60,toInclusive:-20,step:0.5},{fromInclusive:-20,toInclusive:12,step:0.1}]:[],writable:d.connected&&!state.unknownFirmware,readbackSupported:name!=='mute'||c.index===3||c.index===11}))}))});
    if(suffix==='/channels'){if(!await wait(state.channelDelayMs,res))return;return send({connected:d.connected,channels:d.channels});}
    if(suffix==='/settings-cache')return send({phase:d.cache,ready:d.cache==='READY',cachedBlockCount:32,expectedBlockCount:32});
    if(suffix==='/meters')return send({dspId:id,connected:d.connected,meters:{observedAtEpochMs:Date.now()-(state.staleMeters?10000:0),rawPayloadConfirmedFromDevice:true,channels:d.channels.map(c=>({id:c.id,levelDb:-24+c.index,levelConfirmedFromDevice:true,channelMappingConfirmed:true,levelCalibrationConfirmed:true}))}});
    if(suffix==='/refresh'){d.cache='READY';return send({ok:true});}
    if(suffix==='/connection/connect'||suffix==='/connection/reconnect'){d.connected=true;d.cache='READY';return send({ok:true});}
    if(suffix==='/connection/disconnect'){d.connected=false;return send({ok:true});}
    if(suffix==='/parameters/write'&&req.method==='POST') {
     const key=req.headers['idempotency-key'];
     if(key&&delivered.has(key)){const cached=delivered.get(key);return send(cached.body,cached.status);}
     state.operations.push({deviceId:id,...body,key});
     if(!await wait(state.backendDelayMs,res))return;
     const channel=d.channels.find(c=>c.id===body.channelId);if(!channel)return send({message:'Unknown channel'},422);
     if(state.writeGate)await state.writeGate;
     const normalized=typeof body.value==='boolean'?body.value:body.parameter==='delay'?Math.round(body.value*96)/96:body.value<=-20?Math.round(body.value*2)/2:Math.round(body.value*10)/10;
     const uncertain=state.writeMode==='uncertain';const status=uncertain?'UNCERTAIN':body.verify?'VERIFIED':'ACKNOWLEDGED';
     if(!uncertain){channel[fields[body.parameter]]=normalized;channel[confirmed[body.parameter]]=body.verify;}
     d.cache='STALE';
     const outcome={requestId:'browser-fixture',dspId:id,channelId:body.channelId,parameter:body.parameter,requestedValue:body.value,normalizedValue:normalized,status,acknowledged:true,transmitted:true,
       readback:body.verify?{value:uncertain?normalized+1:normalized,receiveRevision:2,observedAtEpochMs:Date.now(),blockIndex:'1C'}:null,errorCode:uncertain?'readback_mismatch':null};
     if(key)delivered.set(key,{body:outcome,status:uncertain?409:200});
     return send(outcome,uncertain?409:200);
    }
    return send({message:'Unknown route'},404);
   }
   if(path==='/app'){res.writeHead(302,{Location:'/app/'});res.end();return;}
   const asset=path==='/app/'?'index.html':path.startsWith('/app/')?path.slice(5):'';
   if(!['index.html','styles.css','app.mjs','model.mjs','icon.svg'].includes(asset)){res.writeHead(404);res.end();return;}
   const bytes=await readFile(new URL(asset,root));
   res.writeHead(200,{'Content-Type':mime[asset.slice(asset.lastIndexOf('.'))]+'; charset=utf-8','Cache-Control':'no-store',
    'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"});res.end(bytes);
  }catch(error){if(!res.headersSent)res.writeHead(500);res.end(error.message);}
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 state.url=`http://127.0.0.1:${server.address().port}`;
 state.close=()=>new Promise(resolve=>{server.closeAllConnections();server.close(resolve);});
 state.posts=()=>state.requests.filter(r=>r.method==='POST');
 return state;
}

function wait(milliseconds,response) {
 if(!milliseconds)return Promise.resolve(true);
 return new Promise(resolve=>{
  let done=false;const finish=value=>{if(done)return;done=true;clearTimeout(timer);response.off('close',closed);resolve(value);};
  const closed=()=>finish(false);const timer=setTimeout(()=>finish(true),milliseconds);timer.unref();response.once('close',closed);
 });
}
