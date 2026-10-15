// Página inicial: escolhe o dado, rola, anima e confere no navegador.
import { fetchLog, getJson, myTables, postJson, verifyRoll, verifyTable } from './api.js';
import {
  buildExpression, criticalOf, formatBreakdown, formatModifier, generateSeed, historyFromLog, historyLoadError, initialOf,
  mergeTables, playerColor, relativeTime, requestRoll, tableLabel, tablesCacheFresh, tablesPage, upsertTable,
} from './page-logic.js';
import { parseExpression } from './verifier/dice-v1.js';
import { fingerprint } from './verifier/chain-v2.js';
import { adoptIdentity, identity, linkDevice, nameProblem, saveName, signRequest, storedName } from './identity.js';
import { acceptIdentity, answerDevice, deviceMac, newSecret, pairingUrl, parsePairingFragment } from './pairing.js';
import {
  $, DIE_COLORS, DIE_SIDES, Tray, celebrate, closeDialog, copyText, countUp, dieIcon, h, lineIcon, openDialog, qrSvg,
  replay, ring,
  runTableCheck, runVerificationBadge, setupDialogs, shareLink, sleep, toast,
} from './ui.js';

const SESSION_KEY = 'dice.sessionId';
const SESSION_NAME_KEY = 'dice.sessionName';
const ID_FORMAT = /^[0-9a-hjkmnp-tv-z]{16}$/;
const POLL_MS = 1000;
const GIVE_UP_MS = 90_000;
const MISSING_TABLE = 'A mesa salva neste navegador não existe mais. Uma nova é criada na próxima rolagem.';
const RECREATED = {
  missing: 'A mesa salva neste navegador não existia mais. Criei uma nova.',
  'read-only': 'A mesa salva era antiga, sem identificação de quem rola. Criei uma nova; convide a turma de novo.',
};

const state = { sides: 20, count: 1, modifier: 0, rolling: false, seedCustom: false, rollId: null };
const tray = new Tray($('tray'), state.sides, state.count);
const stageRing = ring($('ring'));

// ---------- Mesa ----------

function stored(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function store(key, value) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {
    // sem localStorage (modo privado): vale só nesta aba
  }
}

let sessionId = stored(SESSION_KEY);
let sessionName = stored(SESSION_NAME_KEY);

function setTable(id, name = null) {
  // O resultado no palco é da mesa anterior: some ao trocar (durante uma rolagem, resetStage não mexe).
  if (id !== sessionId) resetStage();
  sessionId = id;
  sessionName = name;
  store(SESSION_KEY, id);
  store(SESSION_NAME_KEY, name);
  renderTable();
  refreshHistory();
}

function renderTable() {
  const label = sessionId ? tableLabel({ name: sessionName, sessionId }) : 'Sem mesa';
  $('table-chip-text').textContent = label;
  $('table-chip').classList.toggle('is-active', !!sessionId);
  $('table-current').hidden = !sessionId;
  $('table-none').hidden = !!sessionId;
  $('table-name').textContent = label;
  $('table-id').textContent = sessionId ?? '';
}

/**
 * Põe a mesa na sua lista (seção 7.8) e devolve o nome dela. A lista só ajuda a navegar: se falhar,
 * a mesa continua valendo neste aparelho.
 */
async function rememberTable(id) {
  try {
    const { table } = await myTables('add', { sessionId: id });
    changeTables((items) => upsertTable(items, table));
    return table.name ?? null;
  } catch {
    return null;
  }
}

/** Entra na mesa, fecha as folhas e avisa. */
function enterTable(id, name, message) {
  setTable(id, name);
  closeDialog($('tables-dialog'));
  closeDialog($('table-dialog'));
  toast(message);
}

async function createTable(name) {
  const session = await postJson('/api/sessions', name ? { name } : {});
  setTable(session.sessionId, session.name ?? null);
  rememberTable(session.sessionId);
  return session.sessionId;
}

$('table-new').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('table-error').textContent = '';
  const input = event.target.elements.name;
  try {
    const id = await createTable(input.value.trim());
    input.value = '';
    enterTable(id, sessionName, `${tableLabel({ name: sessionName, sessionId: id })} criada`);
  } catch (e) {
    $('table-error').textContent = e.message;
  }
});

