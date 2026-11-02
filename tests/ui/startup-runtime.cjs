// Real bundled UI + bootstrap + Python + models. Only Android I/O is simulated.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'),assets=path.join(root,'app/src/main/assets'),out=path.join(root,'build/verification');
const origin='https://appassets.androidplatform.net';
const browserExe=process.env.SHIPU_BROWSER_EXE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const expectedFailure=process.argv.includes('--fail-import');
const prefix=process.env.SHIPU_TEST_PREFIX||(expectedFailure?'startup-module-failure':'startup-real-e2e');
const preamble="for(const key of ['Suspending','promising','Suspender'])delete WebAssembly[key];delete globalThis.SharedArrayBuffer;\n";
const imagePaths=process.env.SHIPU_INPUT_IMAGES?JSON.parse(process.env.SHIPU_INPUT_IMAGES):['references/用户提供的乐谱示例.jpg'];
assert.ok(Array.isArray(imagePaths)&&imagePaths.length>0&&imagePaths.length<=40);
const images=imagePaths.map(file=>({bytes:fs.readFileSync(path.resolve(root,file)),mime:/\.png$/i.test(file)?'image/png':'image/jpeg'}));
const snapshotDir=process.env.SHIPU_BASELINE_ASSETS&&path.resolve(root,process.env.SHIPU_BASELINE_ASSETS);
const engineOnly=process.env.SHIPU_ENGINE_ONLY==='1';
const baselineOrchestrator=engineOnly?fs.readFileSync(path.join(snapshotDir||assets,'mobile-omr.js'),'utf8'):null;
const standaloneHarness=engineOnly?'<!doctype html><meta charset="UTF-8"><script>var state={current:{id:"real-score",mime:"application/pdf"},currentId:"real-score"};function renderDetail(){};function post(action,data){Android.postMessage(JSON.stringify(Object.assign({action},data||{})))}function toast(){};window.receive=function(){};'+baselineOrchestrator.slice(0,baselineOrchestrator.indexOf("  const card = node("))+'\nfunction busy(){} function setStatus(text,percent,kind){console.log(text)} function updateCompatibility(){} function updateDiagnostics(){} function refreshDetail(){} window.engineDiagnostic=diagnostics; window.receive=function(e){if(e.type==="localRecognitionStart")return begin(e.data);if(e.type==="localPage")return receivePage(e.data)};})();</script>':null;
const runId=crypto.randomUUID(),pending=new Map(),events=[],outside=[],consoleErrors=[];
const stats={calls:0,completed:0,maximumPacketBytes:0,maximumChunks:0};
const headers={'Cross-Origin-Resource-Policy':'same-origin','X-Content-Type-Options':'nosniff','Cache-Control':'no-store'};
const mime={'.html':'text/html','.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.wasm':'application/wasm','.json':'application/json','.zip':'application/zip','.whl':'application/zip','.otf':'font/otf','.mp3':'audio/mpeg'};
let browser,timer,xmlPages,lastDiagnostic,copied;
function slot(callId){
  if(!pending.has(callId)){
    let resolve;const promise=new Promise(r=>resolve=r);
    pending.set(callId,{resolve,promise,next:0,total:0,bytes:0,chunks:[]});
  }return pending.get(callId);
}
(async()=>{
  fs.mkdirSync(out,{recursive:true});
  browser=await chromium.launch({headless:true,executablePath:browserExe,args:['--no-first-run','--disable-extensions']});
  const context=await browser.newContext({viewport:{width:393,height:852}}),page=await context.newPage();
  let succeed,failed;
  const done=new Promise((resolve,reject)=>{succeed=resolve;failed=reject;});
  timer=setTimeout(()=>failed(Error('Real startup/model test timed out')),600000);
  await context.addInitScript(()=>{
    for(const key of ['Suspending','promising','Suspender'])delete WebAssembly[key];delete globalThis.SharedArrayBuffer;
    window.__nativeContractSimulation=true;
    window.Android={postMessage:raw=>window.__nativeAction(raw),deliverInferenceChunk:(...args)=>window.__nativeChunk(...args)};
  });
  const current={id:'real-score',title:'用户乐谱 · 真实模型验证',mime:images.length>1?'application/pdf':images[0].mime,file:'native-io-simulation',reviewed:false};
  const emit=(type,data)=>page.evaluate(envelope=>window.receive(envelope),{type,data});
  const boot=()=>emit('boot',{currentId:current.id,current,library:[current],history:[],score:null,endpoint:'',hasToken:false,nativeRpc:true,nativeRpcMaxBytes:64*1024*1024,appVersion:'0.2.2'});
  await page.exposeFunction('__nativeAction',async raw=>{
    try{
      const data=JSON.parse(raw);events.push({type:'native-action',action:data.action});
      switch(data.action){
        case'boot':await boot();break;
        case'open':await boot();await emit('opened',{ready:false});break;
        case'localRecognize':await emit('localRecognitionStart',{id:current.id,runId,pages:images.length,transport:'native-rpc',rpcBase:origin+'/__omr_rpc__/'+runId+'/'});break;
        case'localPage':assert.ok(Number.isInteger(data.page)&&data.page>=0&&data.page<images.length);await emit('localPage',{id:current.id,runId,page:data.page,pages:images.length,mime:images[data.page].mime,base64:images[data.page].bytes.toString('base64')});break;
        case'localRecognitionResult':xmlPages=data.musicXmlPages;succeed(data);break;
        case'localRecognitionFailed':if(expectedFailure)succeed(data);else failed(Error(data.message||'Recognition failed'));break;
        case'copyLocalDiagnostics':copied=JSON.parse(data.text);break;
        case'recognize':failed(Error('Unexpected remote recognition fallback'));break;
      }
    }catch(error){failed(error);}
  });
  await page.exposeFunction('__nativeChunk',async(session,callId,index,total,base64)=>{
    assert.equal(session,runId);assert.match(callId,/^c[1-9][0-9]{0,7}$/);assert.ok(total>0&&total<=128);
    const item=slot(callId);assert.equal(index,item.next);if(item.total)assert.equal(total,item.total);item.total=total;
    const bytes=Buffer.from(base64,'base64');assert.ok(bytes.length<=512*1024);item.bytes+=bytes.length;assert.ok(item.bytes<=64*1024*1024);
    item.next++;item.chunks.push(bytes);
    if(item.next===total){stats.maximumPacketBytes=Math.max(stats.maximumPacketBytes,item.bytes);stats.maximumChunks=Math.max(stats.maximumChunks,total);item.resolve(Buffer.concat(item.chunks));item.chunks=[];}
  });
  await context.route('**/*',async route=>{
    try{
      const request=route.request(),url=new URL(request.url());
      if(url.origin!==origin){outside.push(url.href);await route.abort();return;}
      if(engineOnly&&url.pathname==='/startup-harness.html'){await route.fulfill({headers:{...headers,'Content-Security-Policy':"default-src 'self' data:; script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'; worker-src 'self' blob:; connect-src 'self'; object-src 'none'"},contentType:'text/html',body:'<!doctype html><meta charset="UTF-8"><script src="/startup-harness.js"></script>'});return;}
      if(engineOnly&&url.pathname==='/startup-harness.js'){await route.fulfill({headers,contentType:'text/javascript',body:standaloneHarness.slice(standaloneHarness.indexOf('<script>')+8,standaloneHarness.lastIndexOf('</script>'))});return;}
      if(url.pathname.startsWith('/__omr_rpc__/')){
        const parts=url.pathname.split('/');assert.equal(parts[2],runId);assert.match(parts[3],/^c[1-9][0-9]{0,7}$/);
        stats.calls++;const body=await slot(parts[3]).promise;await route.fulfill({status:200,headers,contentType:'application/octet-stream',body});stats.completed++;return;
      }
      const file=path.resolve(assets,'.'+decodeURIComponent(url.pathname));
      assert.ok(file.startsWith(assets+path.sep));
      if(!fs.existsSync(file)){await route.fulfill({status:404,headers,body:'missing'});return;}
      const snapshot=snapshotDir&&path.join(snapshotDir,path.basename(file));
      let body=fs.readFileSync(snapshot&&fs.existsSync(snapshot)?snapshot:file);
      if(expectedFailure && url.pathname==='/local-omr/inference-worker.js')body=Buffer.from("throw new Error('STARTUP_IMPORT_FAILURE_MARKER');");
      if(['/local-omr/worker.js','/local-omr/inference-worker.js','/local-omr/bootstrap.js'].includes(url.pathname))body=Buffer.from(preamble+body.toString('utf8'));
      await route.fulfill({headers,contentType:mime[path.extname(file)]||'application/octet-stream',body});
    }catch(error){failed(error);try{await route.abort();}catch(_){}}
  });
  page.on('pageerror',error=>{consoleErrors.push(error.message);failed(error);});
  page.on('console',message=>{if(message.type()==='error')consoleErrors.push(message.text().slice(0,2000));});
  await page.goto(origin+(engineOnly?'/startup-harness.html':'/index.html'));
  if(!engineOnly){await page.locator('[data-open="real-score"]').click();await page.locator('#detail.active').waitFor();}
  const capabilities=await page.evaluate(()=>({jspi:typeof WebAssembly.Suspending==='function',sab:typeof SharedArrayBuffer==='function',isolated:crossOriginIsolated,userAgent:navigator.userAgent}));
  assert.equal(capabilities.jspi,false);assert.equal(capabilities.sab,false);assert.equal(capabilities.isolated,false);
  const started=Date.now();if(engineOnly)await page.evaluate(()=>post('localRecognize'));else await page.locator('#recognize-button').click();
  await done;
  lastDiagnostic=JSON.parse(engineOnly?await page.evaluate(()=>window.engineDiagnostic()):await page.locator('#mobile-omr-diagnostics').textContent());
  if(expectedFailure){
    assert.match(lastDiagnostic.lastError,/STARTUP_IMPORT_FAILURE_MARKER/);
    assert.equal(lastDiagnostic.lastErrorDetails.worker,'shipu-ort');assert.equal(lastDiagnostic.lastErrorDetails.stage,'module-import');
    assert.equal(await page.locator('#mobile-omr-error-details').isVisible(),true);
    assert.doesNotMatch(await page.locator('#mobile-omr-status').innerText(),/STARTUP_IMPORT_FAILURE_MARKER/);
    await page.locator('#mobile-omr-error-details summary').click();
    await page.locator('#mobile-omr-error-details [data-mobile-omr-action="copyDiagnostic"]').click();
    assert.match(copied.lastError,/STARTUP_IMPORT_FAILURE_MARKER/);
    await page.reload();await page.locator('[data-open="real-score"]').waitFor();
    const persisted=JSON.parse(await page.locator('#mobile-omr-diagnostics').textContent());
    assert.match(persisted.lastError,/STARTUP_IMPORT_FAILURE_MARKER/);assert.equal(persisted.phase,'idle');
    assert.equal(outside.length,0);
    fs.writeFileSync(path.join(out,prefix+'.json'),JSON.stringify({passed:true,kind:'real-worker-module-import-fault-injection',androidDevice:false,browserVersion:browser.version(),checks:['caught original import error','worker and stage retained','direct error details on score page','friendly user message','native copy action receives original error','last error survives page reload','no upload fallback'],diagnostics:lastDiagnostic},null,2));
    console.log('PASS: real worker import failure, direct copy and persistent diagnostics');return;
  }
  assert.equal(xmlPages.length,images.length);for(const xml of xmlPages)assert.match(xml,/<score-partwise/);assert.equal(outside.length,0);
  assert.equal(lastDiagnostic.workerBoot['shipu-ort'],true);assert.equal(lastDiagnostic.workerBoot['shipu-python'],true);assert.equal(lastDiagnostic.probePassed,true);
  assert.equal(stats.calls,stats.completed);assert.ok(stats.maximumPacketBytes>16*1024*1024);
  xmlPages.forEach((xml,index)=>fs.writeFileSync(path.join(out,prefix+(xmlPages.length>1?'-page-'+(index+1):'')+'.musicxml'),xml));
  fs.writeFileSync(path.join(out,prefix+'.json'),JSON.stringify({passed:true,kind:engineOnly?'production-offline-orchestrator-python-model-with-android-io-contract-simulation':'production-ui-bootstrap-python-model-with-android-io-contract-simulation',imagePaths,androidDevice:false,browserVersion:browser.version(),capabilities,seconds:(Date.now()-started)/1000,rpc:stats,externalRequests:outside,diagnostics:lastDiagnostic,accuracyManuallyCertified:false},null,2));
  console.log(JSON.stringify({passed:true,browser:browser.version(),seconds:(Date.now()-started)/1000,rpc:stats,workerBoot:lastDiagnostic.workerBoot,xmlBytes:Buffer.byteLength(xmlPages[0]),externalRequests:outside.length},null,2));
})().catch(async error=>{
  console.error(error);process.exitCode=1;
  if(browser){try{const page=browser.contexts()[0].pages()[0];lastDiagnostic=JSON.parse(await page.locator('#mobile-omr-diagnostics').textContent());}catch(_){}}
}).finally(async()=>{
  clearTimeout(timer);
  fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,prefix+'.log'),JSON.stringify({events,consoleErrors,diagnostics:lastDiagnostic},null,2));
  for(const item of pending.values())item.resolve(Buffer.alloc(0));
  if(browser)await browser.close();
});
