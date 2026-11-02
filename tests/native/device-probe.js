(async()=>{
  const files=['index.html','mobile-omr.js','fonts/BRAVURA-LICENSE.txt','piano/SALAMANDER-LICENSE.txt','local-omr/bootstrap.js','local-omr/worker.js','local-omr/inference-worker.js','local-omr/rpc-codec.js','assets/local-omr/bootstrap.js','local-omr/manifest.json'];
  const responses=[];
  for(const file of files){try{const response=await fetch(new URL(file,location.href),{cache:'no-store'});const body=await response.text();responses.push({file,status:response.status,type:response.headers.get('content-type'),bytes:body.length,error:response.ok?undefined:body.slice(0,200)});}catch(error){responses.push({file,error:String(error)});}}
  return JSON.stringify({responses,native:JSON.parse(Android.localOmrDiagnostics()),library:state.library.map(item=>({id:item.id,title:item.title,mime:item.mime}))});
})()