/** Confere no servidor que a mesa existe antes de adotá-la. */
async function tableExists(id) {
  try {
    await getJson(`/api/sessions/${encodeURIComponent(id)}/log?limit=1`);
    return true;
  } catch (e) {
    if (e.status === 404) return false;
    throw e;
  }
}

$('table-join').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = event.target.elements.sessionId;
  const id = input.value.trim().toLowerCase();
  if (!ID_FORMAT.test(id)) {
    $('table-error').textContent = 'O ID da mesa tem 16 caracteres (letras e números).';
    return;
  }
  $('table-error').textContent = '';
  try {
    if (!(await tableExists(id))) {
      $('table-error').textContent = 'Não existe mesa com esse ID.';
      return;
    }
  } catch (e) {
    $('table-error').textContent = e.message;
    return;
  }
  input.value = '';
  const name = await rememberTable(id);
  enterTable(id, name, `Você entrou na ${tableLabel({ name, sessionId: id })}`);
});

$('table-id-copy').addEventListener('click', () => copyText(sessionId));
$('table-invite').addEventListener('click', () => shareLink(`${location.origin}/?mesa=${sessionId}`, 'Entre na minha mesa'));

/** Convite: /?mesa={id} adota a mesa, põe na sua lista e limpa a URL. */
async function adoptInvite() {
  const params = new URLSearchParams(location.search);
  const id = params.get('mesa')?.trim().toLowerCase();
  if (!id) return;
  params.delete('mesa');
  history.replaceState(null, '', `${location.pathname}${params.size ? `?${params}` : ''}${location.hash}`);
  if (!ID_FORMAT.test(id) || id === sessionId) return;
  if (await tableExists(id).catch(() => true)) {
    setTable(id);
    const name = await rememberTable(id);
    if (name) setTable(id, name);
    toast(`Você entrou na ${tableLabel({ name, sessionId: id })} do convite`);
  } else {
    toast('A mesa do convite não existe mais.');
  }
}

// ---------- Suas mesas (seção 7.8 da spec) ----------

// A lista fica em cache (memória e localStorage) e só volta ao servidor quando passa do prazo ou no
// botão de atualizar: o que muda neste aparelho (criar, entrar, rolar, tirar) já atualiza o cache.
const TABLES_KEY = 'dice.tables';
const TABLES_TTL_MS = 10 * 60_000;
const TABLES_PER_PAGE = 6;

function storedTables() {
  try {
    const cache = JSON.parse(stored(TABLES_KEY));
    return Array.isArray(cache?.items) ? cache : null;
  } catch {
    return null;
  }
}

const tablesList = { cache: storedTables(), page: 0, run: 0, loading: false };

function saveTables(cache) {
  tablesList.cache = cache;
  store(TABLES_KEY, cache && JSON.stringify(cache));
  renderTablesCount();
  if ($('tables-dialog').open) renderMyTables();
}

/** Mudança feita neste aparelho: atualiza o cache sem pedir a lista de novo. */
function changeTables(change) {
  if (tablesList.cache) saveTables({ ...tablesList.cache, items: change(tablesList.cache.items) });
}

function renderTablesCount() {
  const cache = tablesList.cache;
  $('my-tables-count').hidden = !cache?.items.length;
  $('my-tables-count').textContent = cache ? `${cache.items.length}${cache.next ? '+' : ''}` : '';
}

async function loadMyTables(more = false) {
  const run = ++tablesList.run;
  const cursor = more ? tablesList.cache?.next : null;
  tablesList.loading = true;
  $('my-tables-status').textContent = tablesList.cache?.items.length ? 'Atualizando…' : 'Carregando suas mesas…';
  renderMyTables();
  try {
    const [{ identityKey }, page] = await Promise.all([identity(), myTables('list', { cursor })]);
    if (run !== tablesList.run) return;
    const items = cursor ? mergeTables(tablesList.cache.items, page.tables) : page.tables;
    tablesList.loading = false;
    if (!cursor) tablesList.page = 0;
    $('my-tables-status').textContent = '';
    saveTables({ identityKey, fetchedAt: cursor ? tablesList.cache.fetchedAt : Date.now(), items, next: page.next });
  } catch (e) {
    if (run !== tablesList.run) return;
    tablesList.loading = false;
    if (cursor) tablesList.page -= 1; // a página seguinte não veio: fica na que já estava
    $('my-tables-status').textContent = e.message;
    renderMyTables();
  }
}

