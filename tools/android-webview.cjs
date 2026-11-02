// Debug only our app's WebView over the user-authorized Android USB connection.
const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),adb=path.join(root,'.toolchain/adb/platform-tools/adb.exe');
const args=process.argv.slice(2),serial=args.shift(),command=args.shift()||'diagnostics';
if(!serial||!/^[A-Za-z0-9_.:-]+$/.test(serial))throw Error('Pass the connected test device identifier');
function shell(...items){const result=spawnSync(adb,['-s',serial,...items],{encoding:'utf8',windowsHide:true,timeout:15000});if(result.status!==0)throw Error((result.stderr||result.stdout||'ADB failed').trim());return result.stdout.trim();}
let port,socket;
(async()=>{
  const pid=shell('shell','pidof','com.shipu.assistant').split(/\s+/)[0];
  if(!/^\d+$/.test(pid))throw Error('Open 识谱助手 on the connected test phone first');
  port=shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid);
  if(!/^\d+$/.test(port))throw Error('Invalid debug forwarding port');
  const targets=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json();
  const page=targets.find(target=>target.type==='page'&&target.url.startsWith('https://appassets.androidplatform.net/'));
  if(!page)throw Error('The test build has not exposed the app-local WebView');
  socket=new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',()=>reject(Error('WebView debug connection failed')),{once:true});});
  let nextId=0;const requests=new Map();
  socket.addEventListener('message',event=>{
    const reply=JSON.parse(event.data);if(!requests.has(reply.id))return;
    const request=requests.get(reply.id);requests.delete(reply.id);clearTimeout(request.timer);
    if(reply.error)request.reject(Error(reply.error.message));else request.resolve(reply.result);
  });
  function call(method,params={}){return new Promise((resolve,reject)=>{const id=++nextId,timer=setTimeout(()=>{requests.delete(id);reject(Error('Debug call timeout: '+method));},15000);requests.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});}
  await call('Runtime.enable');
  let report;
  if(command==='screenshot'){
    const output=path.resolve(args[0]||path.join(root,'build/verification/android-app.png'));
    if(!output.startsWith(path.join(root,'build')+path.sep))throw Error('Save test images inside the project build directory');
    const image=await call('Page.captureScreenshot',{format:'png'});fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,Buffer.from(image.data,'base64'));report={saved:output};
  }else{
    const expression=command==='eval'?fs.readFileSync(path.resolve(args[0]),'utf8'):
      `JSON.stringify({url:location.href,userAgent:navigator.userAgent,diagnostics:JSON.parse(document.getElementById('mobile-omr-diagnostics').textContent),screen:state.screen,title:document.getElementById('detail-title').textContent,status:document.getElementById('mobile-omr-status').textContent})`;
    const result=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true,userGesture:true});
    if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));
    report=result.result.value;
    if(typeof report==='string'){try{report=JSON.parse(report);}catch(_){}}
    if(args[1]){const output=path.resolve(args[1]);if(!output.startsWith(path.join(root,'build')+path.sep))throw Error('Save reports inside build');fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2));}
  }
  console.log(JSON.stringify(report,null,2));
})().catch(error=>{console.error(error.message);process.exitCode=1;}).finally(()=>{
  if(socket)socket.close();if(port)try{shell('forward','--remove','tcp:'+port);}catch(_){}
});
