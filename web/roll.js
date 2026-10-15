// Página /r/{rollId}: acompanha o roll e verifica tudo no navegador.
import { GENESIS_PREV_HASH, timeOf, verifyProof, verifySegment } from './verifier/chain-v1.js';
import { fetchRound, verifyBls } from './verifier/drand-verify.js';
import { describeError, formatDice, runChecks, secondsUntil, summarizeLog } from './page-logic.js';

const $ = (id) => document.getElementById(id);
const rollId = $('roll').dataset.rollId;
const STATUS = {
  PENDING: 'Aguardando o drand',
  RESOLVED: 'Resolvido',
  EXPIRED: 'Atrasado: o drand ainda não respondeu',
};

let expectedAt = null;
let sessionId = null;

async function getJson(url) {
  const response = await fetch(url, { headers: { accept: 'application/json' } });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(describeError(body, response.status));
    error.status = response.status;
    throw error;
  }
  return body;
}

function renderRoll(roll) {
  sessionId = roll.sessionId;
  expectedAt = roll.expectedAt;
  $('status').textContent = STATUS[roll.status] ?? roll.status;
  $('status').dataset.status = roll.status;
  if (roll.result) {
    $('total').textContent = String(roll.result.total);
    $('dice').textContent = `dados ${formatDice(roll.result.dice)}`;
    $('countdown').textContent = '';
  }
}

function tick() {
  if (!expectedAt || $('status').dataset.status === 'RESOLVED') return;
  const s = secondsUntil(expectedAt, Date.now());
  $('countdown').textContent = s > 0 ? `round alvo sai em ~${s} s` : 'buscando o round no drand…';
}

function renderChecks(checks) {
  const list = $('checks');
  list.replaceChildren();
  for (const c of checks) {
    const item = document.createElement('li');
    item.className = c.ok ? 'ok' : 'fail';
    const label = document.createElement('strong');
    label.textContent = `${c.ok ? '✓' : '✗'} ${c.label}`;
    const detail = document.createElement('span');
    detail.textContent = c.detail;
    item.append(label, detail);
    list.append(item);
  }
  const allOk = checks.every((c) => c.ok);
  $('verdict').textContent = allOk
    ? 'Tudo confere: este resultado não podia ser escolhido nem previsto.'
    : 'Atenção: alguma verificação falhou. Não confie neste resultado.';
  $('verdict').className = allOk ? 'verdict ok' : 'verdict fail';
}

async function verify() {
  $('checks').replaceChildren(Object.assign(document.createElement('li'), { className: 'muted', textContent: 'Verificando…' }));
  try {
    const proof = await getJson(`/rolls/${rollId}/proof`);
    renderChecks(await runChecks({ proof, verifyProof, fetchRound, verifyBls, timeOf }));
  } catch (e) {
    renderChecks([{ id: 'proof', label: 'Prova obtida do servidor', ok: false, detail: e.message }]);
  }
}

async function poll() {
  try {
    const roll = await getJson(`/rolls/${rollId}`);
    renderRoll(roll);
    if (roll.status === 'RESOLVED') {
      await verify();
      return;
    }
  } catch (e) {
    $('countdown').textContent = e.message;
    if (e.status === 404) return;
  }
  setTimeout(poll, 3000);
}

$('verify-session').addEventListener('click', async () => {
  const out = $('session-result');
  out.className = 'muted';
  out.textContent = 'Baixando o log da mesa…';
  try {
    const entries = [];
    let after = 0;
    for (;;) {
      const page = await getJson(`/sessions/${sessionId}/log?after=${after}`);
      entries.push(...page.entries);
      if (page.next === null) break;
      after = page.next;
    }
    const result = await verifySegment(entries, GENESIS_PREV_HASH);
    const s = summarizeLog(entries);
    out.className = result.valid ? 'ok' : 'fail';
    out.textContent = result.valid
      ? `✓ Cadeia íntegra desde o início: ${s.entries} entradas, ${s.requests} pedidos, ${s.resolved} resolvidos, ${s.pending.length} pendentes.`
      : `✗ Cadeia inválida: ${result.code} (${result.message})`;
  } catch (e) {
    out.className = 'fail';
    out.textContent = e.message;
  }
});

setInterval(tick, 250);
poll();
