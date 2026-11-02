'use strict';
const $ = id => document.getElementById(id);
const NS = 'http://www.w3.org/2000/svg';
const savedWidth = Number(localStorage.getItem('keyboardWidth'));
const state = {screen:'home',library:[],history:[],currentId:'demo',current:{},score:null,practice:null,hand:'RIGHT',mode:'NOTE',loop:false,swap:false,follow:true,hint:false,labels:localStorage.getItem('keyLabels')==='true',keyWidth:savedWidth>=18&&savedWidth<=64?savedWidth:43,page:0,pages:1,previewRequest:0,previewLoading:false,editorMeasure:0,draft:null,practiceStart:0,practiceEnd:null,playback:{state:'stopped'},job:null};
let toastTimer, startWhite=23;
const whites=[];
for(let midi=21;midi<=108;midi++)if(![1,3,6,8,10].includes(midi%12))whites.push(midi);
function icon(name){const svg=document.createElementNS(NS,'svg'),use=document.createElementNS(NS,'use');use.setAttribute('href','#i-'+name);svg.append(use);return svg;}
function element(tag,className,text){const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node;}
function post(action,params={}){const data={action,...params};if(window.Android)window.Android.postMessage(JSON.stringify(data));else{window.dispatchEvent(new CustomEvent('native-message',{detail:data}));if(!window.__UI_TEST__)toast('当前为界面预览，请安装安卓应用使用此功能');}}
function toast(text){$('toast').textContent=text;$('toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('visible'),4500);}
function showScreen(name){if(name!=='practice'&&['playing','paused'].includes(state.playback.state))stopPlayback();document.querySelectorAll('.screen').forEach(n=>n.classList.toggle('active',n.id===name));state.screen=name;if(name==='settings')$('labels-setting').checked=state.labels;if(name!=='practice'&&$('practice').classList.contains('focus')){$('practice').classList.remove('focus');$('focus-toggle').setAttribute('aria-pressed','false');$('focus-toggle').textContent='全屏练习';}if(name==='practice'){requestAnimationFrame(()=>{renderKeyboard();renderPractice();});}}
function openSheet(id){document.querySelectorAll('.sheet').forEach(n=>n.classList.add('hidden'));$(id).classList.remove('hidden');$('modal-backdrop').classList.remove('hidden');}
function closeSheet(){document.querySelectorAll('.sheet').forEach(n=>n.classList.add('hidden'));$('modal-backdrop').classList.add('hidden');}
function pitchName(midi){return ['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'][midi%12]+(Math.floor(midi/12)-1);}

function renderLibrary(){
  const container=$('library-list');container.replaceChildren();
  const items=[{id:'demo',title:'初见 · 八小节练习',demo:true,score:true,reviewed:true},...state.library.slice().reverse()];
  $('library-count').textContent=String(items.length).padStart(2,'0');
  for(const item of items){
    const row=element('article','library-row'),card=element('button','score-card');card.dataset.open=item.id;
    const thumb=element('span','score-thumb');thumb.append(icon('note'));
    const copy=element('div','score-copy');copy.append(element('h3','',item.title));
    const line=element('p');line.append(element('span','card-badge'+(!item.score?' pending':''),item.demo?'原创练习':item.score?(item.reviewed?'已校对':'待校对'):'待识别'));
    line.append(document.createTextNode(item.demo?'8 小节 · 双谱表':item.score?item.score.measures.length+' 小节':item.mime==='application/pdf'?'PDF 乐谱':'图片乐谱'));copy.append(line);
    card.append(thumb,copy,icon('arrow'));card.lastChild.classList.add('chevron');row.append(card);
    if(!item.demo){const remove=element('button','library-delete');remove.dataset.deleteScore=item.id;remove.setAttribute('aria-label','删除乐谱：'+item.title);remove.append(icon('trash'),element('span','','删除'));row.append(remove);}
    container.append(row);
  }
}
function setup(){
  if(!state.score){toast('请先识别或手动校对这份乐谱');showScreen('detail');return;}
  const count=state.score.measures.length;
  for(const id of ['start-measure','end-measure']){const select=$(id);select.replaceChildren();for(let i=0;i<count;i++){const measure=state.score.measures[i],opt=element('option','',`第 ${measure.number} 小节`+(measure.sourcePage>=0?` · 第 ${measure.sourcePage+1} 页`:''));opt.value=i;select.append(opt);}}
  $('start-measure').value=Math.min(count-1,state.practiceStart);$('end-measure').value=Math.min(count-1,state.practiceEnd??count-1);
  $('loop-setting').checked=state.loop;$('swap-setting').checked=state.swap;selectOption('hand-options',state.hand);selectOption('mode-options',state.mode);openSheet('setup-sheet');
}
function selectOption(id,value){$(id).querySelectorAll('button').forEach(button=>button.classList.toggle('selected',button.dataset.value===value));}
function startPractice(){
  const start=+$('start-measure').value,end=+$('end-measure').value;if(start>end){toast('结束小节应不早于起始小节');return;}
  stopPlayback();state.practiceStart=start;state.practiceEnd=end;state.loop=$('loop-setting').checked;state.swap=$('swap-setting').checked;state.hint=false;state.practice=null;
  const tempo=state.score.tempoBpm||state.score.tempo||state.score.measures[start].tempo||100;$('playback-tempo').value=Math.round(tempo);
  closeSheet();showScreen('practice');post('configure',{hand:state.hand,mode:state.mode,start,end,loop:state.loop,swap:state.swap});
}
function notePosition(note,clef){const n=note.name||pitchName(note.midi),match=/^([A-G])[^\d-]*(-?\d)/i.exec(n);let letter,octave;if(match){letter=match[1].toUpperCase();octave=+match[2];}else{letter=pitchName(note.midi)[0];octave=Math.floor(note.midi/12)-1;}const diatonic=octave*7+({C:0,D:1,E:2,F:3,G:4,A:5,B:6}[letter]);const bottom=clef==='treble'?4*7+2:2*7+4;return {offset:diatonic-bottom,accidental:n.includes('♭')||n.includes('b')?'♭':n.includes('♯')||n.includes('#')?'♯':''};}
function svgNode(tag,attrs,text){const node=document.createElementNS(NS,tag);for(const [name,value]of Object.entries(attrs))node.setAttribute(name,value);if(text!==undefined)node.textContent=text;return node;}
function handIncludes(note){const right=state.swap?note.staff>1:note.staff<=1;return state.hand==='BOTH'||(state.hand==='RIGHT'?right:!right);}
function displayedStep(){
  const playback=state.playback;
  if(playback.state==='playing'||playback.state==='paused'){
    const measure=state.score?.measures[playback.measureIndex],event=measure?.events[playback.eventIndex];
    if(event){const notes=event.notes.filter(handIncludes);return {measureIndex:playback.measureIndex,eventIndex:playback.eventIndex,noteIndex:event.notes.indexOf(notes[0]),notes:state.mode==='NOTE'?notes.slice(0,1):notes,onset:event.onset};}
  }
  return state.practice?.step||null;
}
function renderStaff(){
  const container=$('staff');container.replaceChildren();if(!state.score)return;
  const step=displayedStep(),mi=step?step.measureIndex:state.practiceStart,measure=state.score.measures[mi];if(!measure)return;
  // The NOTE view contains only the one expected note, including when a chord is being learnt.
  const single=state.mode==='NOTE',selected=single?(step?.notes||[]).slice(0,1):measure.events.flatMap(event=>event.notes);
  const staves=single?[selected[0]?.staff>1?2:1]:(measure.events.some(e=>e.notes.some(n=>n.staff>1))?[1,2]:[1]);
  const height=staves.length===2?198:143,svg=svgNode('svg',{viewBox:`0 0 340 ${height}`,role:'img','aria-label':`第${measure.number}小节，${single?'仅显示当前音符':'黄色表示当前位置'}`});
  const maxOnset=Math.max(1,measure.duration||0,...measure.events.map(e=>e.onset+Math.min(e.duration||1,1))),eventX=onset=>64+(onset/maxOnset)*249;
  if(!single&&step){const x=eventX(step.onset??measure.events[step.eventIndex]?.onset??0);svg.append(svgNode('rect',{class:'current-position',x:x-12,y:12,width:25,height:height-23,rx:7,fill:'#fff0ab'}));svg.append(svgNode('line',{x1:x,y1:10,x2:x,y2:height-10,stroke:'#d49b14','stroke-width':2,'stroke-dasharray':'3 4'}));}
  for(const staff of staves){
    const bottom=staves.length===1?95:staff===1?75:160,notes=selected.filter(n=>(n.staff<=1?1:2)===staff),mean=notes.reduce((sum,n)=>sum+n.midi,0)/Math.max(1,notes.length),clef=staves.length===1?(mean<57?'bass':'treble'):(staff===1?(mean<48?'bass':'treble'):(mean>76?'treble':'bass'));
    for(let i=0;i<5;i++)svg.append(svgNode('line',{x1:8,y1:bottom-i*9,x2:330,y2:bottom-i*9,stroke:'#d2d7c9','stroke-width':.9}));
    svg.append(svgNode('text',{x:15,y:clef==='treble'?bottom-2:bottom-24,'font-size':clef==='treble'?48:34,'font-family':'Bravura,serif',fill:'#475642'},clef==='treble'?'\uE050':'\uE062'));
    svg.append(svgNode('line',{x1:330,y1:bottom-36,x2:330,y2:bottom,stroke:'#9ca88d','stroke-width':1}));
    const events=single?[{onset:0,duration:selected[0]?.duration||1,notes:selected}]:measure.events;
    for(let ei=0;ei<events.length;ei++){
      const event=events[ei],x=single?174:eventX(event.onset);
      event.notes.forEach((note,ni)=>{
        if((note.staff<=1?1:2)!==staff)return;
        const active=single||!!step&&ei===step.eventIndex&&step.notes.some(n=>n.midi===note.midi&&n.staff===note.staff),dimmed=!handIncludes(note),past=!single&&step&&event.onset<step.onset;
        const color=active?'#8e6410':dimmed?'#d3d8cb':past?'#587856':note.tiedContinuation?'#b6c1a8':'#87957a',pos=notePosition(note,clef),y=bottom-pos.offset*4.5;
        if(single)svg.append(svgNode('rect',{x:x-20,y:y-21,width:42,height:42,rx:12,fill:'#fff0ab'}));
        for(let off=-2;off>=pos.offset;off-=2)svg.append(svgNode('line',{x1:x-9,y1:bottom-off*4.5,x2:x+10,y2:bottom-off*4.5,stroke:color,'stroke-width':1}));
        for(let off=10;off<=pos.offset;off+=2)svg.append(svgNode('line',{x1:x-9,y1:bottom-off*4.5,x2:x+10,y2:bottom-off*4.5,stroke:color,'stroke-width':1}));
        if(pos.accidental)svg.append(svgNode('text',{x:x-18,y:y+4,'font-size':16,fill:color},pos.accidental));
        svg.append(svgNode('ellipse',{'data-note-midi':note.midi,class:active?'note-current':'note-context',cx:x,cy:y,rx:5.7,ry:3.9,transform:`rotate(-22 ${x} ${y})`,fill:(note.duration||event.duration)>=2?'#fffefa':color,stroke:color,'stroke-width':1.5}));
        const down=pos.offset>4;svg.append(svgNode('line',{x1:x+(down?-5:5),y1:y,x2:x+(down?-5:5),y2:y+(down?27:-27),stroke:color,'stroke-width':1.3}));
        if(note.tiedContinuation)svg.append(svgNode('path',{d:`M${x-15} ${y+8}Q${x-7} ${y+15} ${x+1} ${y+8}`,fill:'none',stroke:color,'stroke-width':1}));
      });
    }
  }
  container.append(svg);$('measure-label').textContent=`第 ${measure.number} 小节`+(measure.sourcePage>=0?` · 原谱第 ${measure.sourcePage+1} 页`:'');$('key-label').textContent=`${measure.beats||state.score.beats}/${measure.beatType||state.score.beatType}`;
}
function renderPractice(){if(!state.score)return;$('practice-title').textContent=state.score.title;$('hand-label').textContent=({LEFT:'左手',RIGHT:'右手',BOTH:'双手'}[state.hand])+' · '+(state.mode==='NOTE'?'逐音练习':'小节练习')+(state.loop?' · 循环':'');const p=state.practice;if(p){$('step-label').textContent=`${p.finished?p.total:Math.min(p.index+1,p.total)} / ${p.total}`;$('practice-progress').style.width=(p.total?100*p.index/p.total:0)+'%';}renderStaff();renderHints();if(state.follow)followCurrent();}
function receiveState(data){const previousIndex=state.practice?state.practice.index:-1;state.practice=data;if(data.index!==previousIndex){state.hint=false;$('hint-button').classList.remove('on');}if(data.result==='WRONG'){$('feedback').classList.add('wrong');$('feedback-title').textContent='还差一点，再看一眼五线谱';$('feedback-detail').textContent='这个音不对，已点齐的和弦音会保留';const key=document.querySelector(`.piano-key[data-midi="${data.played}"]`);if(key){key.classList.add('wrong-key');setTimeout(()=>key.classList.remove('wrong-key'),350);}}else{$('feedback').classList.remove('wrong');if(data.result==='PARTIAL'){$('feedback-title').textContent='这个音对了，继续补齐和弦';$('feedback-detail').textContent=`已找到 ${data.matched.length} / ${data.step.notes.length} 个音，可依次点击`;}else{$('feedback-title').textContent=data.result==='ADVANCED'?'很好，继续下一个音符':'找到黄色音符的位置';$('feedback-detail').textContent=state.mode==='NOTE'?'轻点下方琴键，弹对后自动继续':'按谱顺序练习，和弦可用一根手指依次点齐';}}if(state.screen==='practice')renderPractice();if(data.finished&&data.result!=='READY'&&data.total>0){$('result-accuracy').textContent=Math.round(data.accuracy*100)+'%';$('result-notes').textContent=data.correct;$('result-wrong').textContent=data.wrong;openSheet('complete-sheet');}else if(data.finished&&data.total===0&&data.result==='START')toast('所选声部或小节没有可练习音符，请调整范围');}
function keyWidth(){return Math.max(18,Math.min(64,state.keyWidth));}
function setKeyWidth(value){state.keyWidth=Math.max(18,Math.min(64,Math.round(Number(value)||43)));localStorage.setItem('keyboardWidth',String(state.keyWidth));$('keyboard-zoom').value=state.keyWidth;renderKeyboard();if(state.follow)followCurrent();}
function renderKeyboard(){const keyboard=$('keyboard'),width=keyWidth();document.documentElement.style.setProperty('--key-width',width+'px');$('keyboard-zoom').value=width;if(keyboard.dataset.width===String(width)){positionKeyboard();return;}keyboard.dataset.width=String(width);keyboard.replaceChildren();keyboard.style.width=whites.length*width+'px';for(let midi=21;midi<=108;midi++){const black=[1,3,6,8,10].includes(midi%12),key=element('div','piano-key '+(black?'black':'white'));key.dataset.midi=midi;key.setAttribute('role','button');key.setAttribute('aria-label',pitchName(midi));if(black){const preceding=whites.findIndex(n=>n>midi)-1;key.style.left=((preceding+1)*width-width*.295)+'px';}else key.style.left=(whites.indexOf(midi)*width)+'px';key.append(element('span','',(state.labels||midi%12===0)?pitchName(midi):''));keyboard.append(key);}positionKeyboard();renderHints();renderPlaybackKeys();}
function positionKeyboard(){const width=keyWidth(),windowWidth=$('keyboard-window').clientWidth||360,visible=Math.max(1,Math.floor(windowWidth/width)),max=Math.max(0,whites.length-visible);startWhite=Math.max(0,Math.min(max,Math.round(startWhite)));$('keyboard-window').scrollLeft=startWhite*width;$('keyboard-range').max=max;$('keyboard-range').value=startWhite;const end=Math.min(whites.length-1,startWhite+visible-1);$('octave-label').textContent=`${pitchName(whites[startWhite])} — ${pitchName(whites[end])}`;}
function followCurrent(){const step=displayedStep();if(!step||!step.notes.length)return;const matched=state.playback.state==='playing'?[]:state.practice?.matched||[],remaining=step.notes.filter(n=>!matched.includes(n.midi));if(!remaining.length)return;const midi=remaining[0].midi,visible=Math.max(1,Math.floor(($('keyboard-window').clientWidth||360)/keyWidth()));let index=whites.findIndex(n=>n>=midi);if(index<0)index=whites.length-1;if(index<startWhite+1||index>startWhite+visible-2){startWhite=index-Math.floor(visible/2);positionKeyboard();}}
function renderHints(){document.querySelectorAll('.piano-key.hinted').forEach(n=>n.classList.remove('hinted'));if(!state.hint||!state.practice||!state.practice.step)return;for(const note of state.practice.step.notes){if(!state.practice.matched.includes(note.midi)){const key=document.querySelector(`.piano-key[data-midi="${note.midi}"]`);if(key)key.classList.add('hinted');}}$('feedback-title').textContent='提示：'+state.practice.step.notes.map(n=>n.name).join(' · ');$('feedback-detail').textContent='键盘上的圆点标出了目标位置';}
function playbackParams(command){const bpm=Math.max(30,Math.min(240,Number($('playback-tempo').value)||100));$('playback-tempo').value=bpm;return {command,bpm,hand:state.hand,start:state.practiceStart,end:state.practiceEnd,swap:state.swap};}
function stopPlayback(){post('playback',playbackParams('stop'));state.playback={state:'stopped'};renderPlayback();}
function renderPlaybackKeys(){document.querySelectorAll('.piano-key.playing').forEach(n=>n.classList.remove('playing'));if(state.playback.state!=='playing')return;for(const note of displayedStep()?.notes||[]){document.querySelector(`.piano-key[data-midi="${note.midi}"]`)?.classList.add('playing');}}
function renderPlayback(){const playing=state.playback.state==='playing';$('playback-toggle').replaceChildren(icon(playing?'pause':'play'),element('span','',playing?'暂停示范':state.playback.state==='paused'?'继续示范':'示范播放'));$('playback-toggle').setAttribute('aria-label',playing?'暂停乐谱示范':'播放乐谱示范');$('playback-status').textContent=playing?'正在示范 · 不计入练习':state.playback.state==='paused'?'已暂停':state.playback.state==='complete'?'示范完成':'所选声部与小节';renderPlaybackKeys();if(state.screen==='practice')renderPractice();}
const pressed=new Map();
function playKey(midi,pointer){if(pressed.get(pointer)===midi)return;if(state.playback.state==='playing')stopPlayback();if(pressed.has(pointer))releaseKey(pointer);pressed.set(pointer,midi);const key=document.querySelector(`.piano-key[data-midi="${midi}"]`);if(key)key.classList.add('active');if(window.Android)window.Android.keyDown(midi);else{window.dispatchEvent(new CustomEvent('piano-key',{detail:midi}));if(!window.__UI_TEST__)toast('请在安卓应用中体验钢琴声音与练习判定');}}
function releaseKey(pointer){const midi=pressed.get(pointer);pressed.delete(pointer);if([...pressed.values()].includes(midi))return;document.querySelector(`.piano-key[data-midi="${midi}"]`)?.classList.remove('active');}
$('keyboard-window').addEventListener('pointerdown',e=>{const key=e.target.closest('.piano-key');if(!key)return;e.preventDefault();e.currentTarget.setPointerCapture(e.pointerId);playKey(+key.dataset.midi,e.pointerId);});
$('keyboard-window').addEventListener('pointermove',e=>{if(!pressed.has(e.pointerId))return;const key=document.elementFromPoint(e.clientX,e.clientY)?.closest('.piano-key');if(key)playKey(+key.dataset.midi,e.pointerId);});
for(const type of ['pointerup','pointercancel','lostpointercapture'])$('keyboard-window').addEventListener(type,e=>releaseKey(e.pointerId));
window.addEventListener('blur',()=>[...pressed.keys()].forEach(releaseKey));
$('keyboard-range').addEventListener('input',e=>{startWhite=+e.target.value;positionKeyboard();});
$('keyboard-zoom').addEventListener('input',e=>setKeyWidth(e.target.value));
$('playback-tempo').addEventListener('change',()=>{if(['playing','paused'].includes(state.playback.state))post('playback',playbackParams('tempo'));});
function startEditor(){state.draft=state.score?JSON.parse(JSON.stringify(state.score)):{title:state.current.title||'我的乐谱',composer:'',fifths:0,beats:4,beatType:4,measures:[{number:1,beats:4,beatType:4,duration:4,events:[]}]};state.editorMeasure=0;state.editorNote=null;$('edit-title').value=state.draft.title;renderEditor();}
function collectEditor(){if(!state.draft)return;state.draft.title=$('edit-title').value.trim()||'我的乐谱';}
function renderEditor(){
  const container=$('measure-editor');container.replaceChildren();if(!state.draft)return;
  const measure=state.draft.measures[state.editorMeasure],title=element('div','editor-title');
  title.append(element('strong','','小节音符'));
  const nav=element('div','editor-nav');
  const previous=element('button','editor-step','‹');previous.dataset.action='editorStep';previous.dataset.delta=-1;previous.setAttribute('aria-label','上一小节');previous.disabled=state.editorMeasure<=0;
  const select=element('select');
  for(let i=0;i<state.draft.measures.length;i++){const option=element('option','',`第 ${state.draft.measures[i].number} 小节`);option.value=i;select.append(option);}
  select.value=state.editorMeasure;
  select.addEventListener('change',()=>{state.editorMeasure=+select.value;state.editorNote=null;renderEditor();});
  const next=element('button','editor-step','›');next.dataset.action='editorStep';next.dataset.delta=1;next.setAttribute('aria-label','下一小节');next.disabled=state.editorMeasure>=state.draft.measures.length-1;
  nav.append(previous,select,next);title.append(nav);container.append(title);
  container.append(drawEditorStaff(measure));
  const selected=state.editorNote,event=selected?measure.events[selected.ei]:null,note=event?event.notes[selected.ni]:null;
  if(note){
    const panel=element('div','note-editor');panel.append(element('strong','',`${note.name} · ${note.staff<=1?'上谱表':'下谱表'}`));
    const group=element('div','note-editor-actions'),action=(text,kind,danger)=>{const button=element('button','note-action'+(danger?' danger':''),text);button.dataset.action='editNoteValue';button.dataset.kind=kind;return button;};
    group.append(action('♭ 半音','flat'),action('♯ 半音','sharp'),action('低八度','octaveDown'),action('高八度','octaveUp'),action('换谱表','staff'),action('删除','delete',true));
    panel.append(group);container.append(panel);
  }else container.append(element('p','editor-tip','点一下谱面上的音符，就能改音高、换谱表或删除。'));
  const add=element('button','editor-add','＋ 添加下一个音符 / 和弦');
  add.addEventListener('click',()=>{const onset=measure.events.length?Math.max(...measure.events.map(e=>e.onset))+1:0;measure.events.push({onset,duration:1,notes:[{midi:60,staff:1,name:'C4',duration:1,tiedContinuation:false}]});measure.duration=Math.max(measure.duration||4,onset+1);state.editorNote={ei:measure.events.length-1,ni:0};renderEditor();});
  container.append(add);
}
function drawEditorStaff(measure){
  const hasLower=measure.events.some(event=>event.notes.some(note=>note.staff>1)),staves=hasLower?[1,2]:[1],height=staves.length===2?198:143;
  const svg=svgNode('svg',{viewBox:`0 0 340 ${height}`,class:'editor-staff',role:'img','aria-label':`第${measure.number}小节音符`});
  const maxOnset=Math.max(1,measure.duration||0,...measure.events.map(event=>event.onset+Math.min(event.duration||1,1))),eventX=onset=>64+(onset/maxOnset)*249;
  for(const staff of staves){
    const bottom=staves.length===1?95:staff===1?75:160,items=[];
    measure.events.forEach((event,ei)=>event.notes.forEach((note,ni)=>{if((note.staff<=1?1:2)===staff)items.push({note,ei,ni,onset:event.onset,duration:event.duration});}));
    const mean=items.reduce((sum,item)=>sum+item.note.midi,0)/Math.max(1,items.length),clef=staves.length===1?(mean<57?'bass':'treble'):(staff===1?(mean<48?'bass':'treble'):(mean>76?'treble':'bass'));
    for(let i=0;i<5;i++)svg.append(svgNode('line',{x1:8,y1:bottom-i*9,x2:330,y2:bottom-i*9,stroke:'#d2d7c9','stroke-width':.9}));
    svg.append(svgNode('text',{x:15,y:clef==='treble'?bottom-2:bottom-24,'font-size':clef==='treble'?48:34,'font-family':'Bravura,serif',fill:'#475642'},clef==='treble'?'\uE050':'\uE062'));
    svg.append(svgNode('line',{x1:330,y1:bottom-36,x2:330,y2:bottom,stroke:'#9ca88d','stroke-width':1}));
    for(const item of items){
      const x=eventX(item.onset),pos=notePosition(item.note,clef),y=bottom-pos.offset*4.5,selected=state.editorNote&&state.editorNote.ei===item.ei&&state.editorNote.ni===item.ni;
      if(selected)svg.append(svgNode('rect',{x:x-14,y:y-15,width:28,height:30,rx:9,fill:'#fff0ab'}));
      for(let off=-2;off>=pos.offset;off-=2)svg.append(svgNode('line',{x1:x-9,y1:bottom-off*4.5,x2:x+10,y2:bottom-off*4.5,stroke:'#87957a','stroke-width':1}));
      for(let off=10;off<=pos.offset;off+=2)svg.append(svgNode('line',{x1:x-9,y1:bottom-off*4.5,x2:x+10,y2:bottom-off*4.5,stroke:'#87957a','stroke-width':1}));
      if(pos.accidental)svg.append(svgNode('text',{x:x-18,y:y+4,'font-size':16,fill:selected?'#8e6410':'#87957a'},pos.accidental));
      svg.append(svgNode('ellipse',{'data-action':'pickNote','data-event':item.ei,'data-note':item.ni,class:'editor-note',cx:x,cy:y,rx:5.7,ry:3.9,transform:`rotate(-22 ${x} ${y})`,fill:(item.note.duration||item.duration)>=2?'#fffefa':'#87957a',stroke:selected?'#d49b14':'#87957a','stroke-width':selected?2:1.5}));
      const down=pos.offset>4;svg.append(svgNode('line',{x1:x+(down?-5:5),y1:y,x2:x+(down?-5:5),y2:y+(down?27:-27),stroke:'#87957a','stroke-width':1.3}));
      if(item.note.tiedContinuation)svg.append(svgNode('path',{d:`M${x-15} ${y+8}Q${x-7} ${y+15} ${x+1} ${y+8}`,fill:'none',stroke:'#87957a','stroke-width':1}));
    }
  }
  return svg;
}
function adjustEditorNote(kind){
  const selected=state.editorNote;if(!state.draft||!selected)return;
  const measure=state.draft.measures[state.editorMeasure],event=measure.events[selected.ei];if(!event)return;
  if(kind==='delete'){event.notes.splice(selected.ni,1);if(!event.notes.length)measure.events.splice(selected.ei,1);state.editorNote=null;renderEditor();return;}
  const note=event.notes[selected.ni];if(!note)return;
  if(kind==='flat'||kind==='sharp'||kind==='octaveDown'||kind==='octaveUp'){
    const delta=kind==='sharp'?1:kind==='flat'?-1:kind==='octaveUp'?12:-12,midi=Math.max(21,Math.min(108,note.midi+delta));
    note.midi=midi;note.name=pitchName(midi);
  }else if(kind==='staff')note.staff=note.staff<=1?2:1;
  renderEditor();
}

function renderDetail(){const ready=!!state.score;$('detail-title').textContent=state.current.title||state.score?.title||'乐谱详情';const status=$('detail-status');status.classList.toggle('pending',!ready||!state.current.reviewed);status.textContent=!ready?'乐谱已保存在本机。点击“手机识谱”开始离线识别，也可以手动录入音符。':state.current.reviewed?'音符已校对，可以离线练习。需要时仍可继续修改。':'识别结果需要你核对：请特别检查升降号、和弦、左右手以及延音连结。';if(state.score?.warnings?.length)status.textContent+=' '+state.score.warnings.join(' ');const visual=state.current.mime==='application/pdf'||(state.current.mime||'').startsWith('image/');$('recognize-button').classList.toggle('hidden',!visual);$('detail-practice-button').classList.toggle('hidden',!ready);$('detail').querySelector('[data-action="delete"]').classList.toggle('hidden',state.currentId==='demo');if(!visual)$('source-preview').replaceChildren(element('p','',state.currentId==='demo'?'内置原创练习，可直接查看练习五线谱':'此文件已包含结构化音符，可直接校对练习'));startEditor();}
function showSettings(){closeSheet();showScreen('settings');}
function saveSettings(){state.labels=$('labels-setting').checked;localStorage.setItem('keyLabels',String(state.labels));$('keyboard').dataset.width='';renderKeyboard();closeSheet();toast('偏好已保存');}
function renderPageControls(){const pages=Math.max(1,state.pages),select=$('page-select');if(select.options.length!==pages){select.replaceChildren();for(let i=0;i<pages;i++){const option=element('option','',`第 ${i+1} 页`);option.value=i;select.append(option);}}select.value=state.page;$('page-number').textContent=`共 ${pages} 页`;$('page-controls').classList.toggle('hidden',pages<2);document.querySelector('[data-action="pagePrevious"]').disabled=state.page<=0;document.querySelector('[data-action="pageNext"]').disabled=state.page>=pages-1;}
function requestPreview(page){page=Math.max(0,Math.min(Math.max(0,state.pages-1),Number(page)||0));state.page=page;state.previewRequest++;state.previewLoading=true;$('source-preview').replaceChildren(element('p','',`正在准备第 ${page+1} 页…`));$('source-preview').scrollTop=0;$('source-preview').scrollLeft=0;renderPageControls();post('preview',{id:state.currentId,page,requestId:state.previewRequest});}
function resetDocument(){$('detail').querySelector('.detail-scroll').scrollTop=0;state.page=0;state.pages=Math.max(1,state.current.pages||state.current.pageCount||1);state.previewRequest++;state.previewLoading=false;state.practice=null;state.practiceStart=0;state.practiceEnd=state.score?Math.max(0,state.score.measures.length-1):0;state.playback={state:'stopped'};$('source-preview').classList.remove('zoomed');$('source-preview').scrollTop=0;$('source-preview').scrollLeft=0;$('source-preview').replaceChildren(element('p','','正在准备原谱预览…'));renderPageControls();window.dispatchEvent(new CustomEvent('document-reset'));}
window.receive=function(envelope){
  const data=envelope.data||{};
  switch(envelope.type){
    case'boot':{const changed=state.currentId!==data.currentId;state.library=data.library||[];state.history=data.history||[];state.currentId=data.currentId;state.current=data.current||{};state.score=data.score;state.job=data.job||state.job;if(changed)resetDocument();renderLibrary();if(state.screen==='detail')renderDetail();break;}
    case'state':receiveState(data);break;
    case'opened':resetDocument();if(data.ready&&(state.currentId==='demo'||state.current.reviewed))setup();else{showScreen('detail');renderDetail();}if(state.current.mime==='application/pdf'||(state.current.mime||'').startsWith('image/'))post('preview',{id:state.currentId,page:0,requestId:state.previewRequest});break;
    case'preview':{
      if(data.id!==undefined&&String(data.id)!==String(state.currentId))return;
      if(data.requestId!==undefined&&Number(data.requestId)!==state.previewRequest)return;
      if(data.requestId===undefined&&state.previewLoading&&Number(data.page)!==state.page)return;
      state.page=Number(data.page)||0;state.pages=Math.max(1,Number(data.pages)||1);state.previewLoading=false;const img=element('img');img.src=data.src;img.alt='导入的原始乐谱，第'+(state.page+1)+'页';$('source-preview').replaceChildren(img);$('source-preview').scrollTop=0;$('source-preview').scrollLeft=0;renderPageControls();break;
    }
    case'playbackState':state.playback={...state.playback,...data};if(data.bpm)$('playback-tempo').value=Math.round(data.bpm);renderPlayback();break;
    case'recognized':closeSheet();if(!data.id||data.id===state.currentId){showScreen('detail');renderDetail();}toast('识别完成，请先校对音符，再开始练习');break;
    case'busy':$('busy-overlay').classList.toggle('hidden',!data.active);$('busy-text').textContent=data.text||'正在处理…';break;
    case'message':case'error':toast(data.text);break;
    case'deleted':closeSheet();showScreen('home');break;
  }
};
document.addEventListener('click',event=>{
  const remove=event.target.closest('[data-delete-score]');if(remove){post('deleteScore',{id:remove.dataset.deleteScore});return;}
  const open=event.target.closest('[data-open]');if(open){stopPlayback();post('open',{id:open.dataset.open});return;}
  const screen=event.target.closest('[data-screen]');if(screen){closeSheet();showScreen(screen.dataset.screen);return;}
  const button=event.target.closest('[data-action]');if(!button)return;
  try{switch(button.dataset.action){
    case'import':post('import');break;case'settings':showSettings();break;case'closeSheet':closeSheet();break;case'continue':if(state.score)setup();else post('open',{id:'demo'});break;case'setup':setup();break;
    case'leavePractice':stopPlayback();post('finishSession');showScreen('home');break;case'previous':stopPlayback();post('previous');break;case'restart':stopPlayback();post('restart');break;
    case'focusPractice':{const page=$('practice'),button=$('focus-toggle'),on=!page.classList.contains('focus');page.classList.toggle('focus',on);button.setAttribute('aria-pressed',String(on));button.textContent=on?'退出专注':'全屏练习';requestAnimationFrame(()=>renderKeyboard());break;}
    case'hint':state.hint=!state.hint;$('hint-button').classList.toggle('on',state.hint);renderPractice();if(!state.hint){$('feedback-title').textContent='找到黄色音符的位置';$('feedback-detail').textContent='轻点下方琴键，弹对后自动继续';}break;
    case'follow':state.follow=!state.follow;$('follow-button').classList.toggle('on',state.follow);if(state.follow)followCurrent();break;
    case'wide':setKeyWidth(keyWidth()>=53?43:53);break;case'zoomOut':setKeyWidth(keyWidth()-5);break;case'zoomIn':setKeyWidth(keyWidth()+5);break;
    case'octaveDown':startWhite-=7;positionKeyboard();break;case'octaveUp':startWhite+=7;positionKeyboard();break;
    case'source':{const page=state.score?.measures[displayedStep()?.measureIndex||0]?.sourcePage;stopPlayback();showScreen('detail');renderDetail();$('detail').querySelector('.detail-scroll').scrollTop=0;requestPreview(page>=0?page:state.page);break;}
    case'pagePrevious':if(state.page>0)requestPreview(state.page-1);break;case'pageNext':if(state.page<state.pages-1)requestPreview(state.page+1);break;
    case'playbackToggle':post('playback',playbackParams(state.playback.state==='playing'?'pause':'play'));break;case'playbackStop':stopPlayback();break;
    case'cancelRecognition':$('busy-overlay').classList.add('hidden');break;case'saveSettings':saveSettings();break;
    case'saveEdit':collectEditor();if(!state.draft.measures.some(m=>m.events.some(e=>e.notes.length))){toast('请至少添加一个音符');return;}post('saveScore',{score:state.draft});break;
    case'addMeasure':collectEditor();state.draft.measures.push({number:(state.draft.measures.at(-1)?.number||0)+1,beats:4,beatType:4,duration:4,events:[]});state.editorMeasure=state.draft.measures.length-1;renderEditor();break;
    case'pickNote':state.editorNote={ei:+button.dataset.event,ni:+button.dataset.note};renderEditor();break;
    case'editNoteValue':adjustEditorNote(button.dataset.kind);break;
    case'editorStep':{const next=state.editorMeasure+(+button.dataset.delta||0);if(!state.draft||next<0||next>=state.draft.measures.length)break;state.editorMeasure=next;state.editorNote=null;renderEditor();break;}
    case'delete':if(state.currentId!=='demo')post('deleteScore',{id:state.currentId});break;case'again':closeSheet();post('restart');break;case'done':closeSheet();post('finishSession');showScreen('home');break;
  }}catch(error){toast(error.message);}
});
for(const[id,key]of[['hand-options','hand'],['mode-options','mode']])$(id).addEventListener('click',event=>{const button=event.target.closest('[data-value]');if(!button)return;state[key]=button.dataset.value;selectOption(id,state[key]);});
$('page-select').addEventListener('change',event=>requestPreview(+event.target.value));
$('demo-start').addEventListener('click',()=>post('open',{id:'demo'}));$('start-practice').addEventListener('click',startPractice);$('modal-backdrop').addEventListener('click',closeSheet);
window.appBack=function(){if(!$('busy-overlay').classList.contains('hidden')){$('busy-overlay').classList.add('hidden');return;}if(!$('modal-backdrop').classList.contains('hidden')){closeSheet();return;}if(state.screen==='practice'){stopPlayback();post('finishSession');}if(state.screen!=='home')showScreen('home');else post('finishApp');};
window.addEventListener('resize',()=>{if(state.screen==='practice'){positionKeyboard();renderStaff();}});
(function splash(){const node=$('splash');if(!node)return;let closed=false;const close=()=>{if(closed)return;closed=true;node.classList.add('gone');setTimeout(()=>node.remove(),320);};node.addEventListener('click',close);node.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' ')close();});setTimeout(close,3600);})();
renderLibrary();renderKeyboard();post('boot');
