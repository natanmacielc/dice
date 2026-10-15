// Página "Por que confiar?": explicação e conferência de qualquer rolagem pelo próprio usuário.
import { fetchLog, verifyRoll } from './api.js';
import { drandRoundUrl, explainRoll, formatBreakdown, historyFromLog, parseRollInput, shellQuote } from './page-logic.js';
import { roll as computeRoll } from './verifier/dice-v1.js';
import { playerMessage } from './verifier/chain-v2.js';
import { $, DIE_COLORS, copyButton, dieIcon, h, renderChecks } from './ui.js';

const SESSION_KEY = 'dice.sessionId';
const REPO = 'https://github.com/natanmacielc/dices';

// ---------- Entrada animada das seções ----------

const observer = 'IntersectionObserver' in globalThis
  ? new IntersectionObserver((items) => {
    for (const item of items) {
      if (item.isIntersecting) {
        item.target.classList.add('is-visible');
        observer.unobserve(item.target);
      }
    }
  }, { threshold: 0.2 })
  : null;
for (const el of document.querySelectorAll('.reveal')) {
  if (observer) observer.observe(el);
  else el.classList.add('is-visible');
}

// ---------- Blocos de exibição ----------

function codeLine(value, label) {
  return h('div', { class: 'code-line' },
    label ? h('span', { class: 'code-label', text: label }) : null,
    h('code', { text: value }),
    copyButton(value, label ?? 'valor'));
}

function command(text) {
  return h('div', { class: 'cmd' }, h('pre', {}, h('code', { text })), copyButton(text, 'comando'));
}

function ingredient(name, value, note) {
  return h('li', {}, h('span', { class: 'ing-name', text: name }), codeLine(String(value)), note ? h('span', { class: 'field-help', text: note }) : null);
}

/**
 * Passo a passo: ingredientes, texto de cada bloco, hash, palavras e o dado de cada uma.
 * A conta exibida vem de explainRoll; a fonte da verdade é roll() do verificador, e os dois são comparados.
 */
async function renderMath(target, input, { round = null, claimedDice = null } = {}) {
  const [steps, official] = await Promise.all([explainRoll(input), computeRoll(input)]);
  const same = (a, b) => a.length === b.length && a.every((d, i) => d === b[i]);
  const agrees = same(steps.dice, official.dice) && steps.total === official.total;

  const ingredients = h('ol', { class: 'ingredients' },
    ingredient('Número do drand (randomness)', input.randomness, round ? null : 'cole o valor publicado pelo drand'),
    ingredient('Sua seed', input.clientSeed),
    ingredient('Nonce (número do pedido na mesa)', input.nonce),
    ingredient('Expressão', steps.expression));
  const drandLink = round
    ? h('p', { class: 'field-help' }, 'Veja este número direto no drand: ', h('a', { href: drandRoundUrl(round), target: '_blank', rel: 'noopener', text: `round ${round}` }))
    : null;

  const blocks = steps.blocks.map((b) => h('div', { class: 'block' },
    h('p', { class: 'block-title', text: `Bloco ${b.counter}: SHA-256 deste texto` }),
    codeLine(b.input, 'texto'),
    codeLine(b.hash, 'hash'),
    h('p', { class: 'field-help', text: 'Confira no terminal (no Linux, troque shasum -a 256 por sha256sum):' }),
    command(`printf '%s' ${shellQuote(b.input)} | shasum -a 256`)));

  const words = h('ol', { class: 'words' }, ...steps.words.map((w) => {
    const item = h('li', { class: `word${w.rejected ? ' is-rejected' : ''}` },
      h('code', { class: 'word-hex', text: w.hex }),
      h('span', { class: 'word-arrow', text: '→' }),
      h('span', { class: 'word-dec', text: w.value.toLocaleString('pt-BR') }),
      w.rejected
        ? h('span', { class: 'word-result', text: `descartada (≥ ${steps.limit.toLocaleString('pt-BR')}, para não favorecer nenhuma face)` })
        : h('span', { class: 'word-result' }, `% ${steps.sides} + 1 = `, h('strong', { text: String(w.die) })));
    item.style.setProperty('--i', String(Math.min(w.index, 20)));
    return item;
  }));

  const claimedOk = claimedDice === null || same(claimedDice, official.dice);
  const final = h('p', { class: `math-final ${agrees && claimedOk ? 'is-ok' : 'is-fail'}` },
    h('span', { text: formatBreakdown(official.dice, official.modifier) || String(official.dice[0]) }),
    h('span', { text: ' = ' }),
    h('strong', { text: String(official.total) }),
    h('span', {
      class: 'math-verdict',
      text: !agrees ? 'O passo a passo diverge do verificador. Avise-nos!'
        : claimedDice === null ? 'Calculado no seu navegador.'
          : claimedOk ? 'Igual ao resultado registrado.' : 'Diferente do resultado registrado!',
    }));

  target.replaceChildren(
    h('h4', { text: '1. Os ingredientes' }), ingredients, drandLink,
    h('h4', { text: '2. Misture tudo no SHA-256' }),
    h('p', { class: 'muted', text: 'Junte os ingredientes separados por "|", com o número do bloco no fim (0, 1, 2… se precisar de mais números).' }),
    ...blocks,
    h('h4', { text: '3. Corte o hash em pedaços de 8 caracteres' }),
    h('p', { class: 'muted', text: `Cada pedaço vira um número (de 0 a 4.294.967.295). O resto da divisão por ${steps.sides}, mais 1, é a face do dado.` }),
    words,
    h('h4', { text: '4. Some' }), final);
}

