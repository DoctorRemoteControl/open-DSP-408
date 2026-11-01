import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {chromium} from 'playwright-core';
import {fixture} from './fixture.mjs';
let browser;const profiles=[];
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.DSP408_BROWSER_BIN?{executablePath:process.env.DSP408_BROWSER_BIN}:{}),args:['--no-sandbox']});});
after(async()=>{await browser?.close();if(process.env.DSP408_BROWSER_EVIDENCE_DIR){await mkdir(process.env.DSP408_BROWSER_EVIDENCE_DIR,{recursive:true});await writeFile(process.env.DSP408_BROWSER_EVIDENCE_DIR+'/network-profiles.json',JSON.stringify(profiles,null,2));}});
async function visit(run,{width=1366,height=1000}={}) {
 const api=await fixture();const context=await browser.newContext({viewport:{width,height},reducedMotion:'reduce',hasTouch:width<600,isMobile:width<600});const page=await context.newPage();
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 try {
  await page.goto(api.url+'/app/');await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .gain-slider')?.disabled===false);
  await run(page,api);assert.deepEqual(errors,[],'No uncaught JavaScript errors');
 } finally {await context.close();await api.close();}
}
const card=id=>`[data-channel="${id}"]`;
async function gain(page,id,value,event='change') {
 await page.locator(card(id)+' .gain-slider').evaluate((input,{value,event})=>{input.value=value;input.dispatchEvent(new Event(event,{bubbles:true}));},{value:String(value),event});
}
const waitOutcome=page=>page.waitForFunction(()=>{const n=document.querySelector('#notice');return !n.hidden&&/ACK|verified|acknowledged/i.test(n.textContent);});
async function strictVerification(page){await page.locator('.verification summary').click();await page.getByLabel('Read back after every change').check();}

