'use strict';
// Executes production service orchestration with deterministic worker/native boundaries.
// The real Python/ONNX model regression is separate; this isolates lifecycle races.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../app/src/main/assets/omr-engine.js'), 'utf8');
const origin = 'https://appassets.androidplatform.net/omr-engine.html';
const XML = '<score-partwise version="4.0"><part id="P1"><measure number="1"/></part></score-partwise>';
const tick = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; }
function harness(options = {}) {
  const actions = [], chunks = [], workers = [], revoked = [], timers = new Map(); let timerId = 0, blobId = 0;
  class FakeURL extends URL {
    static createObjectURL() { return 'blob:service-fixture-' + (++blobId); }
    static revokeObjectURL(url) { revoked.push(url); }
  }
  class FakeWorker {
    constructor(url, config) { this.url = url; this.config = config; this.messages = []; this.terminated = false; workers.push(this); }
    postMessage(data, transfer) { this.messages.push({data, transfer}); }
    terminate() { this.terminated = true; }
    emit(data) { if (this.onmessage) this.onmessage({data}); }
  }
  const context = {
    Android: {
      postMessage: text => actions.push(JSON.parse(text)),
      deliverInferenceChunk: (...args) => { chunks.push(args); return options.deliver ? options.deliver(...args) : undefined; }
    },
    Worker: FakeWorker, URL: FakeURL, Blob, ArrayBuffer, Uint8Array,
    location: {href: origin}, console,
    fetch: options.fetch || (() => Promise.resolve({ok: true, text: async () => '/* real bootstrap body is a worker boundary in this test */'})),
    atob: text => Buffer.from(text, 'base64').toString('binary'),
    btoa: text => Buffer.from(text, 'binary').toString('base64'),
    setTimeout: (callback, ms) => { timers.set(++timerId, {callback, ms}); return timerId; },
    clearTimeout: id => timers.delete(id)
  };
  context.window = context; vm.createContext(context); vm.runInContext(source, context);
  const command = data => context.engineCommand(data);
  const current = name => workers.filter(worker => worker.config.name === name && !worker.terminated).at(-1);
  const start = async runId => { command({type: 'start', id: 'sheet', runId}); await tick(); };
  const ready = runId => current('shipu-python').emit({type: 'ready', runId, offline: true, transport: 'native-rpc'});
  const page = (runId, index) => command({type: 'page', runId, page: index, mime: 'image/png', base64: Buffer.from('page-' + index).toString('base64')});
  const call = (runId, callId = 'c1') => current('shipu-python').emit({type: 'inferenceCall', runId, callId, method: 'run', args: []});
  const result = (runId, packet, callId = 'c1') => current('shipu-ort').emit({type: 'inferenceResult', runId, callId, packet});
  return {actions, chunks, workers, revoked, timers, command, current, start, ready, page, call, result};
}