// ---------- Conferir uma rolagem ----------

function renderSummary(proof) {
  const r = proof.roll;
  const sides = Number(/d(\d+)/.exec(r.expression)[1]);
  const box = $('summary');
  box.style.setProperty('--c', DIE_COLORS[sides] ?? DIE_COLORS[20]);
  box.replaceChildren(
    h('div', { class: 'summary-dice' }, ...r.dice.slice(0, 8).map((d) => dieIcon(sides, d)), r.dice.length > 8 ? h('span', { class: 'muted', text: `+${r.dice.length - 8}` }) : null),
    h('div', { class: 'summary-text' },
      h('strong', { class: 'summary-total', text: String(r.total) }),
      h('span', { text: `${r.expression} · ${formatBreakdown(r.dice, r.modifier) || `dado ${r.dice[0]}`}` }),
      r.player ? h('span', { text: `rolado por ${r.player.name}` }) : null,
      h('a', { class: 'text-link', href: `/r/${r.rollId}`, text: 'Abrir a rolagem' })));
}

function renderPython(proof) {
  const r = proof.roll;
  $('python-cmds').replaceChildren(
    command(`git clone ${REPO}.git\ncd dices/tools/verify-py`),
    h('p', { class: 'field-help', text: 'Refaz o dado a partir dos ingredientes:' }),
    command(`python3 -m dice_verify --randomness ${proof.beacon.randomness} --seed ${shellQuote(r.clientSeed)} --nonce ${r.nonce} --expr ${r.expression} --signature ${proof.beacon.signature}`),
    h('p', { class: 'field-help', text: 'Ou confere a prova inteira (cadeia, assinatura de quem rolou, round e resultado):' }),
    command(`python3 -m pip install cryptography\ncurl -s ${location.origin}/api/rolls/${r.rollId}/proof > prova.json\npython3 -m dice_verify.proof prova.json`),
    ...playerBlock(proof));
}

