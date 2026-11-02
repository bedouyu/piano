'use strict';
// 用最小 DOM 桩验证“点选音符后修改”的编辑逻辑，无需浏览器或外部依赖。
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/app.js'), 'utf8');
function productionFunction(name) {
  const start = source.indexOf('function ' + name + '(');
  if (start < 0) throw new Error('Missing production function: ' + name);
  const end = source.indexOf('\nfunction ', start + 1);
  return source.slice(start, end < 0 ? undefined : end);
}
class FakeElement {
  constructor(tag, className, text) {
    this.tag = tag; this.className = className || ''; this.textContent = text || '';
    this.children = []; this.dataset = {}; this.value = '';
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute() {}
  addEventListener() {}
}
const elements = { 'edit-title': new FakeElement('input') };
elements['edit-title'].value = '校对测试';
const context = {
  state: { draft: null, editorMeasure: 0, editorNote: null },
  $: id => elements[id],
  document: { querySelectorAll: () => [] },
  element: (tag, className, text) => new FakeElement(tag, className, text),
  icon: () => new FakeElement('svg'),
  toast: message => { throw new Error(message); },
  renderEditor: () => {}
};
vm.createContext(context);
vm.runInContext([
  productionFunction('pitchName'),
  productionFunction('collectEditor'),
  productionFunction('adjustEditorNote')
].join('\n'), context);
function setScore(notes) {
  context.state.draft = { title: '校对测试', measures: [{ number: 1, duration: 4, events: [{ onset: 0, duration: 4, notes }] }] };
  context.state.editorMeasure = 0;
  context.state.editorNote = null;
}
function notes() { return context.state.draft.measures[0].events[0].notes; }
function pick(ei, ni) { context.state.editorNote = { ei, ni }; }
function edit(kind) { context.adjustEditorNote(kind); }
function plain(value) { return JSON.parse(JSON.stringify(value)); }

function original() {
  return [
    { midi: 60, staff: 1, name: 'C4', duration: 4, tiedContinuation: true },
    { midi: 64, staff: 1, name: 'E4', duration: 1, tiedContinuation: false },
    { midi: 48, staff: 2, name: 'C3', duration: 4, tiedContinuation: false }
  ];
}

setScore(original());
pick(0, 0); edit('sharp');
assert.strictEqual(notes()[0].midi, 61);
assert.strictEqual(notes()[0].name, 'C♯4');
assert.strictEqual(notes()[0].tiedContinuation, true, '改音高不影响延音连结标记');
assert.strictEqual(notes()[1].midi, 64, '相邻音符不受影响');
edit('flat'); edit('flat');
assert.strictEqual(notes()[0].midi, 59);

setScore(original());
pick(0, 0); edit('octaveUp');
assert.strictEqual(notes()[0].midi, 72);
edit('octaveDown'); edit('octaveDown');
assert.strictEqual(notes()[0].midi, 48);

setScore([{ midi: 107, staff: 1, name: 'B7', duration: 1, tiedContinuation: false }]);
pick(0, 0); edit('sharp');
assert.strictEqual(notes()[0].midi, 108, '音高上限截断在 C8');
edit('octaveUp');
assert.strictEqual(notes()[0].midi, 108);
setScore([{ midi: 21, staff: 1, name: 'A0', duration: 1, tiedContinuation: false }]);
pick(0, 0); edit('flat');
assert.strictEqual(notes()[0].midi, 21);

setScore(original());
pick(0, 2); edit('staff');
assert.strictEqual(notes()[2].staff, 1);
assert.strictEqual(notes()[2].duration, 4, '换谱表保留时值');
edit('staff');
assert.strictEqual(notes()[2].staff, 2);

setScore(original());
pick(0, 1); edit('delete');
assert.deepStrictEqual(plain(notes()), [original()[0], original()[2]], '只删除被点选的音符');
assert.strictEqual(context.state.editorNote, null, '删除后取消选中');
setScore(original());
pick(0, 0); edit('delete');
pick(0, 0); edit('delete');
pick(0, 0); edit('delete');
assert.strictEqual(context.state.draft.measures[0].events.length, 0, '事件内音符删光后移除该事件');

setScore(original());
elements['edit-title'].value = '  我的练习  ';
context.collectEditor();
assert.strictEqual(context.state.draft.title, '我的练习', '保存时同步标题并去除空白');
elements['edit-title'].value = '';
context.collectEditor();
assert.strictEqual(context.state.draft.title, '我的乐谱', '空标题回退到默认名称');

console.log('PASS: 点选音符可改半音、八度、谱表、删除，标题同步保持可用');