test('desktop renders real API data, all channels and verified controls without startup POSTs',async()=>visit(async(page,api)=>{
 assert.equal(await page.locator('.channel-card').count(),12);assert.equal(api.posts().length,0);
 assert.match(await page.locator('#firmware').textContent(),/FIR408 V0113P/);
 await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .meter-state').textContent==='Live level');
 assert.match(await page.locator(card('out8')+' .meter-number').textContent(),/−13.0 dB/);
 assert.ok(await page.locator(card('out1')+' .mute-toggle').isEnabled());
 assert.ok(await page.locator(card('out8')+' .mute-toggle').isEnabled());
 const requests=await page.evaluate(()=>performance.getEntriesByType('resource').map(r=>r.name));assert.ok(requests.every(url=>url.startsWith(api.url)));
 if(process.env.DSP408_BROWSER_EVIDENCE_DIR){await mkdir(process.env.DSP408_BROWSER_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:process.env.DSP408_BROWSER_EVIDENCE_DIR+'/desktop.png',fullPage:true});}
}));
test('phone view fits 360px, supports touch-sized controls and filters channel groups',async()=>visit(async(page)=>{
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await page.getByRole('button',{name:'Outputs',exact:true}).click();assert.equal(await page.locator('.channel-group:not([hidden]) .channel-card').count(),8);
 const bounds=await page.locator(card('out8')+' .mute-toggle').boundingBox();assert.ok(bounds.height>=44);
 if(process.env.DSP408_BROWSER_EVIDENCE_DIR){await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .meter-state').textContent==='Live level');await page.screenshot({path:process.env.DSP408_BROWSER_EVIDENCE_DIR+'/phone.png',fullPage:false});await page.locator(card('out8')).scrollIntoViewIfNeeded();await page.screenshot({path:process.env.DSP408_BROWSER_EVIDENCE_DIR+'/phone-channel.png'});}
},{width:360,height:800}));
test('normal gain previews locally and submits one ACK-only change on release',async()=>visit(async(page,api)=>{
 await gain(page,'out8',-12.34,'input');assert.equal(api.posts().length,0);
 assert.match(await page.locator(card('out8')+' .gain-evidence').textContent(),/Preview/);
 await gain(page,'out8',-12.34);await waitOutcome(page);
 assert.equal(api.posts().length,1);assert.equal(api.posts()[0].body.verify,false);
 assert.match(await page.locator(card('out8')+' .gain-value').textContent(),/−12.3/);
 assert.match(await page.locator('#snapshot-status').textContent(),/Changed/);
}));
test('strict verification disables inferred mappings; normal mode is ACK-only and resets per device',async()=>visit(async(page,api)=>{
 await strictVerification(page);assert.ok(await page.locator(card('out1')+' .mute-toggle').isDisabled());
 await page.getByLabel('Read back after every change').uncheck();
 await page.locator(card('out1')+' .mute-toggle').click();await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('ACK only'));
 assert.equal(api.posts().length,1);assert.equal(api.posts()[0].body.verify,false);
 assert.match(await page.locator(card('out1')+' .card-feedback').textContent(),/ACK received/);
 await page.selectOption('#device-select','second');await page.waitForFunction(()=>document.querySelector('#device-address').textContent.includes('192.0.2.11'));
 assert.equal(await page.getByLabel('Read back after every change').isChecked(),false);
}));
test('delay changes require Apply and blank input never becomes zero',async()=>visit(async(page,api)=>{
 const input=page.getByLabel('Out8 delay in milliseconds');await input.fill('');assert.equal(api.posts().length,0);
 await page.getByRole('button',{name:'Apply Out8 delay',exact:true}).click();assert.equal(api.posts().length,0);
 await input.fill('21.25');assert.equal(api.posts().length,0);await page.getByRole('button',{name:'Apply Out8 delay',exact:true}).click();await waitOutcome(page);
 assert.equal(api.posts()[0].body.value,21.25);assert.equal(api.posts().length,1);
}));
test('remote edit during a draft is a visible conflict instead of an overwrite',async()=>visit(async(page,api)=>{
 await gain(page,'out8',-12,'input');api.devices.main.channels[11].gainDb=-3;
 await gain(page,'out8',-12);await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('changed elsewhere'));
 assert.equal(api.posts().length,0);assert.match(await page.locator(card('out8')+' .gain-value').textContent(),/−3.0/);
}));
test('uncertain result blocks further writes until explicit full refresh',async()=>visit(async(page,api)=>{
 api.writeMode='uncertain';await gain(page,'out8',-12);await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('uncertain'));
 assert.ok(await page.locator(card('out8')+' .gain-slider').isDisabled());assert.equal(api.posts().length,1);
 await page.getByRole('button',{name:'Refresh',exact:true}).click();await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .gain-slider').disabled===false);
 assert.equal(api.posts().filter(p=>p.path.endsWith('/parameters/write')).length,1);
 assert.equal(api.posts().filter(p=>p.path.endsWith('/refresh')).length,1);
}));
test('lost POST response is not replayed and recovery is explicit',async()=>visit(async(page,api)=>{
 api.writeMode='lost';await gain(page,'out8',-12);await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('uncertain'));
 assert.ok(await page.locator(card('out8')+' .mute-toggle').isDisabled());assert.equal(api.operations.length,1);
 assert.ok(api.posts().length>=1);assert.ok(api.posts().every(p=>p.body.value===-12));
}));
test('stale levels are historical and unknown firmware disables controls',async()=>visit(async(page,api)=>{
 api.staleMeters=true;await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .meter-state').textContent.includes('stale'));
 assert.ok(await page.locator(card('out8')).evaluate(e=>e.classList.contains('meter-stale')));
 api.unknownFirmware=true;await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('not recognized'));
 assert.ok(await page.locator(card('out8')+' .gain-slider').isDisabled());assert.equal(api.posts().length,0);
}));
test('keyboard gain changes use the same single-write path',async()=>visit(async(page,api)=>{
 await page.getByLabel('Out8 gain').focus();await page.keyboard.press('ArrowRight');await waitOutcome(page);
 assert.equal(api.posts().length,1);assert.equal(api.posts()[0].body.value,-5.9);
}));

test('a focused delay editor preserves the displayed baseline across remote updates',async()=>visit(async(page,api)=>{
 const input=page.getByLabel('Out8 delay in milliseconds');await input.focus();
 api.devices.main.channels[11].delayMs=30;api.devices.main.channels[11].gainDb=-9;
 await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .gain-value').textContent.includes('−9.0'));
 assert.equal(await input.inputValue(),'20');
 await input.fill('25');await page.getByRole('button',{name:'Apply Out8 delay',exact:true}).click();
 await page.waitForFunction(()=>!document.querySelector('#notice').hidden);
 assert.match(await page.locator('#notice').textContent(),/changed elsewhere/);assert.equal(api.posts().length,0);
}));

