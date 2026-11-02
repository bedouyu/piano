// Browser UI contract checks use a TEST ONLY Android bridge. Music semantics are
// separately verified against the production Java engine in tests/music/.
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const assert = require('node:assert/strict');
const modules = process.env.CODEX_NODE_MODULES || 'C:/Users/10575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const { chromium } = require(path.join(modules, 'playwright'));
const root = path.resolve(__dirname, '../..');
const output = path.join(root, 'build', 'verification');
fs.mkdirSync(output, { recursive: true });

function installTestBridge() {
  window.__UI_TEST__ = true;
  const notes = [60,64,67,64];
  const names = ['C4','E4','G4','E4'];
  const score={title:'初见 · 八小节练习',composer:'识谱助手 · 原创练习',fifths:0,beats:4,beatType:4,warnings:[],measures:[]};
  for(let m=0;m<8;m++) score.measures.push({number:m+1,beats:4,beatType:4,duration:4,events:notes.map((n,i)=>({onset:i,duration:1,notes:[{midi:n,staff:1,name:names[i],duration:1,tiedContinuation:false},...(i===0?[{midi:48,staff:2,name:'C3',duration:4,tiedContinuation:false}]:[])]}))});
  let currentId='demo', current={id:'demo',title:score.title,reviewed:true}, activeScore=score;
  let steps=[],index=0,matched=[],correct=0,wrong=0,loop=false,history=[];
  window.__nativeCalls=[];
  const send=(type,data)=>queueMicrotask(()=>window.receive({type,data}));
  function boot(){send('boot',{library:[],history,currentId,current,score:activeScore,appVersion:'0.3.0',job:{}});}
  function update(result,played=-1){send('state',{result,played,index,total:steps.length,finished:index>=steps.length,matched:[...matched],correct,wrong,accuracy:correct+wrong?correct/(correct+wrong):1,loops:0,step:steps[index]||null});}
  window.Android={postMessage(raw){const a=JSON.parse(raw);window.__nativeCalls.push(a);switch(a.action){case'boot':boot();break;case'open':currentId=a.id;current={id:a.id,title:score.title,reviewed:true};activeScore=score;boot();send('opened',{ready:true});break;case'configure':{steps=[];index=0;matched=[];correct=wrong=0;loop=a.loop;for(let mi=a.start;mi<=a.end;mi++){activeScore.measures[mi].events.forEach((event,ei)=>{const selected=event.notes.filter(n=>a.hand==='BOTH'||(a.hand==='RIGHT'?n.staff===1:n.staff===2));if(a.mode==='NOTE'){selected.forEach(n=>steps.push({measureIndex:mi,measureNumber:mi+1,eventIndex:ei,noteIndex:event.notes.indexOf(n),onset:event.onset,notes:[n]}));}else if(selected.length)steps.push({measureIndex:mi,measureNumber:mi+1,eventIndex:ei,noteIndex:-1,onset:event.onset,notes:selected});});}update('START');break;}case'previous':index=Math.max(0,index-1);matched=[];update('PREVIOUS');break;case'restart':index=correct=wrong=0;matched=[];update('START');break;case'finishSession':if(correct+wrong)history=[{title:score.title,time:Date.now(),correct,wrong,accuracy:correct/(correct+wrong),seconds:30}];boot();break;case'saveScore':activeScore=a.score;current.reviewed=true;boot();send('message',{text:'校对已保存，可以开始练习'});break;case'playback':send('playbackState',{state:a.command==='play'?'playing':a.command==='pause'?'paused':'stopped',measureIndex:0,eventIndex:0,bpm:a.bpm});break;}},keyDown(midi){if(!steps[index])return;const expected=steps[index].notes.map(n=>n.midi);if(!expected.includes(midi)){wrong++;update('WRONG',midi);return;}if(matched.includes(midi))return;correct++;matched.push(midi);if(expected.every(n=>matched.includes(n))){index++;matched=[];if(loop&&index>=steps.length)index=0;update(index>=steps.length?'COMPLETED':'ADVANCED',midi);}else update('PARTIAL',midi);},audition(){}};
}