function tableItem(t) {
  const current = t.sessionId === sessionId;
  const remove = h('button', {
    type: 'button', class: 'icon-btn table-remove', 'aria-label': `Tirar ${tableLabel(t)} da sua lista`, title: 'Tirar da lista',
    onclick: async () => {
      try {
        await myTables('remove', { sessionId: t.sessionId });
        changeTables((items) => items.filter((x) => x.sessionId !== t.sessionId));
      } catch (e) {
        $('my-tables-status').textContent = e.message;
      }
    },
  }, lineIcon('M6 6l12 12M18 6L6 18'));
  return h('li', { class: `table-item${current ? ' is-current' : ''}` },
    h('button', {
      type: 'button', class: 'table-open', 'aria-current': current ? 'true' : null,
      onclick: () => (current ? closeDialog($('tables-dialog'))
        : enterTable(t.sessionId, t.name ?? null, `Você está na ${tableLabel(t)}`)),
    },
    h('strong', { text: tableLabel(t) }),
    h('span', { text: current ? 'mesa atual' : `ativa ${relativeTime(t.lastActiveAt)}` })),
    remove);
}

function myTablesView() {
  const cache = tablesList.cache;
  return tablesPage(cache?.items ?? [], tablesList.page, TABLES_PER_PAGE, !!cache?.next);
}

function renderMyTables() {
  const cache = tablesList.cache;
  const view = myTablesView();
  tablesList.page = view.page;
  $('my-tables-list').replaceChildren(...view.slice.map(tableItem));
  $('my-tables-empty').hidden = tablesList.loading || !cache || cache.items.length > 0;
  $('my-tables-pager').hidden = !view.hasPrev && !view.hasNext;
  $('my-tables-range').textContent = view.slice.length
    ? `${view.from + 1}–${view.to} de ${cache.items.length}${cache.next ? '+' : ''}` : '';
  $('my-tables-prev').disabled = tablesList.loading || !view.hasPrev;
  $('my-tables-next').disabled = tablesList.loading || !view.hasNext;
  $('my-tables-refresh').disabled = tablesList.loading;
}

function turnTablesPage(delta) {
  tablesList.page += delta;
  if (myTablesView().needsFetch) loadMyTables(true);
  else renderMyTables();
}

$('my-tables-prev').addEventListener('click', () => turnTablesPage(-1));
$('my-tables-next').addEventListener('click', () => turnTablesPage(1));
$('my-tables-refresh').addEventListener('click', () => loadMyTables());
$('tables-dialog').addEventListener('opened', async () => {
  tablesList.page = 0;
  $('my-tables-status').textContent = '';
  renderMyTables();
  const me = await identity().catch(() => null);
  if (!tablesCacheFresh(tablesList.cache, me?.identityKey, Date.now(), TABLES_TTL_MS)) loadMyTables();
});
$('table-dialog').addEventListener('opened', () => {
  $('table-error').textContent = '';
});

// ---------- Jogador ----------

let playerName = storedName();
let pendingRoll = false;

/** Avatar: inicial do apelido na cor da identidade (só exibição; a impressão vai escrita ao lado). */
function paintAvatar(el, name, fp) {
  el.textContent = initialOf(name);
  if (fp) el.style.setProperty('--p', playerColor(fp));
}

