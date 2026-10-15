// Peças visuais compartilhadas: dados em SVG, animações, diálogos e checklist.
// Sem regra de negócio e sem innerHTML: todo texto entra por textContent.

const SVG_NS = 'http://www.w3.org/2000/svg';

export const DIE_SIDES = [4, 6, 8, 10, 12, 20, 100];
export const DIE_COLORS = {
  4: '#2ee6ff', 6: '#4dffa6', 8: '#ffc23d', 10: '#ff7a3d', 12: '#ff3d9a', 20: '#a86bff', 100: '#6b8cff',
};

export const reducedMotion = () => globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
export const $ = (id) => document.getElementById(id);

/** Cria um elemento. Strings em children viram nós de texto (nunca HTML). */
export function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  node.append(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

function svg(tag, attrs = {}) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
}

// ---------- Formas dos dados (viewBox 200x200) ----------

const polar = (n, r, rot = -90, cx = 100, cy = 100) => Array.from({ length: n }, (_, i) => {
  const a = ((rot + (360 * i) / n) * Math.PI) / 180;
  return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
});
const path = (...polylines) => polylines.map((pl) => `M${pl.map((p) => p.map((v) => v.toFixed(1)).join(' ')).join('L')}`).join('');

const hex = polar(6, 92);
const deca = polar(10, 90);
const pent = polar(5, 48, -90, 100, 104);
const tri20 = [[100, 48], [146, 128], [54, 128]];

/** Cada forma começa no ponto mais alto, no sentido horário: o morph casa vértice com vértice. */
const SHAPES = {
  4: { outline: [[100, 14], [188, 168], [12, 168]], cy: 122, size: 56, facets: '' },
  6: { outline: [[100, 22], [178, 22], [178, 178], [22, 178], [22, 22]], cy: 100, size: 84,
    facets: path([[40, 40], [160, 40], [160, 160], [40, 160], [40, 40]]) },
  8: { outline: [[100, 8], [184, 100], [100, 192], [16, 100]], cy: 98, size: 48,
    facets: path([[100, 8], [156, 128], [44, 128], [100, 8]], [[44, 128], [16, 100]], [[156, 128], [184, 100]],
      [[44, 128], [100, 192], [156, 128]]) },
  10: { outline: [[100, 6], [186, 92], [100, 194], [14, 92]], cy: 92, size: 44,
    facets: path([[100, 6], [142, 116], [100, 140], [58, 116], [100, 6]], [[142, 116], [186, 92]],
      [[58, 116], [14, 92]], [[100, 140], [100, 194]]) },
  12: { outline: deca, cy: 104, size: 50,
    facets: path([...pent, pent[0]], ...pent.map((p, i) => [p, deca[2 * i]])) },
  20: { outline: hex, cy: 103, size: 46,
    facets: path([...tri20, tri20[0]], [tri20[0], hex[0]], [tri20[0], hex[1]], [tri20[0], hex[5]],
      [tri20[1], hex[1]], [tri20[1], hex[2]], [tri20[1], hex[3]], [tri20[2], hex[3]], [tri20[2], hex[4]], [tri20[2], hex[5]]) },
  100: { outline: deca, cy: 100, size: 50,
    facets: path([...polar(10, 62), polar(10, 62)[0]], ...deca.map((p, i) => [p, polar(10, 62)[i]])) },
};

const MORPH_POINTS = 60;

/** Reamostra o contorno em N pontos mantendo os vértices (cantos não arredondam no morph). */
function resample(outline, n = MORPH_POINTS) {
  const closed = [...outline, outline[0]];
  const lengths = outline.map((p, i) => Math.hypot(closed[i + 1][0] - p[0], closed[i + 1][1] - p[1]));
  const total = lengths.reduce((a, b) => a + b, 0);
  const counts = lengths.map((l) => Math.max(1, Math.floor((n * l) / total)));
  for (let sum = counts.reduce((a, b) => a + b, 0); sum < n; sum++) {
    let best = 0; // a aresta com o maior espaçamento ganha mais um ponto
    counts.forEach((c, i) => { if (lengths[i] / c > lengths[best] / counts[best]) best = i; });
    counts[best]++;
  }
  const out = [];
  outline.forEach((p, i) => {
    const q = closed[i + 1];
    for (let k = 0; k < counts[i]; k++) {
      const t = k / counts[i];
      out.push([p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t]);
    }
  });
  return out;
}

