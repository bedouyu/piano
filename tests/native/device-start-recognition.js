(async()=>{
  const item=state.library.find(item=>item.mime && item.mime.startsWith('image/'));
  if(!item)throw new Error('Import the test image into the app first');
  post('open',{id:item.id});
  for(let i=0;i<100 && state.currentId!==item.id;i++)await new Promise(resolve=>setTimeout(resolve,20));
  if(state.currentId!==item.id)throw new Error('The score did not open');
  document.getElementById('recognize-button').click();
  return JSON.stringify({title:item.title,requestedLocalRecognition:true,confirmation:'Waiting for the native on-device processing confirmation'});
})()