async function renderPlayer() {
  paintAvatar($('me-avatar'), playerName, null);
  $('me-btn').setAttribute('aria-label', `Você: ${playerName ?? 'sem apelido'}`);
  try {
    const me = await identity();
    paintAvatar($('me-avatar'), playerName, me.fingerprint);
    paintAvatar($('player-avatar'), playerName, me.fingerprint);
    $('player-fingerprint').textContent = me.fingerprint;
    $('player-device').textContent = me.links.length
      ? `vinculado · ${me.deviceFingerprint}` : `principal · ${me.deviceFingerprint}`;
    $('player-ephemeral').hidden = me.persistent;
  } catch (e) {
    $('player-fingerprint').textContent = '—';
    $('player-error').textContent = e.message;
  }
}

$('player-dialog').addEventListener('opened', () => {
  $('player-error').textContent = '';
  $('player-lead').textContent = pendingRoll
    ? 'Antes de rolar, escolha como a mesa vai te ver. Cada rolagem sai assinada por uma chave deste aparelho.'
    : 'Cada rolagem sai assinada por uma chave deste aparelho. O apelido aparece para a mesa; a impressão mostra que foi você, mesmo que outra pessoa use o mesmo apelido.';
  $('player-form').elements.name.value = playerName ?? '';
  renderPlayer();
});

$('player-dialog').addEventListener('close', () => {
  pendingRoll = false;
});

$('player-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const name = event.target.elements.name.value.trim();
  if (nameProblem(name)) {
    $('player-error').textContent = 'O apelido deve ter de 1 a 32 bytes, sem quebra de linha.';
    return;
  }
  playerName = name;
  saveName(name);
  renderPlayer();
  const rollAfter = pendingRoll;
  closeDialog($('player-dialog'));
  toast(`Você rola como ${name}`);
  if (rollAfter) roll();
});

// ---------- Outros aparelhos (seção 7.7 da spec) ----------

const PAIR_POLL_MS = 1500;
const runs = { pair: 0, join: 0 };

/** Consulta o pareamento até ready(p); para quando a folha fecha (outra rodada) ou o link expira. */
async function waitPairing(kind, run, pairingId, expiresAt, ready) {
  for (;;) {
    if (runs[kind] !== run) throw Object.assign(new Error('cancelado'), { cancelled: true });
    if (Date.now() > Date.parse(expiresAt)) throw new Error('O link expirou. Gere outro no aparelho que já rola.');
    const pairing = await getJson(`/api/pairings/${encodeURIComponent(pairingId)}`);
    if (ready(pairing)) return pairing;
    await sleep(PAIR_POLL_MS);
  }
}

$('pair-dialog').addEventListener('opened', async () => {
  const run = ++runs.pair;
  const say = (text) => {
    if (runs.pair === run) $('pair-status').textContent = text;
  };
  $('pair-qr').replaceChildren();
  $('pair-link').textContent = '';
  $('pair-copy').disabled = true;
  try {
    if (!playerName) throw new Error('Escolha seu apelido antes: o outro aparelho usa o mesmo.');
    say('Gerando o link…');
    await identity();
    const secret = newSecret();
    const [{ pairingId, expiresAt }, { qrcode }] = await Promise.all([
      postJson('/api/pairings', {}), import('./vendor/qrcode-generator-2.0.4.mjs')]);
    const url = pairingUrl(location.origin, pairingId, secret);
    const qr = qrcode(0, 'M');
    qr.addData(url);
    qr.make();
    $('pair-qr').replaceChildren(qrSvg(qr, 'QR com o link para vincular outro aparelho'));
    $('pair-link').textContent = url;
    $('pair-copy').disabled = false;
    $('pair-copy').onclick = () => copyText(url, 'Link copiado');
    say('Esperando o outro aparelho abrir o link…');
    const withDevice = await waitPairing('pair', run, pairingId, expiresAt, (p) => p.device);
    const body = await answerDevice({ pairing: withDevice, secret, name: playerName, linkDevice });
    await postJson(`/api/pairings/${encodeURIComponent(pairingId)}/identity`, body);
    say(`Pronto. Confirme no outro aparelho e ele rola como ${playerName}.`);
  } catch (e) {
    if (!e.cancelled) say(e.message);
  }
});
$('pair-dialog').addEventListener('close', () => {
  runs.pair += 1;
});

