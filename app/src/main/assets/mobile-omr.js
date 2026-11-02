'use strict';
// The foreground Android service owns recognition. This view only displays its status.
(function(){
  const originalReceive=window.receive,originalRender=renderDetail;
  const node=(tag,className,text)=>element(tag,className,text);
  const isVisual=()=>state.current.mime==='application/pdf'||(state.current.mime||'').startsWith('image/');
  let webViewVersion='',appVersion='0.3.0';
  const card=node('section','mobile-omr-card hidden');card.id='mobile-omr-card';
  const heading=node('div','mobile-omr-heading');heading.append(icon('note'),node('strong','','手机离线识谱'),node('span','mobile-offline-tag','完全离线'));
  const intro=node('p','mobile-omr-intro','图片与 PDF 在本机逐页处理。开始后可返回乐谱列表，或切换到其他应用，通知中会显示进度。');
  const status=node('p','mobile-omr-status');status.id='mobile-omr-status';status.setAttribute('aria-live','polite');
  const track=node('div','mobile-omr-progress-track');track.id='mobile-omr-progress-track';track.setAttribute('role','progressbar');track.setAttribute('aria-label','手机识谱进度');track.setAttribute('aria-valuemin','0');track.setAttribute('aria-valuemax','100');
  const progress=node('div','mobile-omr-progress');progress.id='mobile-omr-progress';track.append(progress);
  const controls=node('div','mobile-omr-actions');
  const retry=node('button','button secondary full hidden','重新尝试手机识谱');retry.id='mobile-omr-retry';retry.dataset.mobileOmrAction='recognize';
  const cancel=node('button','button secondary hidden','取消识别');cancel.id='mobile-omr-cancel';cancel.dataset.mobileOmrAction='cancel';controls.append(retry,cancel);
  const errors=node('details','mobile-omr-diagnostic-details hidden');errors.id='mobile-omr-error-details';
  const inline=node('pre','mobile-omr-diagnostics');inline.id='mobile-omr-inline-diagnostics';
  const errorCopy=node('button','button secondary full','复制错误信息');errorCopy.dataset.mobileOmrAction='copy';errors.append(node('summary','','查看错误详情'),inline,errorCopy);
  card.append(heading,intro,status,track,controls,errors);$('detail-status').after(card);
  const banner=node('section','recognition-banner hidden');banner.id='recognition-banner';banner.setAttribute('aria-live','polite');
  const bannerText=node('div');bannerText.append(node('strong','','正在后台识谱'),node('p'));const open=node('button','','查看');open.dataset.mobileOmrAction='openJob';banner.append(icon('note'),bannerText,open);$('home').querySelector('.page-scroll').prepend(banner);
  const settingsContent=$('settings-sheet').querySelector('.sheet-content'),settings=node('section','mobile-omr-settings');
  settings.append(node('h3','','手机离线识谱'),node('p','','模型随安装包提供，不上传乐谱，也不需要连接电脑。多页 PDF 按原始页码逐页识别。'),node('p','','识别期间可切换应用或锁屏，进度会显示在手机通知中。若主动强制停止应用或系统结束任务，再次识别会接着已完成的页面处理。'));
  const version=node('p','mobile-webview-status');version.id='mobile-webview-status';
  const details=node('details','mobile-omr-diagnostic-details');details.id='mobile-omr-diagnostic-details';
  const diagnostic=node('pre','mobile-omr-diagnostics');diagnostic.id='mobile-omr-diagnostics';const copy=node('button','button secondary full','复制运行信息');copy.dataset.mobileOmrAction='copy';
  details.append(node('summary','','运行信息（排查问题时使用）'),copy,diagnostic);settings.append(version,details);settingsContent.insertBefore(settings,settingsContent.querySelector('.about'));
  function jobForCurrent(){return state.job&&String(state.job.id)===String(state.currentId)?state.job:null;}
  function running(job){return job&&(job.state==='running'||job.state==='starting');}
  function diagnosticText(){let native=null;try{if(window.Android&&typeof Android.localOmrDiagnostics==='function')native=JSON.parse(Android.localOmrDiagnostics());}catch(_){}return JSON.stringify({app:'识谱助手 '+appVersion,transport:'native-service',systemWebView:webViewVersion||'未知',userAgent:navigator.userAgent,job:state.job?{id:state.job.id,runId:state.job.runId,state:state.job.state,message:state.job.message,page:state.job.page,pages:state.job.pages,error:state.job.error||''}:null,native},null,2);}
  function refresh(){
    const visual=isVisual(),job=jobForCurrent(),active=running(job),anyActive=running(state.job);card.classList.toggle('hidden',!visual);card.dataset.status=active?'working':job?.state==='error'?'error':job?.state==='complete'?'success':'idle';
    status.textContent=anyActive&&!active?'另一份乐谱正在后台识别，可返回乐谱列表或在通知中查看进度。':job?.message||(job?.state==='complete'?'识别完成，请校对音符。':'准备就绪。开始识别后可切换到其他应用。');
    if(active&&job.pages)status.textContent+=' · 第 '+Math.max(1,Math.min(job.pages,Number(job.page)||1))+' / '+job.pages+' 页';
    const percent=Math.max(0,Math.min(100,Number(job?.percent)||0));progress.style.width=percent+'%';track.setAttribute('aria-valuenow',String(Math.round(percent)));track.classList.toggle('hidden',!active);
    retry.classList.toggle('hidden',job?.state!=='error'&&job?.state!=='canceled');cancel.classList.toggle('hidden',!active);errors.classList.toggle('hidden',!job?.error);inline.textContent=job?.error||'';
    $('recognize-button').disabled=anyActive;$('recognize-button').textContent=active?'正在后台识谱…':anyActive?'等待当前识别完成':'手机识谱';$('recognize-button').classList.toggle('hidden',!visual);
    banner.classList.toggle('hidden',!anyActive);bannerText.querySelector('p').textContent=(state.job?.title||'乐谱')+' · '+(state.job?.message||'正在本机处理');
    version.textContent='后台识谱服务 · 系统网页组件 '+(webViewVersion||'未知');diagnostic.textContent=diagnosticText();
  }
  function requestStart(){if(!isVisual())return;if(running(state.job)){toast('已有乐谱正在识别，可在通知中查看进度');return;}post('localRecognize',{id:state.currentId});}
  function copyDiagnostics(){const text=diagnosticText();diagnostic.textContent=text;if(window.Android){post('copyLocalDiagnostics',{text});return;}if(navigator.clipboard?.writeText)navigator.clipboard.writeText(text).then(()=>toast('运行信息已复制')).catch(()=>toast('请展开运行信息后长按复制'));else{details.open=true;toast('请长按运行信息复制');}}
  renderDetail=function(){originalRender();refresh();};
  window.receive=function(envelope){
    const data=envelope.data||{};
    if(envelope.type==='recognitionStatus'){
      // Only the service can choose the active job; document navigation never cancels it.
      state.job=data;refresh();return;
    }
    if(envelope.type==='boot'){webViewVersion=data.webViewVersion||webViewVersion;appVersion=data.appVersion||appVersion;if(Object.prototype.hasOwnProperty.call(data,'job'))state.job=data.job;}
    originalReceive(envelope);if(['boot','opened','recognized','deleted'].includes(envelope.type))refresh();
  };
  document.addEventListener('click',event=>{
    const button=event.target.closest('[data-mobile-omr-action], [data-action="localRecognize"]');if(!button)return;event.preventDefault();event.stopImmediatePropagation();
    switch(button.dataset.mobileOmrAction||'recognize'){
      case'recognize':requestStart();break;
      case'cancel':if(running(state.job))post('localRecognitionCancel',{id:state.job.id,runId:state.job.runId});break;
      case'openJob':if(state.job?.id)post('open',{id:state.job.id});break;
      case'copy':copyDiagnostics();break;
    }
  },true);
  refresh();
})();
