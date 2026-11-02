// Classic, APK-local bootstrap: surface module import failures before model work.
// Created from a Blob fetched by the document, so no worker entry URL lookup is needed.
(function () {
  'use strict';
  let session = '', label = '', status = 'waiting';
  const waiting = [];
  const send = data => self.postMessage(Object.assign({runId:session,worker:label}, data));
  function failure(error, stage) {
    status = 'failed';
    send({type:'bootstrapError',stage,msg:String(error && error.message || error || 'Unknown worker error').slice(0,2000),
      stack:String(error && error.stack || '').slice(0,4000)});
  }
  self.addEventListener('unhandledrejection', event => { failure(event.reason, 'unhandled-rejection'); event.preventDefault(); });
  const receive = function (event) {
    const data = event.data || {};
    if (data.type !== 'bootstrap') {
      if (status === 'loading' && waiting.length < 8) waiting.push(data);
      else if (status !== 'failed') failure(new Error('Worker bootstrap message order invalid'), 'bootstrap');
      return;
    }
    if (status !== 'waiting') { failure(new Error('Worker bootstrap already started'), 'bootstrap'); return; }
    session = String(data.runId || ''); label = String(data.worker || '');
    let entry;
    try {
      entry = new URL(data.entryUrl);
      const allowed = ['/local-omr/worker.js','/local-omr/inference-worker.js'];
      if (entry.origin !== self.location.origin || entry.protocol !== 'https:' || entry.hostname !== 'appassets.androidplatform.net'
        || !allowed.includes(entry.pathname.replace(/^\/assets\//, '/')) || entry.search || entry.hash) throw new Error('Only APK-local worker modules are allowed');
    } catch (error) { failure(error, 'entry-validation'); return; }
    status = 'loading'; send({type:'bootstrapProgress',stage:'module-import',entry:entry.pathname});
    import(entry.href).then(() => {
      const handler = self.onmessage;
      if (typeof handler !== 'function' || handler === receive) throw new Error('Worker module did not install its message handler');
      status = 'ready'; send({type:'bootstrapReady',entry:entry.pathname});
      for (const message of waiting.splice(0)) handler.call(self, {data:message});
    }).catch(error => failure(error, 'module-import'));
  };
  self.onmessage = receive;
})();