/** Abriu /#parear=…: este aparelho entra na identidade de quem gerou o link. */
async function joinFromLink() {
  if (!location.hash.startsWith('#parear=')) return;
  const parsed = parsePairingFragment(location.hash);
  // O segredo sai da barra de endereço logo de cara.
  history.replaceState(null, '', `${location.pathname}${location.search}`);
  const run = ++runs.join;
  const say = (text) => {
    if (runs.join === run) $('join-status').textContent = text;
  };
  $('join-who').hidden = true;
  $('join-warning').hidden = true;
  $('join-confirm').hidden = true;
  $('join-error').textContent = '';
  openDialog($('join-dialog'));
  try {
    if (!parsed) throw new Error('Link de vínculo inválido. Gere outro no aparelho que já rola.');
    const { pairingId, secret } = parsed;
    say('Conectando com o outro aparelho…');
    const me = await identity();
    const pairing = await getJson(`/api/pairings/${encodeURIComponent(pairingId)}`);
    await postJson(`/api/pairings/${encodeURIComponent(pairingId)}/device`,
      { deviceKey: me.publicKey, mac: await deviceMac(secret, pairingId, me.publicKey) });
    say('Esperando o outro aparelho autorizar…');
    const done = await waitPairing('join', run, pairingId, pairing.expiresAt, (p) => p.identity);
    const pkg = await acceptIdentity({ pairing: done, secret, deviceKey: me.publicKey });
    const fp = await fingerprint(pkg.identityKey);
    say('O outro aparelho autorizou. Confira e confirme:');
    $('join-who').textContent = `${pkg.name} · ${fp}`;
    $('join-who').hidden = false;
    if (pkg.identityKey !== me.identityKey && (playerName || me.links.length)) {
      $('join-warning').textContent = `Este aparelho deixa de rolar como ${playerName ?? 'antes'} · ${me.fingerprint}.`;
      $('join-warning').hidden = false;
    }
    $('join-confirm').hidden = false;
    $('join-confirm').onclick = async () => {
      try {
        await adoptIdentity(pkg);
        saveTables(null); // a lista era da identidade anterior
        playerName = pkg.name;
        saveName(pkg.name);
        await renderPlayer();
        closeDialog($('join-dialog'));
        toast(`Este aparelho rola como ${pkg.name} · ${fp}`);
      } catch (e) {
        $('join-error').textContent = e.message;
      }
    };
  } catch (e) {
    if (!e.cancelled) $('join-error').textContent = e.message;
  }
}
$('join-dialog').addEventListener('close', () => {
  runs.join += 1;
});
addEventListener('hashchange', joinFromLink);

// ---------- Escolha do dado ----------

function expression() {
  return buildExpression(state);
}

function renderChoice() {
  document.documentElement.style.setProperty('--c', DIE_COLORS[state.sides]);
  for (const button of $('picker').children) {
    const on = Number(button.dataset.sides) === state.sides;
    button.setAttribute('aria-checked', String(on));
    button.tabIndex = on ? 0 : -1;
  }
  $('count-value').textContent = String(state.count);
  $('mod-value').textContent = formatModifier(state.modifier) || '0';
  const expr = expression();
  $('roll-expr').textContent = expr;
  $('stage').setAttribute('aria-label', `Rolar ${expr}`);
  if (document.activeElement !== $('expression')) $('expression').value = expr;
}

/** Saiu do resultado anterior: volta os dados para o estado de escolha. */
function resetStage() {
  if (state.rolling) return;
  $('result').hidden = true;
  $('banner').hidden = true;
  $('roll-error').textContent = '';
  $('status-line').textContent = 'Escolha o dado e role';
  stageRing.mode('idle');
  tray.idle();
}

function choose({ sides = state.sides, count = state.count, modifier = state.modifier }) {
  if (state.rolling) return;
  const changedSides = sides !== state.sides;
  Object.assign(state, { sides, count, modifier });
  resetStage();
  tray.setCount(count);
  if (changedSides) tray.setSides(sides);
  renderChoice();
}