const POINTS = Object.fromEntries(DIE_SIDES.map((s) => [s, resample(SHAPES[s].outline)]));
const toPath = (pts) => `M${pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join('L')}Z`;
const easeOutBack = (t) => 1 + 2.2 * (t - 1) ** 3 + 1.2 * (t - 1) ** 2;

function faceSize(sides, text) {
  const base = SHAPES[sides].size;
  return text.length >= 3 ? base * 0.74 : base;
}

/** Ícone estático de um dado (seletor, histórico). */
export function dieIcon(sides, label = null) {
  const icon = svg('svg', { viewBox: '0 0 200 200', class: 'die-icon', 'aria-hidden': 'true' });
  icon.append(svg('path', { class: 'die-body', d: toPath(POINTS[sides]) }), svg('path', { class: 'die-facets', d: SHAPES[sides].facets }));
  if (label !== null) {
    const text = String(label);
    const t = svg('text', { class: 'die-face', x: 100, y: SHAPES[sides].cy, 'font-size': faceSize(sides, text) * 1.25 });
    t.textContent = text;
    icon.append(t);
  }
  return icon;
}

/** Um dado animável. */
export class Die {
  constructor(sides) {
    this.sides = sides;
    this.points = POINTS[sides];
    this.el = h('div', { class: 'die' });
    this.spin = h('div', { class: 'die-spin' });
    const g = svg('svg', { viewBox: '0 0 200 200', 'aria-hidden': 'true' });
    this.body = svg('path', { class: 'die-body' });
    this.shine = svg('path', { class: 'die-shine' });
    this.facets = svg('path', { class: 'die-facets' });
    this.face = svg('text', { class: 'die-face', x: 100 });
    g.append(this.body, this.shine, this.facets, this.face);
    this.spin.append(g);
    this.el.append(this.spin);
    this.draw(this.points);
    this.facets.setAttribute('d', SHAPES[sides].facets);
    this.color(sides);
    this.idle();
  }

  color(sides) {
    this.el.style.setProperty('--c', DIE_COLORS[sides]);
  }

  draw(points) {
    const d = toPath(points);
    this.body.setAttribute('d', d);
    this.shine.setAttribute('d', d);
  }

  setFace(text, cls = '') {
    const value = String(text);
    this.face.textContent = value;
    this.face.setAttribute('y', SHAPES[this.sides].cy);
    this.face.setAttribute('font-size', faceSize(this.sides, value));
    this.el.classList.remove('is-idle', 'is-max', 'is-min');
    if (cls) this.el.classList.add(cls);
  }

  idle() {
    this.setFace(this.sides === 100 ? '%' : this.sides, 'is-idle');
  }

