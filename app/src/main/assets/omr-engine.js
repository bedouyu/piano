'use strict';
// Owned by RecognitionService, independent of the activity's view and navigation.
(() => {
  const CALL = /^c[1-9][0-9]{0,7}$/;
  const METHODS = new Set(['echo', 'create', 'run', 'generate', 'data']);
  let run = null, python = null, inference = null, urls = [], timer;
  const pending = new Map();
  let delivery = Promise.resolve();
  function current(owner) { return owner !== null && run === owner; }
  function post(action, payload = {}, owner = run) { if (current(owner)) Android.postMessage(JSON.stringify({action, id:owner.id, runId:owner.runId, ...payload})); }
  function fail(error, owner = run) { if (!current(owner)) return; post('failed', {message:String(error && error.stack || error).slice(0,6000)}, owner); stop(owner); }
  function watchdog(ms, label, owner = run) { clearTimeout(timer); timer = setTimeout(() => fail(label, owner), ms); }
  function stop(owner = run) { if (run !== owner) return; clearTimeout(timer); if (python) python.terminate(); if (inference) inference.terminate(); python = inference = null; for (const url of urls) URL.revokeObjectURL(url); urls = []; pending.clear(); run = null; }
  function bootstrap(data, owner) {
    if (!String(data.type || '').startsWith('bootstrap')) return false;
    if (data.runId !== undefined && String(data.runId) !== owner.runId) return true;
    post('diagnostic', {stage:data.type, worker:data.worker, detail:String(data.msg || data.stage || '').slice(0,1500)}, owner);
    if (data.type === 'bootstrapError') fail(data.msg || '内置组件载入失败', owner);
    return true;
  }
  function makeWorker(entry, name, source, owner) {
    if (!current(owner)) return null;
    const url = URL.createObjectURL(new Blob([source], {type:'text/javascript'})); urls.push(url);
    const worker = new Worker(url, {type:'classic', name});
    worker.onerror = event => { event.preventDefault(); fail(event.message || '识谱线程启动失败', owner); };
    worker.onmessage = event => { if (!current(owner) || bootstrap(event.data || {}, owner)) return; (name === 'shipu-python' ? onPython : onInference)(event.data || {}, owner); };
    worker.postMessage({type:'bootstrap', entryUrl:new URL('local-omr/' + entry, location.href).href, worker:name, runId:owner.runId});
    return worker;
  }
  async function begin(data) {
    stop();
    if (!data || typeof data.id !== 'string' || !data.id || data.id.length > 128 || typeof data.runId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(data.runId)) return;
    const owner = run = {id:data.id, runId:data.runId, page:null, ready:false, pageInFlight:false};
    delivery = Promise.resolve(); watchdog(300000, '载入内置模型超时，请重新识别', owner);
    try {
      const response = await fetch('local-omr/bootstrap.js', {cache:'no-store'});
      if (!current(owner)) return;
      if (!response.ok) throw new Error('内置启动文件无法读取：' + response.status);
      const source = await response.text();
      if (!current(owner)) return;
      if (source.length > 65536 || !source) throw new Error('内置启动文件无效');
      inference = makeWorker('inference-worker.js', 'shipu-ort', source, owner);
      python = makeWorker('worker.js', 'shipu-python', source, owner);
      if (current(owner) && python) python.postMessage({type:'init', runId:owner.runId, transport:'native-rpc', rpcBase:new URL('/__omr_rpc__/' + owner.runId + '/', location.href).href});
    } catch (error) { fail(error, owner); }
  }
  function onPython(data, owner) {
    if (!current(owner) || (data.runId !== undefined && String(data.runId) !== owner.runId)) return;
    if (data.type === 'error') { if (data.layout) post('diagnostic', {stage:'piano-layout', detail:String(data.layout).slice(0,12000)}); fail(data.msg || '识谱失败'); return; }
    if (data.type === 'progress' || data.type === 'probePassed') { post('engineProgress', {stage:data.stage || 'probePassed'}); return; }
    if (data.type === 'ready') {
      if (!data.offline || data.transport !== 'native-rpc') { fail('手机识谱通道不匹配'); return; }
      if (owner.ready) return;
      owner.ready = true;
      clearTimeout(timer); post('ready'); return;
    }
    if (data.type === 'inferenceCall') {
      if (!CALL.test(String(data.callId)) || !METHODS.has(data.method) || pending.has(data.callId) || pending.size >= 8) { fail('本机模型通道请求无效'); return; }
      pending.set(data.callId, {owner, delivering:false}); inference.postMessage({type:'call', runId:owner.runId, callId:data.callId, method:data.method, args:data.args}); return;
    }
    if (data.type === 'result') {
      if (!owner.pageInFlight || Number(data.id) !== owner.page) return;
      if (typeof data.xml !== 'string' || data.xml.length > 4 * 1024 * 1024 || !/<score-partwise(?:\s|>)/.test(data.xml)) { fail('这一页的乐谱结果无效'); return; }
      owner.pageInFlight = false;
      clearTimeout(timer); post('pageResult', {page:owner.page, xml:data.xml, warnings:data.warnings || []});
    }
  }
  function base64(bytes) {
    const pieces = []; for (let offset = 0; offset < bytes.length; offset += 8192) pieces.push(String.fromCharCode.apply(null, bytes.subarray(offset, Math.min(offset + 8192, bytes.length))));
    return btoa(pieces.join(''));
  }
  function onInference(data, owner) {
    if (!current(owner) || (data.runId !== undefined && String(data.runId) !== owner.runId)) return;
    if (data.type === 'progress') { post('diagnostic', {stage:'model', detail:String(data.name || data.stage || '')}); return; }
    if (String(data.runId) !== owner.runId || !pending.has(data.callId)) return;
    if (data.type === 'error') { fail(data.msg || '模型推理未完成'); return; }
    if (data.type !== 'inferenceResult') return;
    const call = pending.get(data.callId);
    if (call.owner !== owner || call.delivering) return;
    call.delivering = true;
    delivery = delivery.then(async () => {
      if (!current(owner)) return;
      if (!(data.packet instanceof ArrayBuffer) || !data.packet.byteLength || data.packet.byteLength > 64 * 1024 * 1024) throw new Error('模型输出超过本机范围');
      const bytes = new Uint8Array(data.packet), size = 512 * 1024, total = Math.ceil(bytes.length / size);
      for (let index = 0; index < total; index++) {
        if (!current(owner)) return;
        // Microtask yielding avoids background-page timer throttling between binary chunks.
        await Android.deliverInferenceChunk(owner.runId, data.callId, index, total, base64(bytes.subarray(index * size, Math.min(bytes.length, (index + 1) * size))));
      }
      if (current(owner) && pending.get(data.callId) === call) pending.delete(data.callId);
    }).catch(error => fail(error, owner));
  }
  window.engineCommand = message => {
    if (!message || typeof message !== 'object') return;
    if (message.type === 'start') { begin(message); return; }
    if (message.type === 'stop') { stop(); return; }
    if (!run || run.runId !== message.runId || message.type !== 'page' || !run.ready || run.pageInFlight) return;
    try {
      if (!Number.isInteger(message.page) || message.page < 0 || message.page >= 40 || (run.page !== null && message.page !== run.page + 1)) throw new Error('识谱页码顺序无效');
      if (typeof message.base64 !== 'string' || !message.base64.length || message.base64.length > 8 * 1024 * 1024) throw new Error('乐谱页面内容过大');
      const decoded = atob(message.base64), bytes = new Uint8Array(decoded.length);
      if (!bytes.length || bytes.length > 4 * 1024 * 1024) throw new Error('乐谱页面内容过大');
      for (let i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
      run.page = message.page; run.pageInFlight = true; watchdog(20 * 60000, '这一页处理超时，已完成页面会保留，请重试');
      python.postMessage({type:'recognise', id:run.page, image:bytes.buffer}, [bytes.buffer]);
    } catch (error) { fail(error); }
  };
  Android.postMessage(JSON.stringify({action:'engineLoaded'}));
})();
