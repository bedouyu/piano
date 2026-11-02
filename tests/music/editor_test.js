'use strict';
// Exercise the production editor functions without a browser or external packages.
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
    this.children = []; this.dataset = {}; this.style = {}; this.listeners = {}; this.value = '';
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute() {}
  addEventListener(name, listener) { this.listeners[name] = listener; }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  querySelectorAll(selector) {
    const output = [];
    for (const child of this.children) {
      if (selector.startsWith('.') ? child.className.split(' ').includes(selector.slice(1)) : child.tag === selector) output.push(child);
      output.push(...child.querySelectorAll(selector));
    }
    return output;
  }
}
const elements = { 'measure-editor': new FakeElement('div'), 'edit-title': new FakeElement('input') };
elements['edit-title'].value = '校对测试';
const context = {
  state: { draft: null, editorMeasure: 0 },
  $: id => elements[id],
  document: { querySelectorAll: selector => elements['measure-editor'].querySelectorAll(selector) },
  element: (tag, className, text) => new FakeElement(tag, className, text),
  icon: () => new FakeElement('svg'),
  toast: message => { throw new Error(message); }
};
vm.createContext(context);
vm.runInContext(productionFunction('parsePitch') + '\n' + productionFunction('collectEditor') + '\n' + productionFunction('renderEditor'), context);
function setScore(notes) {
  context.state.draft = { title: '校对测试', measures: [{ number: 1, duration: 4, events: [{ onset: 0, duration: 4, notes }] }] };
  context.state.editorMeasure = 0;
  context.renderEditor();
}
function rows() { return elements['measure-editor'].querySelectorAll('.editor-row'); }
function notes() { return context.state.draft.measures[0].events[0].notes; }
function plain(value) { return JSON.parse(JSON.stringify(value)); }
function original() {
  return [
    { midi: 60, staff: 1, name: 'C4', duration: 4, tiedContinuation: true },
    { midi: 64, staff: 1, name: 'E4', duration: 1, tiedContinuation: false },
    { midi: 48, staff: 2, name: 'C3', duration: 4, tiedContinuation: false }
  ];
}

setScore(original());
assert.strictEqual(+rows()[0].dataset.sourceStaff, 1);
context.collectEditor();
assert.deepStrictEqual(plain(notes()), original(), 'unchanged save must preserve per-note mixed tie and durations');
context.collectEditor();
assert.deepStrictEqual(plain(notes()), original(), 'repeated collection must not lose metadata');

setScore(original());
rows()[0].querySelector('input').value = 'E4 C4';
context.collectEditor();
assert.deepStrictEqual(plain(notes().slice(0, 2)), [original()[1], original()[0]], 'metadata follows the pitch when reordered');

setScore(original());
rows()[0].querySelector('input').value = 'D4 E4';
context.collectEditor();
assert.strictEqual(notes()[0].midi, 62);
assert.strictEqual(notes()[0].tiedContinuation, false, 'edited pitch is a new attack');
assert.strictEqual(notes()[1].duration, 1, 'unchanged neighboring pitch keeps own duration');

setScore(original());
rows()[0].querySelector('select').value = 2;
context.collectEditor();
assert.strictEqual(notes()[0].staff, 2);
assert.strictEqual(notes()[0].duration, 4);
assert.strictEqual(notes()[0].tiedContinuation, true, 'staff correction preserves musical metadata');

setScore(original());
rows()[0].querySelector('select').value = 2;
rows()[0].querySelector('button').listeners.click();
assert.deepStrictEqual(plain(notes()), [original()[2]], 'deleting moved row must retain the other row now sharing its staff');

setScore([{ midi: 67, staff: 1, name: 'A𝄫4', duration: 2, tiedContinuation: true }]);
context.collectEditor();
assert.strictEqual(notes()[0].midi, 67, 'imported double-flat spelling remains editable');
assert.strictEqual(notes()[0].duration, 2);
assert.strictEqual(notes()[0].tiedContinuation, true);

setScore([
  { midi: 60, staff: 1, name: 'C4', duration: 4, tiedContinuation: true },
  { midi: 60, staff: 1, name: 'C4', duration: 1, tiedContinuation: false }
]);
context.collectEditor();
assert.strictEqual(notes()[0].tiedContinuation, true);
assert.strictEqual(notes()[1].tiedContinuation, false, 'duplicate pitches do not reuse the first note metadata');
assert.strictEqual(notes()[1].duration, 1);
console.log('PASS: editor preserves ties and note durations across save, reorder, staff correction and delete');
