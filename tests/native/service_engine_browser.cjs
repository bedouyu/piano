'use strict';
// Actual service HTML, orchestration, classic Blob bootstrap, CSP and two browser
// Workers. Tiny module fixtures replace the expensive model layer only.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const {chromium} = require('C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../..'), assets = path.join(root, 'app/src/main/assets');
const origin = 'https://appassets.androidplatform.net';
const defaultBrowser = path.join(root, '.toolchain/browser131/chrome-headless-shell-win64/chrome-headless-shell.exe');
const executablePath = process.env.SHIPU_BROWSER_EXE || (fs.existsSync(defaultBrowser) ? defaultBrowser : 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe');
const xml = '<score-partwise version="4.0"><part id="P1"><measure number="1"/></part></score-partwise>';
const pythonFixture = `self.onmessage = event => {
 const data=event.data;
 if(data.type==='init'){self.runId=data.runId;self.postMessage({type:'ready',offline:true,transport:'native-rpc'});}
 else if(data.type==='recognise'){
   self.postMessage({type:'inferenceCall',runId:self.runId,callId:'c'+(data.id+1),method:'echo',args:[data.id]});
   self.postMessage({type:'result',runId:self.runId,id:data.id,xml:${JSON.stringify(xml)}});
 }
};`;
const inferenceFixture = `self.onmessage=event=>{const data=event.data;if(data.type!=='call')return;
 const packet=new ArrayBuffer(512*1024+17);self.postMessage({type:'inferenceResult',runId:data.runId,callId:data.callId,packet},[packet]);};`;
let browser;
async function scenario(failure) {
  const context = await browser.newContext({viewport: {width: 393, height: 852}}), page = await context.newPage();
  const actions = [], chunks = [], outside = [], errors = [], requests = [];
  const runId = failure ? 'import-failure' : 'two-worker-success';
  await context.addInitScript(() => {
    for (const key of ['Suspending', 'promising', 'Suspender']) delete WebAssembly[key]; delete globalThis.SharedArrayBuffer;
    window.Android = {postMessage: raw => window.__nativeAction(raw), deliverInferenceChunk: (...args) => window.__nativeChunk(...args)};
  });
  await page.exposeFunction('__nativeAction', raw => { actions.push(JSON.parse(raw)); });
  await page.exposeFunction('__nativeChunk', (...args) => { chunks.push(args); });
  await context.route('**/*', async route => {
    const url = new URL(route.request().url()); requests.push(url.pathname);
    if (url.origin !== origin) { outside.push(url.href); await route.abort(); return; }
    let body, contentType;
    if (url.pathname === '/local-omr/worker.js') { body = pythonFixture; contentType = 'text/javascript'; }
    else if (url.pathname === '/local-omr/inference-worker.js') { body = failure ? "throw new Error('SERVICE_MODULE_IMPORT_SENTINEL');" : inferenceFixture; contentType = 'text/javascript'; }
    else {
      const file = path.resolve(assets, '.' + url.pathname); assert.ok(file.startsWith(assets + path.sep));
      body = fs.readFileSync(file); contentType = file.endsWith('.html') ? 'text/html' : 'text/javascript';
    }
    await route.fulfill({contentType, headers: {'Cross-Origin-Resource-Policy': 'same-origin', 'X-Content-Type-Options': 'nosniff'}, body});
  });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/omr-engine.html');
  await page.waitForFunction(() => typeof window.engineCommand === 'function');
  assert.equal(actions[0].action, 'engineLoaded');
  await page.evaluate(runId => window.engineCommand({type: 'start', id: 'sheet', runId}), runId);
  const until = async predicate => { const started = Date.now(); while (!predicate()) { if (Date.now() - started > 20000) throw new Error('Service browser test timed out: ' + JSON.stringify(actions)); await page.waitForTimeout(25); } };
  if (failure) {
    await until(() => actions.some(action => action.action === 'failed'));
    const failed = actions.find(action => action.action === 'failed'); assert.equal(failed.runId, runId); assert.match(failed.message, /SERVICE_MODULE_IMPORT_SENTINEL/);
    const diagnostic = actions.find(action => action.stage === 'bootstrapError'); assert.equal(diagnostic.worker, 'shipu-ort');
  } else {
    await until(() => actions.some(action => action.action === 'ready'));
    for (const index of [0, 1]) {
      await page.evaluate(({runId, index}) => window.engineCommand({type: 'page', runId, page: index, base64: btoa('image-' + index)}), {runId, index});
      await until(() => actions.some(action => action.action === 'pageResult' && action.page === index));
      await until(() => chunks.filter(chunk => chunk[1] === 'c' + (index + 1)).length === 2);
    }
    assert.deepEqual(actions.filter(action => action.action === 'pageResult').map(action => action.page), [0, 1]);
    for (const callId of ['c1', 'c2']) {
      const delivered = chunks.filter(chunk => chunk[1] === callId); assert.deepEqual(delivered.map(chunk => chunk.slice(0, 4)), [[runId, callId, 0, 2], [runId, callId, 1, 2]]);
      assert.equal(Buffer.concat(delivered.map(chunk => Buffer.from(chunk[4], 'base64'))).length, 512 * 1024 + 17);
    }
    await page.evaluate(() => window.engineCommand({type: 'stop'}));
  }
  assert.equal(outside.length, 0); assert.deepEqual(errors, []);
  assert.ok(requests.includes('/local-omr/bootstrap.js')); assert.ok(requests.includes('/local-omr/inference-worker.js'));
  await context.close();
  return {passed: true, scenario: failure ? 'actual-worker-module-import-failure' : 'actual-two-worker-page-and-chunk-contract', pages: failure ? 0 : 2, externalRequests: outside.length};
}
(async () => {
  browser = await chromium.launch({headless: true, executablePath, args: ['--no-first-run', '--disable-extensions']});
  const results = [await scenario(false), await scenario(true)];
  const report = {passed: true, kind: 'production-service-html-js-bootstrap-browser-workers-with-model-fixtures', browserVersion: browser.version(), androidDevice: false, results};
  const output = path.join(root, 'build/verification/background-engine-browser.json'); fs.mkdirSync(path.dirname(output), {recursive: true}); fs.writeFileSync(output, JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); });
