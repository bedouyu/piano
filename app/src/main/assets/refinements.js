'use strict';
// Source zoom is independent of musical practice and resets on each document.
const previewTools=element('div','preview-tools');
previewTools.append(element('span','','放大后可拖动查看原谱'));
const sourceZoom=element('button','','放大原谱');sourceZoom.id='source-zoom';
sourceZoom.addEventListener('click',()=>{$('source-preview').classList.toggle('zoomed');sourceZoom.textContent=$('source-preview').classList.contains('zoomed')?'适应屏幕':'放大原谱';});
previewTools.append(sourceZoom);$('source-preview').parentNode.insertBefore(previewTools,$('source-preview'));
window.addEventListener('document-reset',()=>{sourceZoom.textContent='放大原谱';});
const originalRenderDetail=renderDetail;
renderDetail=function(){originalRenderDetail();previewTools.classList.toggle('hidden',!(state.current.mime==='application/pdf'||(state.current.mime||'').startsWith('image/')));};
const practiceTools=document.querySelector('.practice-tools');$('practice').insertBefore(practiceTools,document.querySelector('.piano-dock'));
