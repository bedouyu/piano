(()=>{
  const score=state.score;
  const events=score?score.measures.flatMap(measure=>measure.events):[];
  const notes=events.flatMap(event=>event.notes);
  const diagnostics=JSON.parse(document.getElementById('mobile-omr-diagnostics').textContent);
  return JSON.stringify({androidDevice:true,recognitionSaved:!!score,score:score?{title:score.title,measures:score.measures.length,events:events.length,notes:notes.length,warnings:score.warnings||[]}:null,
    appVersion:'0.2.2',userAgent:navigator.userAgent,currentId:state.currentId,screen:state.screen,status:document.getElementById('mobile-omr-status').textContent,diagnostics});
})()
