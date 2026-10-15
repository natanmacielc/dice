// Testes das peças visuais com um DOM mínimo falso (o Node não tem DOM).
import { test } from 'node:test';
import assert from 'node:assert/strict';

class FakeElement {
  constructor(tag) {
    this.tag = tag;
    this.attrs = {};
    this.children = [];
    this.classes = new Set();
    this.textContent = '';
    this.offsetWidth = 0;
    const props = {};
    this.style = { props, setProperty: (k, v) => { props[k] = v; } };
    this.classList = {
      add: (...c) => c.forEach((x) => this.classes.add(x)),
      remove: (...c) => c.forEach((x) => this.classes.delete(x)),
      contains: (c) => this.classes.has(c),
    };
  }

  set className(value) {
    this.classes = new Set(value.split(/\s+/).filter(Boolean));
  }

  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return this.attrs[k]; }
  append(...c) { this.children.push(...c); }
  addEventListener() {}
}

globalThis.document = {
  createElement: (tag) => new FakeElement(tag),
  createElementNS: (_ns, tag) => new FakeElement(tag),
};

// requestAnimationFrame controlado pelo teste.
let frames = [];
globalThis.requestAnimationFrame = (cb) => frames.push(cb);
function runFrames(atMs) {
  const now = performance.now() + atMs;
  const pending = frames;
  frames = [];
  pending.forEach((cb) => cb(now));
}
function finishAnimation() {
  for (let i = 0; i < 100 && frames.length; i++) runFrames(10_000);
}

const { Die } = await import('./ui.js');

test('trocar o dado esconde as facetas só durante o morph e mostra as novas no fim (regressão)', () => {
  const die = new Die(20);
  const d20 = die.facets.getAttribute('d');
  assert.ok(d20.length > 0);

  die.morphTo(6);
  runFrames(50); // começo do morph
  assert.ok(die.el.classes.has('facets-out'), 'facetas somem enquanto o contorno muda');

  finishAnimation();
  assert.equal(frames.length, 0);
  assert.ok(!die.el.classes.has('facets-out'), 'facetas voltam ao fim do morph');
  assert.ok(!die.el.classes.has('is-morphing'), 'classe do morph é removida ao fim');
  assert.notEqual(die.facets.getAttribute('d'), d20, 'facetas do d6 no lugar das do d20');
  assert.equal(die.face.textContent, '6');
});

test('dois morphs seguidos: o primeiro para e o último dado vence', () => {
  const die = new Die(20);
  die.morphTo(8);
  runFrames(100);
  die.morphTo(12);
  finishAnimation();
  const d12 = new Die(12);
  assert.equal(die.facets.getAttribute('d'), d12.facets.getAttribute('d'));
  assert.equal(die.body.getAttribute('d'), d12.body.getAttribute('d'));
  assert.ok(!die.el.classes.has('facets-out'));
  assert.ok(!die.el.classes.has('is-morphing'));
});