(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--no-first-run','--disable-extensions']});
  try {
  const context=await browser.newContext({viewport:{width:393,height:852},deviceScaleFactor:1,isMobile:true,hasTouch:true});
  await context.addInitScript(installTestBridge);
  const page=await context.newPage();const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  const assertPracticeControlsFit=async()=>{
    const boxes=await page.evaluate(()=>{
      const rect=id=>{const b=document.getElementById(id).getBoundingClientRect();return {top:b.top,bottom:b.bottom,height:b.height};};
      const playback=document.querySelector('.playback-bar').getBoundingClientRect(),tools=document.querySelector('.practice-tools').getBoundingClientRect();
      return {playback:{top:playback.top,bottom:playback.bottom},toolsTop:tools.top,staff:rect('staff'),keys:rect('keyboard-window'),view:innerHeight,controls:['playback-toggle','playback-tempo','keyboard-zoom','keyboard-range'].map(id=>({id,...rect(id)}))};
    });
    assert.ok(boxes.playback.top>=0&&boxes.playback.bottom<=boxes.toolsTop+1,'portrait playback bar must fit above practice tools without scrolling');
    assert.ok(boxes.staff.height>=45,'portrait notation retains readable space');
    for(const control of boxes.controls)assert.ok(control.top>=0&&control.bottom<=boxes.view+1,control.id+' stays on screen');
  };

  await page.goto(pathToFileURL(path.join(root,'app/src/main/assets/index.html')).href);
  await page.locator('.score-card').first().waitFor();
  await page.screenshot({path:path.join(output,'01-乐谱首页.png')});
  assert.equal(await page.locator('#home').evaluate(el=>el.scrollWidth<=el.clientWidth),true,'home horizontal fit');
  await page.locator('#demo-start').click();
  await page.locator('#setup-sheet:not(.hidden)').waitFor();
  await page.locator('#mode-options [data-value="MEASURE"]').click();
  await page.locator('#end-measure').selectOption('0');
  await page.screenshot({path:path.join(output,'02-练习设置.png')});
  await page.locator('#start-practice').click();
  await page.locator('#practice.active').waitFor();
  await page.waitForTimeout(100);
  await assertPracticeControlsFit();
  await page.screenshot({path:path.join(output,'03-竖屏练习.png')});
  const keys=await page.locator('.piano-key.white').count();assert.equal(keys,52);
  const lastConfig=await page.evaluate(()=>window.__nativeCalls.filter(c=>c.action==='configure').at(-1));
  assert.deepEqual([lastConfig.hand,lastConfig.mode,lastConfig.start,lastConfig.end],['RIGHT','MEASURE',0,0]);
  const clickPitch=async midi=>{const key=page.locator(`.piano-key[data-midi="${midi}"]`);await key.click({position:{x:21,y:Math.max(10,(await key.boundingBox()).height-16)}});await page.waitForTimeout(80);};
  await clickPitch(62);
  assert.match(await page.locator('#feedback-title').innerText(),/再看/);
  assert.equal(await page.locator('#step-label').innerText(),'1 / 4');
  await clickPitch(60);assert.equal(await page.locator('#step-label').innerText(),'2 / 4');
  await page.locator('#hint-button').click();assert.match(await page.locator('#feedback-title').innerText(),/E4/);
  await page.locator('#hint-button').click();assert.doesNotMatch(await page.locator('#feedback-title').innerText(),/提示/);
  await clickPitch(64);await clickPitch(67);await clickPitch(64);
  await page.locator('#complete-sheet:not(.hidden)').waitFor();
  assert.equal(await page.locator('#result-accuracy').innerText(),'80%');
  await page.screenshot({path:path.join(output,'04-练习完成.png')});
  await page.locator('[data-action="done"]').click();
  await page.locator('#home [data-screen="history"]').click();
  assert.equal(await page.locator('#total-sessions').innerText(),'1');
  await page.screenshot({path:path.join(output,'05-练习记录.png')});
  await page.locator('#history [data-screen="home"]').click();
  await page.locator('#demo-start').click();
  await page.locator('#hand-options [data-value="BOTH"]').click();
  await page.locator('#mode-options [data-value="MEASURE"]').click();
  await page.locator('#end-measure').selectOption('0');
  await page.locator('#start-practice').click();
  // Deliver partial-chord state via the bridge to test one-handed feedback.
  await page.evaluate(()=>Android.keyDown(48));
  await page.waitForTimeout(50);
  assert.match(await page.locator('#feedback-title').innerText(),/补齐和弦/);
  await page.evaluate(()=>Android.keyDown(61));
  assert.equal(await page.locator('#step-label').innerText(),'1 / 4');
  await page.setViewportSize({width:360,height:640});
  await assertPracticeControlsFit();
  await page.screenshot({path:path.join(output,'06-小屏单手练习.png')});
  const geometry=await page.evaluate(()=>{const b=document.getElementById('keyboard-window').getBoundingClientRect();return {bottom:b.bottom,width:document.body.scrollWidth,view:innerWidth,keys:getComputedStyle(document.querySelector('.piano-key.white')).width};});
  assert.ok(geometry.bottom<=640);assert.ok(geometry.width<=360);assert.equal(geometry.keys,'43px');
  await page.setViewportSize({width:393,height:852});
  await page.locator('[data-action="source"]').click();
  await page.locator('#detail.active').waitFor();
  assert.equal(await page.locator('#recognize-button').isVisible(),false);
  await page.screenshot({path:path.join(output,'07-音符校对.png')});
  await page.locator('[data-action="setup"]').last().click();
  await page.locator('#mode-options [data-value="NOTE"]').click();
  await page.locator('#start-practice').click();
  assert.equal(await page.locator('#staff ellipse').count(),1,'NOTE displays one current note');
  await page.locator('#playback-toggle').click();
  await page.waitForFunction(()=>document.getElementById('playback-status').textContent.includes('正在示范'));
  const playRequest=await page.evaluate(()=>__nativeCalls.filter(c=>c.action==='playback'&&c.command==='play').at(-1));
  assert.equal(playRequest.hand,'BOTH');assert.equal(playRequest.start,0);assert.equal(playRequest.end,0);
  await page.locator('#playback-toggle').click();
  await page.waitForFunction(()=>document.getElementById('playback-status').textContent.includes('已暂停'));
  await page.locator('[data-action="playbackStop"]').click();
  await page.locator('#keyboard-zoom').evaluate(el=>{el.value='18';el.dispatchEvent(new Event('input',{bubbles:true}));});
  assert.equal(await page.locator('.piano-key.white').first().evaluate(el=>getComputedStyle(el).width),'18px');
  await page.setViewportSize({width:852,height:393});
  await page.waitForTimeout(100);
  const landscape=await page.locator('#keyboard-window').evaluate(el=>({width:el.clientWidth,bottom:el.getBoundingClientRect().bottom}));
  assert.ok(landscape.width>=840,'landscape keyboard uses full screen width');
  assert.ok(landscape.bottom<=393,'landscape keys remain on screen');
  await page.screenshot({path:path.join(output,'08-横屏缩放键盘.png')});
  await page.setViewportSize({width:393,height:852});
  assert.equal(await page.locator('#endpoint-setting').count(),0);
  assert.equal(await page.locator('#token-setting').count(),0);
  assert.equal(errors.length,0,errors.join('\n'));
  fs.writeFileSync(path.join(output,'ui-report.json'),JSON.stringify({passed:true,browser:'Microsoft Edge (headless)',viewports:['393x852','360x640','852x393'],checks:['home/library','practice configuration bridge','52 white keys','wrong-note waits','correct-note advances','hint toggle','completion accuracy','practice history','one-hand chord feedback','portrait small-screen fit','playback controls fit without scrolling at both portrait sizes','MusicXML source affordances','single current note','play/pause/stop bridge','18px key zoom','landscape wide keyboard','remote settings removed','no runtime JS errors'],nativeBridge:'TEST ONLY fixture; production Java tested separately',screenshots:8},null,2));
  console.log('PASS: UI flows, portrait and landscape layout; 8 screenshots saved.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
