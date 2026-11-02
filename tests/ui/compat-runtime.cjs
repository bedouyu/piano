// REAL model regression with JSPI, SharedArrayBuffer and isolation unavailable.
// The loopback endpoint models only the opaque Android transport; inference and
// Python run the production bundled workers and models, with all WAN requests blocked.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..');
const assets=path.join(root,'app/src/main/assets');
const output=path.join(root,'build/verification');
const prefix='compat-no-jspi-e2e';
const sourceImage=path.join(root,'references/用户提供的乐谱示例.jpg');
const slots=new Map();
const logs=[];
const stats={calls:0,completed:0,maximumPacketBytes:0,maximumChunks:0,maximumChunkBytes:0};
const external=[];
const runId=crypto.randomUUID();
let server,browser;
const TIMEOUT=240000;
const preamble=`\nfor(const name of ['Suspending','promising','Suspender']) delete WebAssembly[name];\ndelete globalThis.SharedArrayBuffer;\n`;
function slot(key){
  if(!slots.has(key)){
    const value={next:0,total:null,chunks:[],bytes:0};
    value.promise=new Promise((resolve,reject)=>{value.resolve=resolve;value.reject=reject;});
    value.timer=setTimeout(()=>value.reject(Error('Local RPC response deadline exceeded')),TIMEOUT);
    slots.set(key,value);
  }
  return slots.get(key);
}
const mime={'.html':'text/html','.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.wasm':'application/wasm','.json':'application/json','.zip':'application/zip','.whl':'application/zip','.otf':'font/otf','.png':'image/png','.jpg':'image/jpeg'};

