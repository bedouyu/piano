// Real local OMR pipeline test. No Android bridge fixture or simulated inference.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname,'../..');
const assets = path.join(root,'app/src/main/assets');
const output = path.join(root,'build/verification');
const image = path.join(root,'references/用户提供的乐谱示例.jpg');
const cpuOnly = process.argv.includes('--cpu');
const prefix = cpuOnly ? 'mobile-local-cpu-e2e' : 'mobile-local-e2e';
const mime = {'.html':'text/html','.js':'application/javascript','.mjs':'application/javascript','.css':'text/css','.wasm':'application/wasm','.json':'application/json','.zip':'application/zip','.whl':'application/zip','.onnx':'application/octet-stream','.otf':'font/otf','.jpg':'image/jpeg'};
let browser,server;
const logs=[];
let external=[];
(async()=>{
  fs.mkdirSync(output,{recursive:true});
  server=http.createServer((req,res)=>{
    const name=new URL(req.url,'http://localhost').pathname;
    const file=name==='/__score.jpg'?image:path.resolve(assets,'.'+decodeURIComponent(name));
    if(file!==image&&!file.startsWith(assets+path.sep)){res.writeHead(403).end();return;}
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}
    res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream','Cross-Origin-Opener-Policy':'same-origin','Cross-Origin-Embedder-Policy':'require-corp','Cross-Origin-Resource-Policy':'same-origin'});
    if(cpuOnly && name==='/local-omr/worker.js') {
      const original=fs.readFileSync(file,'utf8');
      const patched=original.replace('useGpu = !!(self.navigator.gpu && (await navigator.gpu.requestAdapter()));','useGpu = false;');
      if(patched===original){res.end('throw new Error("CPU branch test override failed");');return;}
      res.end(patched); return;
    }
    fs.createReadStream(file).pipe(res);
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin='http://127.0.0.1:'+server.address().port;
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--no-first-run','--disable-extensions']});
  const context=await browser.newContext({viewport:{width:393,height:852}});
  await context.route('**/*',route=>{const url=route.request().url();if(url.startsWith(origin+'/'))return route.continue();external.push(url);route.abort();});
  const page=await context.newPage();
  page.on('console',m=>{const entry=m.type()+': '+m.text();logs.push(entry);if(m.type()==='error')console.log(entry.slice(0,600));});
  page.on('pageerror',e=>logs.push('PAGE ERROR: '+e.message));
  await page.exposeFunction('runtimeProgress',m=>{logs.push(JSON.stringify(m));if(m.type==='progress'||m.type==='error')console.log(JSON.stringify(m).slice(0,500));});
  await page.goto(origin+'/index.html');
  const caps=await page.evaluate(()=>({jspi:typeof WebAssembly.Suspending==='function',isolated:crossOriginIsolated,secure:isSecureContext,worker:typeof Worker==='function',userAgent:navigator.userAgent}));
  assert.ok(caps.jspi,'real JSPI is required');assert.ok(caps.secure,'loopback test context must be secure');
  const started=Date.now();
  const result=await page.evaluate(async()=>{
    const worker=new Worker(new URL('local-omr/worker.js',location.href),{type:'module'});
    try{
      const ready=await new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>reject(Error('real OMR initialization timed out')),300000);
        worker.onerror=e=>{clearTimeout(timer);reject(Error('Worker: '+e.message));};
        worker.onmessage=e=>{window.runtimeProgress(e.data);if(e.data.type==='ready'){clearTimeout(timer);resolve(e.data);}if(e.data.type==='error'){clearTimeout(timer);reject(Error(e.data.msg));}};
        worker.postMessage({type:'init'});
      });
      const data=await(await fetch('/__score.jpg')).arrayBuffer();
      const recognized=await new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>reject(Error('real local recognition timed out')),300000);
        worker.onmessage=e=>{window.runtimeProgress(e.data.type==='result'?{type:'result',id:e.data.id,ms:e.data.ms,xmlLength:e.data.xml.length}:e.data);if(e.data.type==='result'){clearTimeout(timer);resolve(e.data);}if(e.data.type==='error'){clearTimeout(timer);reject(Error(e.data.msg));}};
        worker.postMessage({type:'recognise',id:0,image:data},[data]);
      });
      return {ready,recognized};
    }finally{worker.terminate();}
  });
  const xml=result.recognized.xml;
  assert.ok(xml&&xml.includes('score-partwise'),'real MusicXML must be returned');
  assert.equal(external.length,0,'no external resource or score upload permitted');
  fs.writeFileSync(path.join(output,prefix+'.musicxml'),xml);
  const report={passed:true,kind:'real-local-browser-omr',device:'Desktop Edge; Android device not attached',cpuOnlyTestOverride:cpuOnly,capabilities:caps,seconds:(Date.now()-started)/1000,ready:result.ready,recognitionMilliseconds:result.recognized.ms,musicXmlBytes:Buffer.byteLength(xml),externalRequests:external,sourceImage:'用户提供的乐谱示例.jpg',accuracyManuallyCertified:false};
  fs.writeFileSync(path.join(output,prefix+'.json'),JSON.stringify(report,null,2));
  fs.writeFileSync(path.join(output,prefix+'.log'),logs.join('\n'));
  console.log(JSON.stringify(report,null,2));
})().catch(error=>{fs.writeFileSync(path.join(output,prefix+'.log'),logs.join('\n'));console.error(error);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();if(server)await new Promise(resolve=>server.close(resolve));});