  /** Troca o número de lados com morph do contorno. */
  morphTo(sides) {
    if (sides === this.sides) return;
    const from = this.points;
    const to = POINTS[sides];
    this.sides = sides;
    this.points = to;
    this.color(sides);
    if (reducedMotion()) {
      this.draw(to);
      this.facets.setAttribute('d', SHAPES[sides].facets);
      this.idle();
      return;
    }
    this.el.classList.remove('is-morphing');
    void this.el.offsetWidth; // reinicia a animação
    this.el.classList.add('is-morphing');
    const start = performance.now();
    const duration = 520;
    let swapped = false;
    const frame = (now) => {
      const t = Math.min(1, (now - start) / duration);
      const e = easeOutBack(t);
      this.draw(from.map(([x, y], i) => [x + (to[i][0] - x) * e, y + (to[i][1] - y) * e]));
      if (!swapped && t > 0.45) {
        swapped = true;
        this.facets.setAttribute('d', SHAPES[sides].facets);
        this.idle();
      }
      if (t < 1) requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }

  tumble() {
    this.el.classList.remove('is-landing', 'is-max', 'is-min', 'is-idle');
    this.el.style.setProperty('--tumble-ms', `${420 + Math.random() * 260}ms`);
    this.el.style.setProperty('--tumble-delay', `${-Math.random() * 600}ms`);
    this.el.style.setProperty('--tumble-dir', Math.random() < 0.5 ? '1' : '-1');
    this.setFace('?');
    this.el.classList.add('is-tumbling');
  }

  /** Pousa mostrando o valor real. Resolve quando a animação termina. */
  async land(value, delay = 0) {
    await sleep(delay);
    this.el.classList.remove('is-tumbling');
    void this.el.offsetWidth;
    this.el.classList.add('is-landing');
    await sleep(reducedMotion() ? 0 : 200);
    const max = this.sides === 100 ? 100 : this.sides;
    this.setFace(value, value === max ? 'is-max' : value === 1 ? 'is-min' : '');
    await sleep(reducedMotion() ? 0 : 450);
    this.el.classList.remove('is-landing');
  }
}

/** Bandeja de dados: quantidade, tamanho e animações em grupo. */
export class Tray {
  constructor(container, sides = 20, count = 1) {
    this.container = container;
    this.sides = sides;
    this.dice = [];
    this.setCount(count, false);
  }

  setCount(count, animate = true) {
    while (this.dice.length < count) {
      const die = new Die(this.sides);
      if (animate && !reducedMotion()) {
        die.el.classList.add('is-entering');
        die.el.addEventListener('animationend', () => die.el.classList.remove('is-entering'), { once: true });
      }
      this.dice.push(die);
      this.container.append(die.el);
    }
    while (this.dice.length > count) {
      const die = this.dice.pop();
      if (animate && !reducedMotion()) {
        die.el.classList.add('is-leaving');
        setTimeout(() => die.el.remove(), 260);
      } else {
        die.el.remove();
      }
    }
    this.container.dataset.size = count === 1 ? 'xl' : count <= 4 ? 'lg' : count <= 9 ? 'md' : 'sm';
  }

  setSides(sides) {
    this.sides = sides;
    for (const die of this.dice) die.morphTo(sides);
  }

  idle() {
    for (const die of this.dice) {
      die.el.classList.remove('is-tumbling', 'is-landing');
      die.idle();
    }
  }

  tumble() {
    for (const die of this.dice) die.tumble();
  }

  /** Pousa os dados um a um, com o valor real de cada um. */
  async land(values) {
    const step = reducedMotion() ? 0 : Math.max(60, Math.min(160, 900 / values.length));
    await Promise.all(this.dice.map((die, i) => die.land(values[i], i * step)));
  }