(async()=>{
  fs.mkdirSync(output,{recursive:true});
  server=http.createServer(async(req,res)=>{
    try{
      const name=new URL(req.url,'http://localhost').pathname;
      if(name.startsWith('/__omr_rpc__/')){
        const parts=name.split('/');
        if(parts.length!==4||parts[2]!==runId||!/^c[1-9][0-9]{0,7}$/.test(parts[3])){res.writeHead(403).end();return;}
        stats.calls++;
        const key=parts[2]+'/'+parts[3],pending=slot(key);
        res.writeHead(200,{'Content-Type':'application/octet-stream','Cache-Control':'no-store'});
        res.flushHeaders(); // Do not block loading model assets while the body waits.
        const bytes=await pending.promise;
        res.end(bytes);slots.delete(key);stats.completed++;
        return;
      }
      if(name==='/__harness.html'){
        res.writeHead(200,{'Content-Type':'text/html'});
        res.end(`<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'; worker-src 'self' blob:; connect-src 'self'; object-src 'none'"><title>Real compatibility test</title>`);return;
      }
      const file=name==='/__score.jpg'?sourceImage:path.resolve(assets,'.'+decodeURIComponent(name));
      if(file!==sourceImage&&!file.startsWith(assets+path.sep)){res.writeHead(403).end();return;}
      if(!fs.existsSync(file)||!fs.statSync(file).isFile()){logs.push('404 '+name);res.writeHead(404).end();return;}
      res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store'});
      if(name==='/local-omr/worker.js'||name==='/local-omr/inference-worker.js'){
        res.end(preamble+fs.readFileSync(file,'utf8'));return;
      }
      fs.createReadStream(file).pipe(res);
    }catch(error){logs.push('SERVER: '+error.message);if(!res.headersSent)res.writeHead(500);res.end();}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin='http://127.0.0.1:'+server.address().port;
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--no-first-run','--disable-extensions']});
  const context=await browser.newContext({viewport:{width:393,height:852}});
  await context.addInitScript(()=>{
    for(const name of ['Suspending','promising','Suspender']) delete WebAssembly[name];
    delete globalThis.SharedArrayBuffer;
  });
  await context.route('**/*',route=>{const url=route.request().url();if(url.startsWith(origin+'/'))return route.continue();external.push(url);route.abort();});
  const page=await context.newPage();
  page.on('pageerror',error=>logs.push('PAGE: '+error.message));
  page.on('console',message=>{logs.push(message.type()+': '+message.text());if(message.type()==='error')console.log(message.text().slice(0,700));});
  await page.exposeFunction('recordCompatProgress',data=>{logs.push(JSON.stringify(data));if(data.type==='progress'||data.type==='error')console.log(JSON.stringify(data).slice(0,500));});
  await page.exposeFunction('deliverCompatChunk',(session,callId,index,total,base64)=>{
    assert.equal(session,runId);assert.match(callId,/^c[1-9][0-9]{0,7}$/);
    assert.ok(total>0&&total<=128);assert.ok(index>=0&&index<total);
    const key=session+'/'+callId,pending=slot(key);
    assert.equal(index,pending.next);if(pending.total!==null)assert.equal(total,pending.total);pending.total=total;
    const data=Buffer.from(base64,'base64');assert.ok(data.length<=512*1024);
    pending.bytes+=data.length;assert.ok(pending.bytes<=64*1024*1024);
    pending.chunks.push(data);pending.next++;
    stats.maximumChunkBytes=Math.max(stats.maximumChunkBytes,data.length);
    if(pending.next===total){clearTimeout(pending.timer);stats.maximumPacketBytes=Math.max(stats.maximumPacketBytes,pending.bytes);stats.maximumChunks=Math.max(stats.maximumChunks,total);pending.resolve(Buffer.concat(pending.chunks));pending.chunks=[];}
  });
  await page.goto(origin+'/__harness.html');
  const capabilities=await page.evaluate(()=>({jspi:typeof WebAssembly.Suspending==='function',sharedArrayBuffer:typeof SharedArrayBuffer==='function',isolated:crossOriginIsolated,secure:isSecureContext,worker:typeof Worker==='function',userAgent:navigator.userAgent}));
  assert.equal(capabilities.jspi,false);assert.equal(capabilities.sharedArrayBuffer,false);assert.equal(capabilities.isolated,false);
  const started=Date.now();
  const result=await page.evaluate(async({runId})=>{
    const python=new Worker(new URL('local-omr/worker.js',location.href),{type:'module'});
    const inference=new Worker(new URL('local-omr/inference-worker.js',location.href),{type:'module'});
    let failure;
    const fail=new Promise((resolve,reject)=>{failure=reject;});
    async function deliver(data){
      const bytes=new Uint8Array(data.packet),total=Math.ceil(bytes.byteLength/(512*1024));
      for(let i=0;i<total;i++){
        const block=bytes.subarray(i*512*1024,(i+1)*512*1024),pieces=[];
        for(let at=0;at<block.length;at+=16384)pieces.push(String.fromCharCode(...block.subarray(at,at+16384)));
        await window.deliverCompatChunk(runId,data.callId,i,total,btoa(pieces.join('')));
      }
    }
    inference.onerror=e=>failure(Error('Inference worker: '+e.message));
    inference.onmessage=e=>{const data=e.data;if(data.type==='inferenceResult')deliver(data).catch(failure);else if(data.type==='error')failure(Error(data.msg||data.error));else window.recordCompatProgress(data);};
    python.onerror=e=>failure(Error('Python worker: '+e.message));
    let readyResolve,resultResolve;
    const ready=new Promise(resolve=>{readyResolve=resolve;});
    const recognized=new Promise(resolve=>{resultResolve=resolve;});
    python.onmessage=e=>{
      const data=e.data;
      if(data.type==='inferenceCall'){inference.postMessage({...data,type:'call'});return;}
      window.recordCompatProgress(data.type==='result'?{type:'result',xmlLength:data.xml.length,ms:data.ms}:data);
      if(data.type==='error'){failure(Error(data.msg||data.error));return;}
      if(data.type==='ready')readyResolve(data);
      if(data.type==='result')resultResolve(data);
    };
    const timer=setTimeout(()=>failure(Error('Compatibility real recognition deadline exceeded')),600000);
    try{
      inference.postMessage({type:'init',runId});
      python.postMessage({type:'init',runId,transport:'native-rpc',rpcBase:location.origin+'/__omr_rpc__/'+runId+'/'});
      const initialized=await Promise.race([ready,fail]);
      const image=await(await fetch('/__score.jpg')).arrayBuffer();
      python.postMessage({type:'recognise',id:0,image},[image]);
      const result=await Promise.race([recognized,fail]);
      return {ready:initialized,recognized:result};
    }finally{clearTimeout(timer);python.terminate();inference.terminate();}
  },{runId});
  assert.ok(result.recognized.xml.includes('score-partwise'));assert.equal(external.length,0);assert.equal(stats.calls,stats.completed);
  fs.writeFileSync(path.join(output,prefix+'.musicxml'),result.recognized.xml);
  const report={passed:true,kind:'real-no-jspi-no-sab-model-regression',device:'Desktop browser with unsupported capabilities explicitly removed; Android device not attached',capabilities,transport:'opaque loopback native-RPC contract simulation',ready:result.ready,seconds:(Date.now()-started)/1000,recognitionMilliseconds:result.recognized.ms,rpc:stats,externalRequests:external,musicXmlBytes:Buffer.byteLength(result.recognized.xml),accuracyManuallyCertified:false};
  fs.writeFileSync(path.join(output,prefix+'.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
})().catch(error=>{console.error(error);process.exitCode=1;}).finally(async()=>{
  for(const value of slots.values())clearTimeout(value.timer);
  fs.writeFileSync(path.join(output,prefix+'.log'),logs.join('\n'));
  if(browser)await browser.close();if(server)await new Promise(resolve=>server.close(resolve));
});
