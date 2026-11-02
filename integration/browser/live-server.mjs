import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';
import {chromium} from 'playwright-core';

const [origin,output,deviceId='live']=process.argv.slice(2);
const result={ok:false,origin,deviceId,simulatedOnly:true,startedAt:new Date().toISOString(),requests:[],controlPosts:[],outcomes:[],jsErrors:[],unexpectedMutations:[]};
const contexts=[];let browser,canonicalOrigin;
const writePath=`/api/v1/dsps/${encodeURIComponent(deviceId)}/parameters/write`;
const statePath=`/api/v1/dsps/${encodeURIComponent(deviceId)}/control-state`;
const gainSelector='[data-channel="out8"] .gain-slider';
const allowedGains=new Set([-7.2,-7.3,-6]);
const pause=milliseconds=>new Promise(resolve=>setTimeout(resolve,milliseconds));

async function cachedState() {
 const response=await fetch(origin+statePath,{headers:{Accept:'application/json'},cache:'no-store',signal:AbortSignal.timeout(5000)});
 assert.equal(response.status,200,'RAM control-state endpoint is available');
 const state=await response.json();assert.equal(state.dspId,deviceId);
 assert.equal(typeof state.instanceId,'string');assert.ok(Number.isSafeInteger(state.revision));
 assert.equal(state.connected,true,'The owned synthetic device is already connected');
 return state;
}
const gainOf=state=>{const value=state.channels.find(channel=>channel.id==='out8')?.gainDb;assert.equal(typeof value,'number');return value;};
function assertGain(state,value) {assert.ok(Math.abs(gainOf(state)-value)<0.00001,`Expected cached gain ${value}, received ${gainOf(state)}`);}
async function edit(page,value,event) {
 await page.locator(gainSelector).evaluate((input,{value,event})=>{
  input.value=String(value);input.dispatchEvent(new Event(event,{bubbles:true}));
 },{value,event});
}
async function displayedGain(page,value) {
 await page.waitForFunction(({selector,value})=>{
  const slider=document.querySelector(selector);return slider&&!slider.disabled&&Math.abs(Number(slider.value)-value)<0.00001;
 },{selector:gainSelector,value},{timeout:10000});
}
async function ready(page) {
 await page.goto(origin+'/app/',{waitUntil:'domcontentloaded',timeout:15000});
 await page.waitForFunction(id=>[...document.querySelector('#device-select').options].some(option=>option.value===id),deviceId,{timeout:15000});
 if(await page.locator('#device-select').inputValue()!==deviceId)await page.selectOption('#device-select',deviceId);
 await page.waitForFunction(({id,selector})=>document.querySelector('#device-select').value===id
  &&document.querySelector('#live-status')?.textContent==='Live updates'
  &&document.querySelectorAll('.channel-card').length===12&&document.querySelector(selector)?.disabled===false,
 {id:deviceId,selector:gainSelector},{timeout:15000});
 assert.equal(await page.getByLabel('Read back after every change').isChecked(),false);
}
async function client(name,options) {
 const context=await browser.newContext({...options,reducedMotion:'reduce'});contexts.push(context);
 await context.route('**/*',async route=>{
  const request=route.request(),url=new URL(request.url()),headers=request.headers();let body=null;
  if(request.method()==='POST'){try{body=request.postDataJSON();}catch{/* Invalid JSON is rejected below. */}}
  const record={client:name,path:url.pathname,method:request.method(),body};
  result.requests.push(record);
  if(request.method()==='POST') {
   record.headers={ifMatch:headers['if-match']??null,idempotencyKey:headers['idempotency-key']??null,prefer:headers.prefer??null};
   if(url.pathname===writePath)result.controlPosts.push(record);
   if(url.origin!==canonicalOrigin||url.pathname!==writePath||body?.channelId!=='out8'||body.parameter!=='gain'||body.verify!==false||!allowedGains.has(body.value)) {
    result.unexpectedMutations.push(record);await route.abort();return;
   }
  }
  if(url.origin!==canonicalOrigin){await route.abort();return;}
  await route.continue();
 });
 const page=await context.newPage();page.on('pageerror',error=>result.jsErrors.push({client:name,message:error.message}));
 await ready(page);return page;
}
async function changeWithAck(page,client,value) {
 const started=performance.now();
 const responsePromise=page.waitForResponse(response=>{
  if(new URL(response.url()).pathname!==writePath||response.request().method()!=='POST')return false;
  try{const body=response.request().postDataJSON();return body.channelId==='out8'&&body.parameter==='gain'&&body.value===value&&body.verify===false;}catch{return false;}
 },{timeout:10000});
 await edit(page,value,'change');
 const response=await responsePromise,body=await response.json(),headers=response.request().headers();
 assert.equal(response.status(),200);assert.equal(body.status,'ACKNOWLEDGED');
 assert.equal(body.dspId,deviceId);assert.equal(body.channelId,'out8');assert.equal(body.parameter,'gain');
 assert.equal(body.acknowledged,true);assert.equal(body.transmitted,true);assert.ok(Math.abs(body.normalizedValue-value)<0.00001,'ACK gain obeys the supported quantization');
 assert.match(headers['if-match'],/^"[^"\r\n]+\.\d+"$/,'Write carries one exact strong If-Match tag');
 assert.match(headers['idempotency-key'],/^[0-9a-f]{32}$/);assert.equal(headers.prefer,'wait=0');
 const outcome={client,value,httpStatus:response.status(),body,headers:{ifMatch:headers['if-match'],idempotencyKey:headers['idempotency-key'],prefer:headers.prefer},elapsedMs:performance.now()-started};
 result.outcomes.push(outcome);return outcome;
}