/** chain-v2: a mensagem que o jogador assinou, a chave e a assinatura (Ed25519). */
function playerBlock(proof) {
  const r = proof.roll;
  if (proof.chainFormat !== 'chain-v2' || !r.player) return [];
  const p = r.player;
  const message = new TextDecoder().decode(playerMessage({
    sessionId: r.sessionId, clientSeed: r.clientSeed, expression: r.expression, identityKey: p.identityKey, name: p.name, requestId: p.requestId,
  }));
  const out = [
    h('p', { class: 'field-help', text: `O aparelho de ${p.name} assinou exatamente esta mensagem (Ed25519). Trocar qualquer letra invalida a assinatura:` }),
    codeLine(message, 'mensagem'),
    codeLine(p.publicKey, 'aparelho'),
    codeLine(p.signature, 'assinatura'),
    codeLine(p.identityKey, 'identidade'),
  ];
  if (p.links.length) {
    out.push(h('p', { class: 'field-help', text: `O aparelho está ligado à identidade por ${p.links.length} vínculo(s): cada um é assinado pela chave anterior (a identidade, depois cada aparelho) e diz "este aparelho também é meu".` }));
    p.links.forEach((l, i) => out.push(codeLine(l.deviceKey, `vínculo ${i + 1}`)));
  }
  return out;
}

async function check(rollId) {
  $('check-error').textContent = '';
  $('check-input').value = rollId;
  history.replaceState(null, '', `?roll=${rollId}#confira`);
  $('check-out').hidden = false;
  $('summary').replaceChildren();
  $('verdict-title').className = 'verdict-title';
  $('verdict-title').textContent = 'Conferindo no seu navegador…';
  $('checks').replaceChildren(h('li', { class: 'check is-running', text: 'Baixando a prova e buscando o round no drand…' }));
  $('math').replaceChildren();
  const { proof, checks } = await verifyRoll(rollId);
  renderChecks($('checks'), checks);
  const ok = checks.every((c) => c.ok);
  $('verdict-title').classList.add(ok ? 'is-ok' : 'is-fail');
  $('verdict-title').textContent = ok ? 'Tudo confere' : 'Algo não confere';
  if (!proof) return;
  renderSummary(proof);
  renderPython(proof);
  const r = proof.roll;
  await renderMath($('math'), {
    randomness: proof.beacon.randomness, clientSeed: r.clientSeed, nonce: r.nonce, expression: r.expression,
  }, { round: proof.beacon.round, claimedDice: r.dice });
}

$('check-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const id = parseRollInput($('check-input').value);
  if (!id) {
    $('check-error').textContent = 'Não reconheci. Cole o link da rolagem (…/r/código) ou o código de 16 caracteres.';
    return;
  }
  check(id);
});

/** "Usar minha última rolagem": a mais recente resolvida da mesa salva neste navegador. */
async function setupLastRoll() {
  let sessionId = null;
  try {
    sessionId = localStorage.getItem(SESSION_KEY);
  } catch {
    return;
  }
  if (!sessionId) return;
  let last;
  try {
    last = historyFromLog(await fetchLog(sessionId)).find((r) => r.status === 'RESOLVED');
  } catch {
    return;
  }
  if (!last) return;
  $('use-last').hidden = false;
  $('use-last').addEventListener('click', () => check(last.rollId));
}

// ---------- Modo manual ----------

$('drand-example').textContent = drandRoundUrl('ROUND');
$('manual-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('manual-error').textContent = '';
  const f = event.target.elements;
  const input = {
    randomness: f.randomness.value.trim().toLowerCase(),
    clientSeed: f.clientSeed.value,
    nonce: Number(f.nonce.value),
    expression: f.expression.value,
  };
  try {
    await renderMath($('manual-math'), input);
  } catch (e) {
    $('manual-math').replaceChildren();
    $('manual-error').textContent = e.message;
  }
});

// ---------- Início ----------

const fromUrl = parseRollInput(new URLSearchParams(location.search).get('roll'));
if (fromUrl) {
  $('math-panel').open = true;
  check(fromUrl);
  requestAnimationFrame(() => $('confira').scrollIntoView({ behavior: 'smooth' }));
}
setupLastRoll();