  /** Mostra valores sem animação (página já resolvida). */
  show(values) {
    this.dice.forEach((die, i) => {
      die.el.classList.remove('is-tumbling', 'is-landing');
      const max = die.sides === 100 ? 100 : die.sides;
      die.setFace(values[i], values[i] === max ? 'is-max' : values[i] === 1 ? 'is-min' : '');
    });
  }
}

// ---------- Efeitos ----------

/** Explosão de faíscas a partir do centro do elemento. */
export function burst(container, { count = 22, colors = ['var(--c)', '#ffffff'], spread = 160 } = {}) {
  if (reducedMotion()) return;
  for (let i = 0; i < count; i++) {
    const angle = Math.random() * Math.PI * 2;
    const distance = spread * (0.45 + Math.random() * 0.55);
    const spark = h('span', { class: 'spark' });
    spark.style.setProperty('--dx', `${Math.cos(angle) * distance}px`);
    spark.style.setProperty('--dy', `${Math.sin(angle) * distance}px`);
    spark.style.setProperty('--spark', colors[i % colors.length]);
    spark.style.setProperty('--spark-size', `${4 + Math.random() * 6}px`);
    spark.style.setProperty('--spark-ms', `${600 + Math.random() * 500}ms`);
    container.append(spark);
    spark.addEventListener('animationend', () => spark.remove(), { once: true });
  }
}

/** Conta de 0 até o valor. */
export function countUp(el, to, ms = 700) {
  if (reducedMotion() || ms <= 0) {
    el.textContent = String(to);
    return;
  }
  const start = performance.now();
  const from = 0;
  const frame = (now) => {
    const t = Math.min(1, (now - start) / ms);
    const e = 1 - (1 - t) ** 3;
    el.textContent = String(Math.round(from + (to - from) * e));
    if (t < 1) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

/** Anel do palco: progresso até o round alvo (0..1) e modos idle/waiting/done. */
export function ring(root) {
  const progress = root.querySelector('.ring-progress');
  return {
    mode(m) {
      root.dataset.mode = m;
    },
    progress(p) {
      progress.style.setProperty('stroke-dashoffset', String(100 - Math.max(0, Math.min(1, p)) * 100));
    },
  };
}

// ---------- Diálogos e avisos ----------

/** Liga botões [data-open] e [data-close] aos <dialog>; clique no fundo fecha. */
export function setupDialogs() {
  document.addEventListener('click', (event) => {
    const opener = event.target.closest?.('[data-open]');
    if (opener) openDialog($(opener.dataset.open));
    const closer = event.target.closest?.('[data-close]');
    if (closer) closeDialog(closer.closest('dialog'));
  });
  for (const dialog of document.querySelectorAll('dialog')) {
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog) closeDialog(dialog);
    });
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      closeDialog(dialog);
    });
  }
}

export function openDialog(dialog) {
  if (!dialog || dialog.open) return;
  dialog.classList.remove('is-closing');
  dialog.showModal();
  dialog.dispatchEvent(new Event('opened'));
}

export function closeDialog(dialog) {
  if (!dialog?.open) return;
  if (reducedMotion()) {
    dialog.close();
    return;
  }
  dialog.classList.add('is-closing');
  setTimeout(() => {
    dialog.classList.remove('is-closing');
    dialog.close();
  }, 220);
}

