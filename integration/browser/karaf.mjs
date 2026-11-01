import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright-core';
const [origin,output]=process.argv.slice(2);
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)||!output)throw new Error('An explicit owned loopback runtime and result path are required.');
const result={ok:false,origin,simulatedOnly:true};let browser;
try {
 browser=await chromium.launch({headless:true,...(process.env.DSP408_BROWSER_BIN?{executablePath:process.env.DSP408_BROWSER_BIN}:{}),args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:390,height:844},hasTouch:true,reducedMotion:'reduce'});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(origin+'/app/',{timeout:15000});
 await page.waitForFunction(()=>document.querySelector('#device-select')?.options.length>=4);
 await page.selectOption('#device-select','writable');
 await page.waitForFunction(()=>document.querySelector('#connection-button').disabled===false);
 assert.equal(await page.locator('#connection-button').textContent(),'Connect');
 await page.locator('#connection-button').click();
 await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .gain-slider')?.disabled===false,null,{timeout:15000});
 const started=performance.now();
 await page.locator('[data-channel="out8"] .gain-slider').evaluate(input=>{
  input.value='-12.3';input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));
 });
 await page.waitForFunction(()=>document.querySelector('[data-channel="out8"] .card-feedback').textContent.includes('ACK received'));
 result.feedbackMs=performance.now()-started;result.timing=await page.locator('#timing-status').textContent();
 assert.match(result.timing,/server \d+ ms/);assert.ok(result.feedbackMs<2000);
 assert.equal(await page.getByLabel('Read back after every change').isChecked(),false);
 result.channels=await page.locator('.channel-card').count();assert.equal(result.channels,12);
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await page.screenshot({path:output+'.png',fullPage:false});
 await page.locator('#connection-button').click();await page.waitForFunction(()=>document.querySelector('#device-status').textContent==='Disconnected');
 assert.deepEqual(errors,[]);result.ok=true;
} catch(error){result.error=error.stack;process.exitCode=1;}
finally{await browser?.close();await writeFile(output,JSON.stringify(result,null,2));}