for (const sides of DIE_SIDES) {
  const button = h('button', {
    type: 'button', class: 'die-pick', role: 'radio', 'data-sides': sides, 'aria-label': `d${sides}`,
    onclick: () => choose({ sides }),
  }, dieIcon(sides), h('span', { class: 'die-pick-label', text: `d${sides}` }));
  button.style.setProperty('--c', DIE_COLORS[sides]);
  $('picker').append(button);
}

$('picker').addEventListener('keydown', (event) => {
  const delta = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.key];
  if (!delta) return;
  event.preventDefault();
  const i = (DIE_SIDES.indexOf(state.sides) + delta + DIE_SIDES.length) % DIE_SIDES.length;
  choose({ sides: DIE_SIDES[i] });
  $('picker').children[i].focus();
});

const LIMITS = { count: [1, 20], modifier: [-100, 100] };

function step(field, delta) {
  const [min, max] = LIMITS[field];
  const value = Math.min(max, Math.max(min, state[field] + delta));
  if (value !== state[field]) choose({ [field]: value });
}

for (const stepper of document.querySelectorAll('.stepper')) {
  const field = stepper.dataset.field;
  for (const button of stepper.querySelectorAll('.step')) {
    const delta = Number(button.dataset.step);
    let timer = null;
    const stop = () => {
      clearTimeout(timer);
      clearInterval(timer);
      timer = null;
    };
    // Segurar repete; o click só conta quando vem do teclado (detail 0).
    button.addEventListener('pointerdown', (event) => {
      if (event.button !== 0) return;
      step(field, delta);
      timer = setTimeout(() => {
        timer = setInterval(() => step(field, delta), 70);
      }, 380);
    });
    for (const type of ['pointerup', 'pointerleave', 'pointercancel']) button.addEventListener(type, stop);
    button.addEventListener('click', (event) => {
      if (event.detail === 0) step(field, delta);
    });
  }
}

$('expression').addEventListener('input', (event) => {
  try {
    const { count, sides, modifier } = parseExpression(event.target.value);
    event.target.setCustomValidity('');
    choose({ count, sides, modifier });
  } catch {
    event.target.setCustomValidity('Expressão inválida');
  }
});
$('expression').addEventListener('blur', renderChoice);

function newSeed() {
  $('seed').value = generateSeed();
  state.seedCustom = false;
}
$('seed-gen').addEventListener('click', newSeed);
$('seed').addEventListener('input', () => {
  state.seedCustom = true;
});

// ---------- Rolagem ----------

function setBusy(busy) {
  state.rolling = busy;
  document.body.classList.toggle('is-rolling', busy);
  $('roll-btn').disabled = busy;
  for (const b of document.querySelectorAll('.controls .step, .die-pick')) b.disabled = busy;
}

/** Anima o anel até o horário do round alvo e consulta o roll até resolver. */
async function waitForResult(receipt) {
  const target = Date.parse(receipt.expectedAt);
  const start = Date.now();
  const span = Math.max(1000, target - start);
  let done = false;
  const animate = () => {
    if (done) return;
    const left = Math.ceil((target - Date.now()) / 1000);
    stageRing.progress((Date.now() - start) / span);
    $('status-line').textContent = left > 0
      ? `O round #${receipt.targetRound} do drand sai em ${left} s. Esse número ainda não existe.`
      : `Buscando o round #${receipt.targetRound} no drand…`;
    requestAnimationFrame(animate);
  };
  requestAnimationFrame(animate);
  try {
    // Se o relógio local estiver muito adiantado/atrasado, não espera mais que 8 s para começar.
    await sleep(Math.min(Math.max(0, target - Date.now()) + 300, 8000));
    const giveUp = Date.now() + GIVE_UP_MS;
    for (;;) {
      const roll = await getJson(`/api/rolls/${receipt.rollId}`).catch((e) => {
        if (e.status === 404) throw e;
        return null; // falha passageira: tenta de novo
      });
      if (roll?.status === 'RESOLVED') return roll;
      if (roll?.status === 'EXPIRED' || Date.now() > giveUp) {
        const error = new Error('O drand está demorando. O pedido continua registrado; o resultado aparece na página da rolagem.');
        error.rollId = receipt.rollId;
        throw error;
      }
      await sleep(POLL_MS);
    }
  } finally {
    done = true;
  }
}