const tests = [];
function test(name, body) { tests.push({name, body}); }
test('separate classic workers, physical page order, and native RPC boundary', async () => {
  const h = harness(); assert.equal(h.actions[0].action, 'engineLoaded'); await h.start('run-a');
  assert.equal(h.workers.length, 2);
  assert.deepEqual(h.workers.map(worker => worker.config.name), ['shipu-ort', 'shipu-python']);
  assert.ok(h.workers.every(worker => worker.config.type === 'classic'));
  const python = h.current('shipu-python');
  assert.equal(python.messages[1].data.transport, 'native-rpc');
  assert.equal(python.messages[1].data.rpcBase, 'https://appassets.androidplatform.net/__omr_rpc__/run-a/');
  h.ready('run-a'); h.page('run-a', 0);
  assert.equal(python.messages.at(-1).data.id, 0);
  assert.equal(Buffer.from(python.messages.at(-1).data.image).toString(), 'page-0');
  python.emit({type: 'result', runId: 'run-a', id: 0, xml: XML});
  h.page('run-a', 1); python.emit({type: 'result', runId: 'run-a', id: 1, xml: XML});
  assert.deepEqual(h.actions.filter(action => action.action === 'pageResult').map(action => action.page), [0, 1]);
  h.command({type: 'stop'});
  assert.ok(h.workers.every(worker => worker.terminated)); assert.equal(h.revoked.length, 2); assert.equal(h.timers.size, 0);
});
test('large binary packets use bounded ordered chunks without background timers', async () => {
  const h = harness(); await h.start('run-a'); h.ready('run-a'); h.call('run-a');
  const packet = new Uint8Array(2 * 512 * 1024 + 17); for (let i = 0; i < packet.length; i++) packet[i] = i % 251;
  h.result('run-a', packet.buffer); await tick();
  assert.equal(h.chunks.length, 3);
  assert.deepEqual(h.chunks.map(chunk => chunk.slice(0, 4)), [['run-a', 'c1', 0, 3], ['run-a', 'c1', 1, 3], ['run-a', 'c1', 2, 3]]);
  const combined = Buffer.concat(h.chunks.map(chunk => Buffer.from(chunk[4], 'base64')));
  assert.ok(combined.equals(Buffer.from(packet))); assert.equal(h.timers.size, 0);
});
test('stale worker results cannot complete a newer run', async () => {
  const h = harness(); await h.start('run-a'); const old = h.current('shipu-python');
  await h.start('run-b'); h.ready('run-b'); h.page('run-b', 0);
  old.emit({type: 'result', runId: 'run-a', id: 0, xml: XML});
  assert.equal(h.actions.filter(action => action.action === 'pageResult').length, 0);
  h.current('shipu-python').emit({type: 'result', runId: 'run-b', id: 0, xml: XML});
  assert.equal(h.actions.filter(action => action.action === 'pageResult').length, 1);
});
test('invalid worker channel and duplicate RPC requests fail safely', async () => {
  const h = harness(); await h.start('run-a');
  h.current('shipu-python').emit({type: 'ready', runId: 'run-a', offline: false, transport: 'native-rpc'});
  assert.equal(h.actions.at(-1).action, 'failed'); assert.ok(h.workers.every(worker => worker.terminated));
  await h.start('run-b'); h.call('run-b'); h.call('run-b');
  assert.match(h.actions.at(-1).message, /通道请求无效/); assert.ok(h.workers.every(worker => worker.terminated));
});
test('canceling while bootstrap fetch waits never creates new workers', async () => {
  const loading = deferred(), h = harness({fetch: () => loading.promise});
  h.command({type: 'start', id: 'sheet', runId: 'run-a'}); h.command({type: 'stop'});
  loading.resolve({ok: true, text: async () => '/* bootstrap */'}); await tick();
  assert.equal(h.workers.length, 0, 'canceled startup must not create leaked workers');
  assert.equal(h.actions.filter(action => action.action === 'failed').length, 0, 'canceled startup must not report a failure');
});
test('older fetch completing after restart cannot attach workers to new run', async () => {
  const loading = deferred(); let count = 0;
  const h = harness({fetch: () => ++count === 1 ? loading.promise : Promise.resolve({ok: true, text: async () => '/* bootstrap */'})});
  h.command({type: 'start', id: 'sheet', runId: 'run-a'}); await h.start('run-b');
  loading.resolve({ok: true, text: async () => '/* old bootstrap */'}); await tick();
  assert.equal(h.workers.filter(worker => !worker.terminated).length, 2, 'one run must own exactly two active workers');
});
test('canceling an in-flight native chunk stops the remaining old chunks', async () => {
  const held = deferred(); let first = true;
  const h = harness({deliver: () => { if (first) { first = false; return held.promise; } }});
  await h.start('run-a'); h.ready('run-a'); h.call('run-a'); h.result('run-a', new ArrayBuffer(1024 * 1024)); await tick();
  assert.equal(h.chunks.length, 1); h.command({type: 'stop'}); held.resolve(); await tick();
  assert.equal(h.chunks.length, 1, 'canceled session must not deliver its remaining chunks');
});
test('a canceled last chunk cannot delete the new run call with reused call ID', async () => {
  const held = deferred(); let first = true;
  const h = harness({deliver: () => { if (first) { first = false; return held.promise; } }});
  await h.start('run-a'); h.ready('run-a'); h.call('run-a'); h.result('run-a', new ArrayBuffer(8)); await tick();
  await h.start('run-b'); h.ready('run-b'); h.call('run-b'); held.resolve(); await tick();
  h.result('run-b', new ArrayBuffer(8)); await tick();
  assert.equal(h.chunks.filter(chunk => chunk[0] === 'run-b').length, 1, 'old completion must not erase new call ownership');
});
test('old native delivery rejection does not fail the replacement run', async () => {
  const held = deferred(); let first = true;
  const h = harness({deliver: () => { if (first) { first = false; return held.promise; } }});
  await h.start('run-a'); h.ready('run-a'); h.call('run-a'); h.result('run-a', new ArrayBuffer(8)); await tick();
  await h.start('run-b'); h.ready('run-b'); held.reject(Error('stale native cancellation')); await tick();
  assert.equal(h.actions.filter(action => action.action === 'failed' && action.runId === 'run-b').length, 0);
  assert.equal(h.workers.filter(worker => !worker.terminated).length, 2);
});
test('repeated ready and page result messages cannot advance the service twice', async () => {
  const h = harness(); await h.start('run-a'); h.ready('run-a'); h.ready('run-a');
  assert.equal(h.actions.filter(action => action.action === 'ready').length, 1);
  h.page('run-a', 0); const python = h.current('shipu-python');
  python.emit({type: 'result', runId: 'run-a', id: 0, xml: XML});
  python.emit({type: 'result', runId: 'run-a', id: 0, xml: XML});
  assert.equal(h.actions.filter(action => action.action === 'pageResult').length, 1);
  h.page('run-a', 1); python.emit({type: 'result', runId: 'run-a', id: 0, xml: XML});
  assert.equal(h.actions.filter(action => action.action === 'pageResult').length, 1);
  python.emit({type: 'result', runId: 'run-a', id: 1, xml: XML});
  assert.equal(h.actions.filter(action => action.action === 'pageResult').length, 2);
});
test('resumed recognition accepts its first cached-page offset and keeps subsequent order', async () => {
  const h = harness(); await h.start('run-a'); h.ready('run-a'); h.page('run-a', 3);
  const python = h.current('shipu-python'); assert.equal(python.messages.at(-1).data.id, 3);
  python.emit({type: 'result', runId: 'run-a', id: 3, xml: XML}); h.page('run-a', 5);
  assert.equal(h.actions.at(-1).action, 'failed'); assert.match(h.actions.at(-1).message, /页码顺序/);
});
test('stale ready, progress, bootstrap failure, and worker crash cannot affect a new run', async () => {
  const h = harness(); await h.start('run-a'); const old = h.current('shipu-python');
  await h.start('run-b'); const actionCount = h.actions.length;
  old.emit({type: 'ready', runId: 'run-a', offline: true, transport: 'native-rpc'});
  old.emit({type: 'progress', stage: 'python'});
  old.emit({type: 'bootstrapError', runId: 'run-a', msg: 'old error'});
  old.onerror({preventDefault() {}, message: 'old crash'});
  assert.equal(h.actions.length, actionCount); assert.equal(h.workers.filter(worker => !worker.terminated).length, 2);
  h.current('shipu-python').emit({type: 'ready', runId: 'wrong-run', offline: true, transport: 'native-rpc'});
  assert.equal(h.actions.length, actionCount); h.ready('run-b');
  assert.equal(h.actions.at(-1).action, 'ready');
});
test('invalid or empty inference packets never enter the native channel', async () => {
  for (const packet of ['', new ArrayBuffer(0)]) {
    const h = harness(); await h.start('run-a'); h.ready('run-a'); h.call('run-a'); h.result('run-a', packet); await tick();
    assert.equal(h.chunks.length, 0); assert.equal(h.actions.at(-1).action, 'failed');
    assert.ok(h.workers.every(worker => worker.terminated));
  }
});
test('canceling during response text decoding cannot recreate the engine', async () => {
  const text = deferred(), h = harness({fetch: async () => ({ok: true, text: () => text.promise})});
  h.command({type: 'start', id: 'sheet', runId: 'run-a'}); await tick();
  h.command({type: 'stop'}); text.resolve('/* old bootstrap */'); await tick();
  assert.equal(h.workers.length, 0); assert.equal(h.timers.size, 0);
});

(async () => {
  let failures = 0;
  for (const {name, body} of tests) {
    try { await body(); console.log('PASS ' + name); }
    catch (error) { failures++; console.error('FAIL ' + name + '\n' + error.stack); }
  }
  console.log(`${tests.length - failures}/${tests.length} background engine contract tests passed`);
  if (failures) process.exitCode = 1;
})();
