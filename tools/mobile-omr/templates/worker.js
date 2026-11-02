// Offline Python OMR worker. Local synchronous-XHR RPC requires neither JSPI nor SAB.
import{decodePacket}from'./rpc-codec.js';
const BASE_URL=new URL('./',import.meta.url),PYODIDE_URL=new URL('pyodide/',BASE_URL).href;
const post=message=>self.postMessage(message);
let runId='',rpcBase='',callCounter=0,pyodide,recognise,ready,running=false,pageCounter=0;
function configureTransport(data){
  if(typeof data.runId!=='string'||!/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(data.runId))throw new Error('本地识谱会话无效');
  const url=new URL(data.rpcBase||('/__omr_rpc__/'+encodeURIComponent(data.runId)+'/'),self.location.href);
  if(url.origin!==self.location.origin||url.pathname!=='/__omr_rpc__/'+encodeURIComponent(data.runId)+'/'||url.search||url.hash)throw new Error('本地推理通道地址无效');
  if(runId&&runId!==data.runId)throw new Error('请重新创建识谱工作线程');runId=data.runId;rpcBase=url.href;
}
function rpc(method,args){
  if(!runId||!rpcBase)throw new Error('本地推理通道尚未初始化');if(++callCounter>99999999)throw new Error('本地推理调用次数超限');
  const callId='c'+callCounter;post({type:'inferenceCall',runId,callId,method,args});
  const xhr=new XMLHttpRequest();xhr.open('GET',rpcBase+callId,false);xhr.responseType='arraybuffer';
  try{xhr.send();}catch(error){throw new Error('本地推理连接已中断：'+String(error.message||error));}
  if(xhr.status!==200||!(xhr.response instanceof ArrayBuffer))throw new Error('本地推理通道返回错误（'+xhr.status+'）');
  const packet=decodePacket(xhr.response);if(!packet||packet.ok!==true)throw new Error(packet&&packet.error?String(packet.error):'本地推理未返回有效结果');return packet.value;
}
self.omrOrt=Object.fromEntries(['create','run','generate','data'].map(method=>[method,(...args)=>rpc(method,args)]));
async function init(){
  post({type:'progress',stage:'probe',transport:'native-rpc'});
  const probe=rpc('echo',[{text:'shipu-local-rpc-v1',probe:new Uint8Array([7,13,29])}]);
  if(!probe||probe.text!=='shipu-local-rpc-v1'||!(probe.probe instanceof Uint8Array)||probe.probe[2]!==29)throw new Error('本地推理通道自检失败');
  post({type:'probePassed',runId,transport:'native-rpc'});
  post({type:'log',msg:'本地推理通道自检成功；不需要JSPI或SharedArrayBuffer'});
  post({type:'progress',stage:'python',transport:'native-rpc'});
  const{loadPyodide}=await import(PYODIDE_URL+'pyodide.mjs');
  pyodide=await loadPyodide({indexURL:PYODIDE_URL,packageBaseUrl:PYODIDE_URL,enableRunUntilComplete:false});
  pyodide.setStderr({batched:msg=>post({type:'log',msg})});pyodide.setStdout({batched:msg=>post({type:'log',msg})});
  post({type:'progress',stage:'packages',transport:'native-rpc'});await pyodide.loadPackage(['numpy','opencv-python','pillow','typing-extensions']);
  const lib='/home/pyodide/lib';pyodide.FS.mkdirTree(lib);const source=await fetch(new URL('homr.zip',BASE_URL));
  if(!source.ok)throw new Error('离线识谱程序无法读取');pyodide.unpackArchive(await source.arrayBuffer(),'zip',{extractDir:lib});
  for(const file of['onnxruntime.py','piano_grouping.py','runner.py']){const response=await fetch(new URL('python/'+file,BASE_URL));if(!response.ok)throw new Error('离线识谱模块无法读取：'+file);pyodide.FS.writeFile(lib+'/'+file,await response.text());}
  pyodide.runPython('import sys; sys.path.insert(0, "'+lib+'")');recognise=pyodide.pyimport('runner').recognise;
  post({type:'ready',gpu:false,offline:true,engine:'homr-465',transport:'native-rpc'});
}
self.onmessage=async({data})=>{
  try{
    if(data.type==='init'){configureTransport(data);ready??=init();await ready;}
    else if(data.type==='recognise'){
      if(!ready)throw new Error('请先初始化手机识谱');if(running)throw new Error('请等待当前页识别完成');running=true;await ready;
      const bytes=new Uint8Array(data.image);if(!bytes.length||bytes.byteLength>20*1024*1024)throw new Error('页面图片为空或超过20MB');
      const path='/tmp/score-page-'+(++pageCounter)+'.png';
      try{pyodide.FS.writeFile(path,bytes);const start=performance.now();const xml=recognise(path,'');if(typeof xml!=='string'||!xml.includes('score-partwise'))throw new Error('识谱程序没有返回有效乐谱');post({type:'result',runId,id:data.id,xml,ms:Math.round(performance.now()-start),offline:true,transport:'native-rpc'});}
      finally{try{pyodide.FS.unlink(path);}catch(_){}}
    }
  }catch(error){
    let layout='';
    try{if(pyodide&&recognise)layout=pyodide.runPython('import runner; runner.layout_report()');}catch(_){}
    post({type:'error',runId,id:data.id,msg:String(error&&error.message?error.message:error),layout});
  }
  finally{if(data.type==='recognise')running=false;}
};