let toastTimer = null;
export function toast(message) {
  let el = $('toast');
  if (!el) {
    el = h('div', { id: 'toast', class: 'toast', role: 'status', 'aria-live': 'polite' });
    document.body.append(el);
  }
  el.textContent = message;
  el.classList.add('is-visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('is-visible'), 2200);
}

export async function copyText(text, message = 'Copiado!') {
  try {
    await navigator.clipboard.writeText(text);
    toast(message);
  } catch {
    toast('Não deu para copiar. Selecione e copie à mão.');
  }
}

/** Compartilha pelo menu nativo; sem ele, copia o link. */
export async function shareLink(url, title) {
  if (navigator.share) {
    try {
      await navigator.share({ url, title });
      return;
    } catch (e) {
      if (e.name === 'AbortError') return;
    }
  }
  await copyText(url, 'Link copiado!');
}

/** Botão de copiar ao lado de um valor. */
export function copyButton(value, label = 'Copiar') {
  return h('button', { type: 'button', class: 'copy', 'aria-label': `${label}: ${value}`, onclick: () => copyText(value) }, 'Copiar');
}

// ---------- Verificação ----------

const ICON_OK = 'M5 12.5l4.5 4.5L19 7.5';
const ICON_FAIL = 'M7 7l10 10M17 7L7 17';

function checkIcon(ok) {
  const icon = svg('svg', { viewBox: '0 0 24 24', class: 'check-icon', 'aria-hidden': 'true' });
  icon.append(svg('path', { d: ok ? ICON_OK : ICON_FAIL, pathLength: 1 }));
  return icon;
}

/** Lista do checklist, um item acendendo depois do outro. */
export function renderChecks(list, checks) {
  list.replaceChildren(...checks.map((c, i) => {
    const item = h('li', { class: `check ${c.ok ? 'is-ok' : 'is-fail'}` },
      h('span', { class: 'check-mark' }, checkIcon(c.ok)),
      h('span', { class: 'check-text' }, h('strong', { text: c.label }), h('span', { text: c.detail })));
    item.style.setProperty('--i', String(i));
    return item;
  }));
}

/**
 * Selo de verificação: roda o checklist e, ao clicar, abre o diálogo com o detalhe.
 * dialog precisa ter #verify-title, #verify-checks, #verify-verdict e links [data-roll-link].
 */
export async function runVerificationBadge(badge, dialog, rollId, verifyRoll) {
  badge.hidden = false;
  badge.dataset.state = 'running';
  badge.querySelector('.badge-text').textContent = 'Conferindo no seu navegador…';
  for (const a of dialog.querySelectorAll('[data-roll-link]')) {
    a.href = a.dataset.rollLink.replace('{id}', rollId);
  }
  dialog.querySelector('#verify-checks').replaceChildren(h('li', { class: 'check is-running', text: 'Conferindo…' }));
  dialog.querySelector('#verify-verdict').textContent = '';
  const { proof, checks } = await verifyRoll(rollId);
  const ok = checks.every((c) => c.ok);
  badge.dataset.state = ok ? 'ok' : 'fail';
  badge.querySelector('.badge-text').textContent = ok ? 'Conferido no seu navegador' : 'Falhou na conferência';
  renderChecks(dialog.querySelector('#verify-checks'), checks);
  dialog.dataset.state = ok ? 'ok' : 'fail';
  dialog.querySelector('#verify-title').textContent = ok ? 'Tudo confere' : 'Atenção: algo não confere';
  dialog.querySelector('#verify-verdict').textContent = ok
    ? 'Este resultado veio de um número público do drand que ainda não existia quando você rolou. Ninguém, nem o servidor, podia prever ou escolher.'
    : 'Alguma conferência falhou. Não confie neste resultado até entender o motivo.';
  return { proof, checks, ok };
}

// ---------- Resultado ----------

/** Faíscas, banner de crítico e tremor de falha crítica. */
export function celebrate(stage, banner, crit) {
  banner.hidden = true;
  if (crit === 'crit') {
    burst(stage, { count: 46, colors: ['#ffc23d', '#fff3c4', '#ffffff'], spread: 220 });
  } else if (crit === 'fumble') {
    stage.classList.add('is-shaking');
    setTimeout(() => stage.classList.remove('is-shaking'), 600);
  } else {
    burst(stage);
    return;
  }
  banner.textContent = crit === 'crit' ? 'Crítico!' : 'Falha crítica';
  banner.dataset.kind = crit;
  banner.hidden = false;
  replay(banner, 'is-in');
}

/** Reinicia uma animação de entrada baseada em classe. */
export function replay(el, cls) {
  el.classList.remove(cls);
  void el.offsetWidth;
  el.classList.add(cls);
}

const count = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/** "Verificar a mesa inteira": elos acendendo e o veredito. */
export async function runTableCheck({ button, chain, out }, sessionId, verifyTable) {
  button.disabled = true;
  chain.hidden = false;
  chain.dataset.state = 'running';
  chain.replaceChildren(...Array.from({ length: 24 }, (_, i) => {
    const link = h('span', { class: 'chain-link' });
    link.style.setProperty('--i', String(i));
    return link;
  }));
  out.className = 'chain-result';
  out.textContent = 'Baixando e conferindo cada elo da cadeia…';
  try {
    const [{ result, summary }] = await Promise.all([verifyTable(sessionId), sleep(reducedMotion() ? 0 : 1100)]);
    chain.dataset.state = result.valid ? 'ok' : 'fail';
    out.classList.add(result.valid ? 'is-ok' : 'is-fail');
    out.textContent = result.valid
      ? `Cadeia íntegra desde a primeira entrada: ${count(summary.requests, 'pedido', 'pedidos')}, ${count(summary.resolved, 'resolvido', 'resolvidos')}, ${count(summary.pending.length, 'pendente', 'pendentes')}. Nada foi apagado nem alterado.`
      : `Cadeia inválida: ${result.code} (${result.message})`;
  } catch (e) {
    chain.dataset.state = 'fail';
    out.classList.add('is-fail');
    out.textContent = e.message;
  } finally {
    button.disabled = false;
  }
}