test('optional verification explicitly requests and displays a fresh readback',async()=>visit(async(page,api)=>{
 await strictVerification(page);await gain(page,'out8',-12);await waitOutcome(page);
 assert.equal(api.posts()[0].body.verify,true);assert.match(await page.locator(card('out8')+' .card-feedback').textContent(),/read back/);
}));
test('unknown mute is an explicit set-mute action without inventing an initial value',async()=>visit(async(page,api)=>{
 api.devices.main.channels[4].muted=null;api.devices.main.channels[4].gainDb=-9;
 await page.waitForFunction(()=>document.querySelector('[data-channel="out1"] .gain-value').textContent.includes('−9.0'));
 const button=page.locator(card('out1')+' .mute-toggle');assert.match(await button.textContent(),/Set mute/);
 await button.click();await waitOutcome(page);assert.equal(api.posts()[0].body.value,true);assert.equal(api.posts()[0].body.verify,false);
}));
test('controlled browser/server jitter stays distinct from backend work',async()=>visit(async(page,api)=>{
 api.linkJitter=[20,120,35,180];api.backendDelayMs=60;
 const started=performance.now();await gain(page,'out8',-12);await waitOutcome(page);
 const feedbackMs=performance.now()-started;const timing=await page.locator('#timing-status').textContent();
 assert.equal(api.operations.length,1);assert.ok(feedbackMs<2000);assert.match(timing,/server/);
 profiles.push({scenario:'HTTP jitter20..180ms plus backend60ms',feedbackMs,timing,httpPosts:api.posts().length,logicalWrites:api.operations.length});
}));
test('a simulated 30-second lost/delayed reply becomes uncertainty within the browser deadline',async()=>visit(async(page,api)=>{
 api.replyDelayMs=30000;api.backendDelayMs=60;
 const started=performance.now();await gain(page,'out8',-12);
 await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('uncertain'));
 const feedbackMs=performance.now()-started;assert.ok(feedbackMs<4500);assert.equal(api.operations.length,1);
 assert.ok(await page.locator(card('out8')+' .gain-slider').isDisabled());
 profiles.push({scenario:'HTTP reply held30000ms after processing',feedbackMs,logicalWrites:api.operations.length,outcome:'UNCERTAIN; controls blocked; no application retry'});
}));

test('gain keyboard steps follow half-decibel resolution below -20dB',async()=>visit(async(page,api)=>{
 api.devices.main.channels[11].gainDb=-25;
 await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .gain-value').textContent.includes('−25.0'));
 await page.getByLabel('Out8 gain').focus();
 for(const expected of ['−24.5','−24.0','−23.5']){
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction(value=>document.querySelector('[data-channel="out8"] .gain-value').textContent.includes(value)&&!document.querySelector('[data-channel="out8"] .gain-slider').disabled,expected,{timeout:5000});
 }
 assert.deepEqual(api.operations.map(o=>o.value),[-24.5,-24,-23.5]);
}));
test('gain keyboard changes cross the -20dB resolution boundary in both directions',async()=>visit(async(page,api)=>{
 api.devices.main.channels[11].gainDb=-20.5;
 await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .gain-value').textContent.includes('−20.5'));
 await page.getByLabel('Out8 gain').focus();
 for(const [key,expected] of [['ArrowRight','−20.0'],['ArrowRight','−19.9'],['ArrowLeft','−20.0'],['ArrowLeft','−20.5']]){
  await page.keyboard.press(key);
  await page.waitForFunction(value=>document.querySelector('[data-channel="out8"] .gain-value').textContent.includes(value)&&!document.querySelector('[data-channel="out8"] .gain-slider').disabled,expected,{timeout:5000});
 }
 assert.deepEqual(api.operations.map(o=>o.value),[-20,-19.9,-20,-20.5]);
}));

test('a 30-second preflight stall times out before any control command is submitted',async()=>visit(async(page,api)=>{
 api.channelDelayMs=30000;const started=performance.now();await gain(page,'out8',-12);
 await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('timed out'));
 const feedbackMs=performance.now()-started;assert.ok(feedbackMs<3500);assert.equal(api.operations.length,0);assert.equal(api.posts().length,0);
 profiles.push({scenario:'HTTP preflight held30000ms',feedbackMs,logicalWrites:0,outcome:'Not submitted; controls paused'});
}));