try {
 assert.ok(output,'A JSON result path is required');
 assert.match(origin??'',/^http:\/\/127\.0\.0\.1:\d+$/,'Use an explicit owned http://127.0.0.1:port origin');
 const port=Number(origin.slice(origin.lastIndexOf(':')+1));
 assert.ok(port>0&&port<=65535,'An explicit valid loopback port is required');
 canonicalOrigin=new URL(origin).origin;result.canonicalOrigin=canonicalOrigin;
assert.ok(deviceId.length>0&&deviceId.length<=128,'A synthetic device ID is required');
 await mkdir(dirname(output),{recursive:true});
 browser=await chromium.launch({headless:true,executablePath:process.env.DSP408_BROWSER_BIN||'/usr/bin/google-chrome',args:['--no-sandbox']});
 const desktop=await client('desktop',{viewport:{width:1366,height:1000}});
 const phone=await client('phone',{viewport:{width:360,height:800},hasTouch:true,isMobile:true});
 result.initialPosts=result.requests.filter(request=>request.method==='POST').length;
 assert.equal(result.initialPosts,0,'Opening either browser never sends a command');
 result.initialState=await cachedState();
 const firstGain=Math.abs(gainOf(result.initialState)+7.2)<0.00001?-7.3:-7.2,secondGain=-6,draftGain=-8;
 result.sequence={firstGain,secondGain,draftGain};
 await edit(desktop,firstGain,'input');await pause(250);
 assert.equal(result.controlPosts.length,0,'Gain preview stays local');
 assert.match(await desktop.locator('[data-channel="out8"] .gain-evidence').textContent(),/Preview/);
 await changeWithAck(desktop,'desktop',firstGain);
 assert.equal(result.controlPosts.length,1);
 await displayedGain(phone,firstGain);
 assert.equal(result.requests.filter(request=>request.client==='phone'&&request.method==='GET'&&request.path===statePath).length,0,'The second browser received the gain through SSE');
 result.afterFirst=await cachedState();assertGain(result.afterFirst,firstGain);
 await displayedGain(desktop,firstGain);
 await edit(desktop,draftGain,'input');assert.equal(result.controlPosts.length,1);
 await changeWithAck(phone,'phone',secondGain);assert.equal(result.controlPosts.length,2);
 await displayedGain(phone,secondGain);
 result.beforeConflict=await cachedState();assertGain(result.beforeConflict,secondGain);
 assert.equal(await desktop.locator(gainSelector).inputValue(),String(draftGain),'Remote update preserves the existing local preview');
 const preflight=desktop.waitForResponse(response=>new URL(response.url()).pathname===statePath&&response.request().method()==='GET',{timeout:10000});
 await edit(desktop,draftGain,'change');
 assert.equal((await preflight).status(),200);
 await desktop.waitForFunction(()=>document.querySelector('#notice')?.textContent.includes('changed elsewhere'),null,{timeout:10000});
 await pause(250);
 assert.equal(result.controlPosts.length,2,'Conflicting draft never submits another POST');
 assert.match(await desktop.locator('[data-channel="out8"] .card-feedback').textContent(),/conflict/i);
 result.conflictNotice=await desktop.locator('#notice').textContent();
 result.finalState=await cachedState();assertGain(result.finalState,secondGain);
 assert.equal(result.finalState.instanceId,result.initialState.instanceId,'The synthetic target binding stayed stable');
 result.meterGets=result.requests.filter(request=>request.method==='GET'&&request.path.endsWith('/meters')).length;
 assert.equal(result.meterGets,0,'Live pages never poll the meter endpoint');
 assert.equal(result.requests.filter(request=>request.method==='POST').length,2,'Only the two synthetic gain commands were sent');
 assert.notEqual(result.outcomes[0].headers.idempotencyKey,result.outcomes[1].headers.idempotencyKey);
 assert.deepEqual(result.unexpectedMutations,[]);assert.deepEqual(result.jsErrors,[]);
 result.phoneFits=await phone.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);assert.equal(result.phoneFits,true);
 result.phoneTargets={};
 for(const selector of ['.gain-slider','.mute-toggle','.phase-toggle','.delay-input','.delay-apply']) {
  const bounds=await phone.locator('[data-channel="out8"] '+selector).boundingBox();
  assert.ok(bounds&&bounds.height>=44,selector+' has a phone-sized touch target');result.phoneTargets[selector]=bounds;
 }
 result.screenshots={desktop:output+'.desktop.png',phone:output+'.phone.png'};
 await desktop.screenshot({path:result.screenshots.desktop,fullPage:true});
 await phone.locator('[data-channel="out8"]').scrollIntoViewIfNeeded();
 await phone.screenshot({path:result.screenshots.phone,fullPage:false});
 result.totalActualControlPosts=result.controlPosts.length;result.ok=true;
} catch(error) {result.error=error.stack??String(error);process.exitCode=1;}
finally {
 await Promise.allSettled(contexts.map(context=>context.close()));
 if(browser)await browser.close().catch(error=>{result.cleanupError=error.message;result.ok=false;process.exitCode=1;});
 result.totalActualControlPosts=result.controlPosts.length;
 if(result.jsErrors.length){result.ok=false;result.error??='Uncaught browser JavaScript errors';process.exitCode=1;}
 result.finishedAt=new Date().toISOString();
 if(output){await mkdir(dirname(output),{recursive:true});await writeFile(output,JSON.stringify(result,null,2));}
 else process.stderr.write((result.error??'A result path is required')+'\n');
}