async function reveal(roll) {
  const { dice, total, modifier, round } = roll.result;
  stageRing.progress(1);
  stageRing.mode('done');
  $('status-line').textContent = 'O drand falou!';
  await tray.land(dice);

  celebrate($('stage'), $('banner'), criticalOf(roll.expression, dice));
  $('result').hidden = false;
  replay($('result'), 'is-in');
  countUp($('total'), total, Math.min(900, 300 + total * 8));
  $('breakdown').textContent = formatBreakdown(dice, modifier);
  $('status-line').textContent = `${roll.expression} · round #${round} do drand`;
  state.rollId = roll.rollId;
  runVerificationBadge($('verify-badge'), $('verify-dialog'), roll.rollId, verifyRoll);
}

async function roll() {
  if (state.rolling) return;
  let expr;
  try {
    expr = expression();
  } catch (e) {
    $('roll-error').textContent = e.message;
    return;
  }
  if (!playerName) {
    // Primeira rolagem: escolhe o apelido e a rolagem segue em seguida.
    pendingRoll = true;
    openDialog($('player-dialog'));
    return;
  }
  const clientSeed = $('seed').value;
  const name = playerName;
  resetStage();
  setBusy(true);
  $('verify-badge').hidden = true;
  $('status-line').textContent = 'Pedindo um round futuro ao drand…';
  stageRing.progress(0);
  stageRing.mode('waiting');
  tray.tumble();
  try {
    const { receipt, recreated, reason } = await requestRoll({
      sessionId,
      createTable: () => createTable(null),
      postRoll: async (table) => {
        // Assina para a mesa desta tentativa: a mesa entra na mensagem assinada.
        const player = await signRequest({ sessionId: table, clientSeed, expression: expr, name });
        return postJson(`/api/sessions/${encodeURIComponent(table)}/rolls`, { clientSeed, expression: expr, player });
      },
    });
    if (recreated) toast(RECREATED[reason]);
    bumpHistory();
    // O servidor também põe a mesa no topo da sua lista ao rolar (melhor esforço).
    changeTables((items) => upsertTable(items,
      { sessionId, lastActiveAt: new Date().toISOString(), ...(sessionName ? { name: sessionName } : {}) }));
    const result = await waitForResult(receipt);
    await reveal(result);
    refreshHistory();
  } catch (e) {
    tray.idle();
    stageRing.mode('idle');
    $('status-line').textContent = 'Escolha o dado e role';
    $('roll-error').replaceChildren(e.message);
    if (e.rollId) {
      $('roll-error').append(' ', h('a', { href: `/r/${e.rollId}`, text: 'Abrir a página da rolagem' }));
    }
  } finally {
    setBusy(false);
    if (!state.seedCustom) newSeed();
  }
}

$('roll-btn').addEventListener('click', roll);
// Clicar (ou Enter) no dado também rola; Espaço já rola pelo atalho global.
$('stage').addEventListener('click', () => {
  if (!document.querySelector('dialog[open]')) roll();
});
$('stage').addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' || event.repeat) return;
  event.preventDefault();
  roll();
});
document.addEventListener('keydown', (event) => {
  if (event.code !== 'Space' || event.repeat || document.querySelector('dialog[open]')) return;
  if (event.target.closest?.('input, textarea, select, button, summary, a, [contenteditable]')) return;
  event.preventDefault();
  roll();
});

$('share-btn').addEventListener('click', () => {
  if (state.rollId) shareLink(`${location.origin}/r/${state.rollId}`, `Rolagem ${$('roll-expr').textContent}`);
});

// ---------- Histórico ----------

let historyItems = [];
let historyError = null;

function bumpHistory() {
  renderHistoryCount(historyItems.length + 1);
}

function renderHistoryCount(n) {
  $('history-count').hidden = n === 0;
  $('history-count').textContent = n > 99 ? '99+' : String(n);
}

