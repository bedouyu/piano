'use strict';
// Production RecognitionService document/orchestrator + real bundled Python,
// homr and ONNX models. Only Android image I/O and native RPC streams are simulated.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const {chromium} = require('C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../..'), assets = path.join(root, 'app/src/main/assets'), output = path.join(root, 'build/verification');
const origin = 'https://appassets.androidplatform.net';
const executablePath = process.env.SHIPU_BROWSER_EXE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const imagePaths = process.env.SHIPU_INPUT_IMAGES ? JSON.parse(process.env.SHIPU_INPUT_IMAGES) : ['build/verification/river/page-1-2200.png', 'build/verification/river/page-2-2200.png'];
assert.ok(Array.isArray(imagePaths) && imagePaths.length > 0 && imagePaths.length <= 40);
const images = imagePaths.map(file => fs.readFileSync(path.resolve(root, file)));
const prefix = process.env.SHIPU_TEST_PREFIX || 'service-river';
const runId = crypto.randomUUID(), id = crypto.randomUUID();
const preamble = "for(const key of ['Suspending','promising','Suspender'])delete WebAssembly[key];delete globalThis.SharedArrayBuffer;\n";
const headers = {'Cross-Origin-Resource-Policy': 'same-origin', 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store'};
const mime = {'.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.zip': 'application/zip', '.whl': 'application/zip', '.gz': 'application/gzip'};
const pending = new Map(), events = [], outside = [], consoleErrors = [], consoleTail = [], requested = new Set(), xmlPages = [], pageResults = [];
const stats = {calls: 0, completed: 0, maximumPacketBytes: 0, maximumChunks: 0, maximumPendingCalls: 0};
let browser, page, timeout, succeed, failed, started, capabilities;
let nextPage = 0;
const done = new Promise((resolve, reject) => { succeed = resolve; failed = reject; });
// Attach a rejection handler immediately; bootstrap errors can arrive while page.goto waits.
done.catch(() => {});
function slot(callId) {
  if (!pending.has(callId)) {
    let resolve; const promise = new Promise(yes => { resolve = yes; });
    pending.set(callId, {resolve, promise, next: 0, total: 0, bytes: 0, chunks: []});
    stats.maximumPendingCalls = Math.max(stats.maximumPendingCalls, pending.size);
    assert.ok(pending.size <= 8, 'native RPC supports at most eight concurrent calls');
  }
  return pending.get(callId);
}
async function sendPage(index) {
  assert.equal(index, nextPage); assert.ok(index < images.length);
  console.log('Recognizing physical page ' + (index + 1) + '/' + images.length);
  await page.evaluate(message => window.engineCommand(message), {type: 'page', runId, page: index, mime: 'image/png', base64: images[index].toString('base64')});
}
(async () => {
  fs.mkdirSync(output, {recursive: true});
  browser = await chromium.launch({headless: true, executablePath, args: ['--no-first-run', '--disable-extensions']});
  const context = await browser.newContext({viewport: {width: 393, height: 852}}); page = await context.newPage();
  timeout = setTimeout(() => failed(Error('Two-page production service recognition timed out')), Number(process.env.SHIPU_TEST_TIMEOUT_MS) || 1200000);
  await context.addInitScript(() => {
    for (const key of ['Suspending', 'promising', 'Suspender']) delete WebAssembly[key]; delete globalThis.SharedArrayBuffer;
    window.Android = {postMessage: raw => window.__nativeAction(raw), deliverInferenceChunk: (...args) => window.__nativeChunk(...args)};
  });
  await page.exposeFunction('__nativeAction', async raw => {
    try {
      const data = JSON.parse(raw); events.push({seconds: started ? (Date.now() - started) / 1000 : 0, ...data, xml: data.xml ? '[MusicXML ' + Buffer.byteLength(data.xml) + ' bytes]' : undefined});
      if (data.action === 'engineLoaded') return;
      assert.equal(data.runId, runId); assert.equal(data.id, id);
      switch (data.action) {
        case 'ready': await sendPage(0); break;
        case 'failed': failed(Error(data.message || 'Production service engine failed')); break;
        case 'pageResult': {
          assert.equal(data.page, nextPage, 'service must emit pages in physical PDF order');
          assert.match(data.xml, /<score-partwise(?:\s|>)/); assert.ok(Buffer.byteLength(data.xml) <= 4 * 1024 * 1024);
          xmlPages.push(data.xml); pageResults.push({page: data.page, bytes: Buffer.byteLength(data.xml), seconds: (Date.now() - started) / 1000, warnings: data.warnings || []});
          fs.writeFileSync(path.join(output, prefix + '-page-' + (data.page + 1) + '.musicxml'), data.xml);
          nextPage++; console.log('Saved physical page ' + nextPage + '/' + images.length);
          if (nextPage === images.length) succeed(); else await sendPage(nextPage);
          break;
        }
      }
    } catch (error) { failed(error); }
  });
  await page.exposeFunction('__nativeChunk', async (session, callId, index, total, encoded) => {
    try {
      assert.equal(session, runId); assert.match(callId, /^c[1-9][0-9]{0,7}$/); assert.ok(total > 0 && total <= 128);
      const item = slot(callId); assert.equal(index, item.next); if (item.total) assert.equal(total, item.total); item.total = total;
      const bytes = Buffer.from(encoded, 'base64'); assert.ok(bytes.length > 0 && bytes.length <= 512 * 1024);
      item.bytes += bytes.length; assert.ok(item.bytes <= 64 * 1024 * 1024); item.next++; item.chunks.push(bytes);
      if (item.next === total) {
        stats.maximumPacketBytes = Math.max(stats.maximumPacketBytes, item.bytes); stats.maximumChunks = Math.max(stats.maximumChunks, total);
        item.resolve(Buffer.concat(item.chunks)); item.chunks = [];
      }
    } catch (error) { failed(error); throw error; }
  });
  await context.route('**/*', async route => {
    try {
      const request = route.request(), url = new URL(request.url());
      if (url.origin !== origin) { outside.push(url.href); await route.abort(); return; }
      requested.add(url.pathname);
      if (url.pathname.startsWith('/__omr_rpc__/')) {
        const parts = url.pathname.split('/'); assert.equal(parts.length, 4); assert.equal(parts[2], runId); assert.match(parts[3], /^c[1-9][0-9]{0,7}$/);
        stats.calls++; const body = await slot(parts[3]).promise;
        await route.fulfill({status: 200, headers, contentType: 'application/octet-stream', body}); stats.completed++; pending.delete(parts[3]); return;
      }
      const file = path.resolve(assets, '.' + decodeURIComponent(url.pathname)); assert.ok(file.startsWith(assets + path.sep));
      if (!fs.existsSync(file)) throw new Error('Production asset missing: ' + url.pathname);
      let body = fs.readFileSync(file);
      if (['/local-omr/worker.js', '/local-omr/inference-worker.js', '/local-omr/bootstrap.js'].includes(url.pathname)) body = Buffer.from(preamble + body.toString('utf8'));
      await route.fulfill({headers, contentType: mime[path.extname(file)] || 'application/octet-stream', body});
    } catch (error) { failed(error); try { await route.abort(); } catch (_) {} }
  });
  page.on('pageerror', error => { consoleErrors.push(error.message); failed(error); });
  page.on('console', message => {
    if (consoleTail.length >= 150) consoleTail.shift(); consoleTail.push(message.text().slice(0, 2000));
    if (message.type() === 'error') consoleErrors.push(message.text().slice(0, 2000));
  });
  await page.goto(origin + '/omr-engine.html');
  capabilities = await page.evaluate(() => ({jspi: typeof WebAssembly.Suspending === 'function', sab: typeof SharedArrayBuffer === 'function', isolated: crossOriginIsolated, userAgent: navigator.userAgent}));
  assert.equal(capabilities.jspi, false); assert.equal(capabilities.sab, false); assert.equal(capabilities.isolated, false);
  started = Date.now(); await page.evaluate(message => window.engineCommand(message), {type: 'start', id, runId});
  await done;
  assert.equal(xmlPages.length, images.length); assert.equal(outside.length, 0); assert.equal(stats.calls, stats.completed);
  assert.ok(stats.maximumPacketBytes > 16 * 1024 * 1024, 'real inference must exercise the large binary stream');
  for (const worker of ['shipu-ort', 'shipu-python']) assert.ok(events.some(event => event.stage === 'bootstrapReady' && event.worker === worker));
  assert.ok(events.some(event => event.stage === 'probePassed'));
  assert.equal(pending.size, 0); await page.evaluate(() => window.engineCommand({type: 'stop'}));
  const report = {passed: true, kind: 'production-service-html-orchestrator-bootstrap-python-homr-onnx-with-android-io-boundary-simulation', androidDevice: false, browserVersion: browser.version(), imagePaths, capabilities, seconds: (Date.now() - started) / 1000, pages: pageResults, physicalPageOrder: pageResults.map(result => result.page), rpc: stats, externalRequests: outside, allBundledModelsUsed: Array.from(requested).filter(url => url.endsWith('.onnx')), accuracyManuallyCertified: false};
  fs.writeFileSync(path.join(output, prefix + '-runtime.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  clearTimeout(timeout); fs.mkdirSync(output, {recursive: true});
  fs.writeFileSync(path.join(output, prefix + '-runtime.log'), JSON.stringify({events, consoleErrors, consoleTail, completedPages: pageResults, rpc: stats}, null, 2));
  for (const item of pending.values()) item.resolve(Buffer.alloc(0)); pending.clear();
  if (browser) await browser.close();
});