async function refreshHistory() {
  if (!sessionId) {
    historyItems = [];
    historyError = null;
    renderHistoryCount(0);
    renderHistory();
    return;
  }
  const id = sessionId;
  try {
    historyItems = await withFingerprints(historyFromLog(await fetchLog(id)));
    historyError = null;
  } catch (e) {
    if (e.status === 404 && id === sessionId) {
      // A mesa salva sumiu (ex.: DynamoDB Local zerado): esquece e a próxima rolagem cria outra.
      setTable(null);
      toast(MISSING_TABLE);
      return;
    }
    // Mantém o que já estava na tela: lista vazia passaria a ideia de que as rolagens sumiram.
    historyError = historyLoadError(e);
  }
  renderHistoryCount(historyItems.length);
  if ($('history-dialog').open) renderHistory();
}

/** Acrescenta a impressão da chave de cada jogador (SHA-256 é assíncrono na Web Crypto). */
async function withFingerprints(items) {
  // Entrada fora do formato (a verificação da mesa acusa) não pode esvaziar o histórico.
  const keys = [...new Set(items.map((r) => r.player?.identityKey).filter((k) => /^[0-9a-f]{64}$/.test(k ?? '')))];
  const prints = new Map(await Promise.all(keys.map(async (k) => [k, await fingerprint(k)])));
  for (const r of items) {
    if (r.player) r.player.fingerprint = prints.get(r.player.identityKey) ?? '?';
  }
  return items;
}

const STATUS_TEXT = { PENDING: 'aguardando o drand', EXPIRED: 'atrasado' };

function playerLine(player) {
  const avatar = h('span', { class: 'avatar avatar-sm', 'aria-hidden': 'true' });
  paintAvatar(avatar, player.name, player.fingerprint && player.fingerprint !== '?' ? player.fingerprint : null);
  return h('span', { class: 'h-player' }, avatar, h('span', { class: 'h-name', text: player.name }),
    h('code', { text: player.fingerprint ?? '' }));
}

function renderHistory() {
  $('history-failed').hidden = !historyError;
  $('history-failed-text').textContent = historyError ?? '';
  $('history-empty').hidden = historyItems.length > 0 || !!historyError;
  $('history-list').replaceChildren(...historyItems.map((r, i) => {
    let sides = 20;
    try {
      sides = parseExpression(r.expression).sides;
    } catch {
      // expressão do log sempre é válida; fica o d20 como ícone
    }
    const crit = r.dice ? criticalOf(r.expression, r.dice) : null;
    const item = h('li', { class: `history-item${crit ? ` is-${crit}` : ''}`, 'data-status': r.status },
      h('a', { href: `/r/${r.rollId}` },
        h('span', { class: 'h-die' }, dieIcon(sides)),
        h('span', { class: 'h-main' },
          h('strong', { text: r.expression }),
          h('span', { text: r.dice ? r.dice.join(' · ') : STATUS_TEXT[r.status] }),
          r.player ? playerLine(r.player) : null),
        h('span', { class: 'h-side' },
          h('span', { class: 'h-total', text: r.total ?? '…' }),
          h('span', { class: 'h-time', text: relativeTime(r.requestedAt) }))));
    item.style.setProperty('--i', String(Math.min(i, 12)));
    item.style.setProperty('--c', DIE_COLORS[sides]);
    return item;
  }));
}

$('history-retry').addEventListener('click', () => {
  $('history-failed-text').textContent = 'Carregando…';
  refreshHistory().then(renderHistory);
});

$('history-dialog').addEventListener('opened', () => {
  renderHistory();
  refreshHistory();
});

$('verify-table').addEventListener('click', async () => {
  if (!sessionId) {
    $('chain-result').textContent = 'Sem mesa ainda: role um dado primeiro.';
    return;
  }
  runTableCheck({ button: $('verify-table'), chain: $('chain'), out: $('chain-result') }, sessionId, verifyTable);
});

// ---------- Início ----------

setupDialogs();
renderPlayer();
joinFromLink();
newSeed();
adoptInvite();
renderTable();
renderTablesCount();
renderChoice();
refreshHistory();
// Fecha diálogos abertos ao sair da página pelo histórico do navegador.
addEventListener('pagehide', () => document.querySelectorAll('dialog[open]').forEach(closeDialog));
