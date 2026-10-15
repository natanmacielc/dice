// Lógica das páginas sem DOM (testável no Node).
import { parseExpression } from './verifier/dice-v1.js';
import { QUICKNET, timeOf } from './verifier/chain-v1.js';

const ERROR_MESSAGES = {
  SEED_EMPTY: 'A seed não pode ser vazia.',
  SEED_TOO_LONG: 'A seed passa de 64 bytes.',
  SEED_CONTROL_CHAR: 'A seed tem caractere de controle (ex.: quebra de linha).',
  SEED_INVALID_UTF8: 'A seed não é texto UTF-8 válido.',
  INVALID_EXPRESSION: 'Expressão inválida. Use algo como 2d6+3.',
  DICE_COUNT_OUT_OF_RANGE: 'Quantidade de dados deve ser de 1 a 20.',
  UNSUPPORTED_DIE: 'Dado não suportado. Use d4, d6, d8, d10, d12, d20 ou d100.',
  MODIFIER_OUT_OF_RANGE: 'O modificador deve ficar entre -100 e +100.',
  NOT_FOUND: 'Mesa ou rolagem não encontrada.',
  BEACON_UNAVAILABLE: 'O drand não respondeu agora. Tente de novo em alguns segundos.',
  LEDGER_BUSY: 'Mesa ocupada. Tente de novo.',
  INVALID_REQUEST: 'Pedido inválido.',
  PLAYER_INVALID: 'Apelido ou chave do jogador em formato inválido.',
  PLAYER_SIGNATURE_INVALID: 'A assinatura do pedido não confere.',
  TABLE_READ_ONLY: 'Esta mesa é antiga, sem identificação de quem rola, e não aceita rolagens novas.',
  REQUEST_REPLAY: 'Este pedido já foi registrado.',
  AUTH_EXPIRED: 'O relógio deste aparelho está fora da hora certa. Acerte o relógio para ver suas mesas.',
  AUTH_INVALID: 'O servidor não aceitou a assinatura deste aparelho.',
  SERVER_BUSY: 'O servidor está no limite agora. Tente de novo em instantes.',
};

/** Rótulo de uma mesa: o nome, ou "Mesa" com os 4 primeiros caracteres do ID. */
export function tableLabel({ name, sessionId }) {
  return name?.trim() || `Mesa ${sessionId.slice(0, 4)}`;
}

/** Junta a próxima página da lista de mesas, sem repetir mesa que mudou de posição entre as páginas. */
export function mergeTables(current, page) {
  const seen = new Set(current.map((t) => t.sessionId));
  return [...current, ...page.filter((t) => !seen.has(t.sessionId))];
}

/** Põe a mesa no topo da lista (criou, entrou ou rolou neste aparelho), mantendo o que já se sabia dela. */
export function upsertTable(items, table) {
  const old = items.find((t) => t.sessionId === table.sessionId);
  return [{ ...old, ...table }, ...items.filter((t) => t.sessionId !== table.sessionId)];
}

/**
 * Página da tela sobre as mesas já carregadas. Com hasMore (o servidor tem cursor), a página
 * incompleta pede a próxima página do servidor (needsFetch); sem, a página fica dentro da lista.
 */
export function tablesPage(items, page, size, hasMore) {
  const last = Math.max(0, Math.ceil(items.length / size) - 1);
  const p = hasMore ? Math.max(0, page) : Math.min(Math.max(0, page), last);
  const from = Math.min(p * size, items.length);
  const to = Math.min(items.length, p * size + size);
  return {
    slice: items.slice(from, to), page: p, from, to,
    hasPrev: p > 0,
    hasNext: to < items.length || hasMore,
    needsFetch: hasMore && to - p * size < size,
  };
}

/** O cache das suas mesas serve sem pedir ao servidor: mesma identidade e buscado há menos de ttlMs. */
export function tablesCacheFresh(cache, identityKey, nowMs, ttlMs) {
  if (!cache || !identityKey || cache.identityKey !== identityKey || !Array.isArray(cache.items)) return false;
  const age = nowMs - cache.fetchedAt;
  return age >= 0 && age < ttlMs;
}

/**
 * Cor do jogador, a partir da impressão da identidade ("3f9a-c2e1"): matiz dos 2 primeiros bytes,
 * saturação e luminosidade fixas para o tema escuro. Só exibição; a impressão continua escrita ao lado.
 */
export function playerColor(fingerprint) {
  const hue = Math.round((parseInt(String(fingerprint).slice(0, 4), 16) / 65536) * 360);
  return Number.isNaN(hue) ? 'hsl(0 0% 70%)' : `hsl(${hue} 80% 68%)`;
}

/** Inicial do apelido: o primeiro grafema (emoji inteiro, letra com acento), em maiúscula. */
export function initialOf(name) {
  const text = String(name ?? '').trim();
  if (!text) return '?';
  const [first] = new Intl.Segmenter('pt-BR', { granularity: 'grapheme' }).segment(text);
  return first.segment.toLocaleUpperCase('pt-BR');
}

/** Texto para quando o histórico da mesa não carrega (rede, servidor ou tempo esgotado). */
export function historyLoadError(error) {
  if (error?.name === 'TimeoutError' || error?.name === 'AbortError') {
    return 'O servidor demorou demais para mandar o histórico.';
  }
  if (error?.status) return `Não consegui carregar o histórico: ${error.message}`;
  return 'Não consegui carregar o histórico. Confira a conexão.';
}

export function describeError(body, status) {
  if (body && ERROR_MESSAGES[body.error]) return ERROR_MESSAGES[body.error];
  // 403 sem corpo da API vem do WAF (limite por IP ou IP de má reputação): o plano Free do CloudFront
  // não deixa trocar o código do bloqueio. 429 fica para o caso de o plano mudar.
  if (!body && (status === 403 || status === 429)) return 'Muitos pedidos deste endereço. Espere alguns minutos e tente de novo.';
  return body?.message ?? `Erro inesperado (HTTP ${status}).`;
}

/**
 * Hex do SHA-256 do corpo (bytes UTF-8), para o header x-amz-content-sha256: com OAC, o CloudFront
 * assina o POST para a Function URL usando esse hash, e sem ele a origem recusa o pedido.
 */
export async function contentSha256(text) {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

export function formatDice(dice) {
  return dice.join(' · ');
}

/** "4 + 2 − 1" para mostrar sob o total; vazio quando é um dado só sem bônus. */
export function formatBreakdown(dice, modifier = 0) {
  const parts = dice.length > 1 || modifier ? dice.join(' + ') : '';
  if (!modifier) return parts;
  return `${parts} ${modifier > 0 ? '+' : '−'} ${Math.abs(modifier)}`;
}

export function formatModifier(m) {
  if (m > 0) return `+${m}`;
  if (m < 0) return `${m}`;
  return '';
}

export function secondsUntil(iso, nowMs) {
  return Math.max(0, Math.ceil((Date.parse(iso) - nowMs) / 1000));
}

const CROCKFORD = '0123456789abcdefghjkmnpqrstvwxyz';

/** Seed aleatória de 16 caracteres (80 bits), gerada no navegador. */
export function generateSeed(random = (n) => globalThis.crypto.getRandomValues(new Uint8Array(n))) {
  return Array.from(random(16), (b) => CROCKFORD[b & 31]).join('');
}

/**
 * Checklist da página /r/{id}. Cada item: { id, label, ok, detail }.
 * As dependências entram por parâmetro para testar sem rede.
 */
export async function runChecks({ proof, verifyProof, fetchRound, verifyBls, timeOf, fingerprint }) {
  const target = proof.roll.targetRound;
  const checks = [];

  const p = await verifyProof(proof);
  checks.push({
    id: 'proof',
    label: 'Cadeia íntegra e resultado recalculado neste navegador',
    ok: p.valid,
    detail: p.valid
      ? `dados ${formatDice(proof.roll.dice)} · total ${proof.roll.total}`
      : `${p.code}: ${p.message}`,
  });

  // Só a chain-v2 atesta quem rolou; a assinatura é conferida dentro de verifyProof.
  if (proof.chainFormat === 'chain-v2') {
    const player = proof.roll?.player;
    const fp = typeof player?.identityKey === 'string' && /^[0-9a-f]{64}$/.test(player.identityKey)
      ? await fingerprint(player.identityKey) : '?';
    const via = Array.isArray(player?.links) && player.links.length ? ', por um aparelho vinculado' : '';
    checks.push({
      id: 'player',
      label: `Pedido assinado por ${player?.name ?? '?'}`,
      ok: p.valid,
      detail: p.valid ? `identidade ${fp}${via}: ninguém mais poderia ter feito este pedido` : 'a assinatura ou a prova não confere',
    });
  }

  try {
    const { beacon, endpoint } = await fetchRound(target);
    const same = beacon.randomness === proof.beacon.randomness && beacon.signature === proof.beacon.signature;
    checks.push({
      id: 'drand',
      label: `Round ${target} buscado direto no drand`,
      ok: same,
      detail: same ? `confere com ${new URL(endpoint).host}` : 'o drand devolveu outro valor para este round',
    });
  } catch (e) {
    checks.push({ id: 'drand', label: `Round ${target} buscado direto no drand`, ok: false, detail: e.message });
  }

  const bls = await verifyBls(proof.beacon, target);
  checks.push({
    id: 'bls',
    label: 'Assinatura BLS do drand válida (chave pública do quicknet)',
    ok: bls,
    detail: bls ? 'assinatura do round confere' : 'assinatura inválida para este round',
  });

  const marginMs = timeOf(target) - Date.parse(proof.roll.requestedAt);
  checks.push({
    id: 'future',
    label: 'O round alvo ainda não existia quando o pedido foi feito',
    ok: marginMs > 0,
    detail: marginMs > 0
      ? `pedido ${(marginMs / 1000).toFixed(1)} s antes do round ser publicado`
      : 'o pedido é posterior ao round alvo',
  });
  return checks;
}

/** Resumo do log de uma mesa: entradas, pedidos e pendentes (REQUEST sem RESULT). */
export function summarizeLog(entries) {
  const requested = new Set();
  const resolved = new Set();
  for (const e of entries) {
    if (e.type === 'REQUEST') requested.add(e.payload.rollId);
    if (e.type === 'RESULT') resolved.add(e.payload.rollId);
  }
  const pending = [...requested].filter((id) => !resolved.has(id));
  return { entries: entries.length, requests: requested.size, resolved: resolved.size, pending };
}

/** Expressão canônica a partir do seletor. Lança DiceError se sair dos limites. */
export function buildExpression({ count, sides, modifier = 0 }) {
  let text = `${count}d${sides}`;
  if (modifier > 0) text += `+${modifier}`;
  if (modifier < 0) text += `-${-modifier}`;
  return parseExpression(text).canonical;
}

const ROLL_ID = /^[0-9a-hjkmnp-tv-z]{16}$/;
const ROLL_PATH = /\/r\/([0-9a-hjkmnp-tv-z]{16})(?:[/?#]|$)/;

/** rollId a partir de um ID puro ou de um link /r/{id}. null se não reconhecer. */
export function parseRollInput(text) {
  const t = String(text ?? '').trim().toLowerCase();
  if (ROLL_ID.test(t)) return t;
  return ROLL_PATH.exec(t)?.[1] ?? null;
}

/**
 * Pede o roll na mesa salva. Se ela não existe mais (404, ex.: DynamoDB Local zerado) ou é
 * antiga e só leitura (409 TABLE_READ_ONLY), cria outra e tenta de novo uma vez.
 * Devolve { receipt, sessionId, recreated } e, quando recria, reason ('missing' ou 'read-only').
 */
export async function requestRoll({ sessionId, createTable, postRoll }) {
  if (!sessionId) {
    const created = await createTable();
    return { receipt: await postRoll(created), sessionId: created, recreated: false };
  }
  try {
    return { receipt: await postRoll(sessionId), sessionId, recreated: false };
  } catch (e) {
    if (e.status !== 404 && e.code !== 'TABLE_READ_ONLY') throw e;
    const created = await createTable();
    const reason = e.code === 'TABLE_READ_ONLY' ? 'read-only' : 'missing';
    return { receipt: await postRoll(created), sessionId: created, recreated: true, reason };
  }
}

/** Pendente há mais de 60 s depois do round alvo: só exibição, não é estado gravado. */
const EXPIRED_AFTER_MS = 60_000;

/**
 * Histórico da mesa a partir do log público, do mais novo para o mais antigo.
 * Inclui pendentes e atrasados (invariante 7).
 */
export function historyFromLog(entries, nowMs = Date.now()) {
  const rolls = new Map();
  for (const e of entries) {
    const p = e.payload;
    if (e.type === 'REQUEST') {
      rolls.set(p.rollId, {
        rollId: p.rollId, expression: p.expression, nonce: p.nonce, clientSeed: p.clientSeed,
        targetRound: p.targetRound, requestedAt: p.requestedAt, status: 'PENDING', dice: null, total: null,
        player: p.player ? { name: p.player.name, identityKey: p.player.identityKey, publicKey: p.player.publicKey } : null,
      });
    } else if (e.type === 'RESULT' && rolls.has(p.rollId)) {
      Object.assign(rolls.get(p.rollId), { status: 'RESOLVED', dice: p.dice, total: p.total });
    }
  }
  const list = [...rolls.values()];
  for (const r of list) {
    if (r.status === 'PENDING' && nowMs - timeOf(r.targetRound) > EXPIRED_AFTER_MS) r.status = 'EXPIRED';
  }
  return list.sort((a, b) => b.nonce - a.nonce);
}

/** Crítico de d20: algum 20 natural é 'crit'; todos os dados em 1 é 'fumble'. */
export function criticalOf(expression, dice) {
  if (!dice?.length || parseExpression(expression).sides !== 20) return null;
  if (dice.includes(20)) return 'crit';
  if (dice.every((d) => d === 1)) return 'fumble';
  return null;
}

/** Tempo relativo curto em português ("agora", "há 3 min", "há 2 h", "há 4 dias"). */
export function relativeTime(iso, nowMs = Date.now()) {
  const s = Math.max(0, Math.round((nowMs - Date.parse(iso)) / 1000));
  if (s < 45) return 'agora';
  if (s < 3600) return `há ${Math.max(1, Math.round(s / 60))} min`;
  if (s < 86400) return `há ${Math.round(s / 3600)} h`;
  const d = Math.round(s / 86400);
  return d === 1 ? 'há 1 dia' : `há ${d} dias`;
}

/** Aspas simples para shell POSIX: 'it'\''s'. */
export function shellQuote(text) {
  return `'${String(text).replaceAll("'", "'\\''")}'`;
}

const encoder = new TextEncoder();

/**
 * Passo a passo da conta do dice-v1, só para EXIBIR (texto de cada bloco, hash, palavras).
 * A fonte da verdade é roll() em verifier/dice-v1.js: quem exibe compara os dois.
 */
export async function explainRoll({ randomness, clientSeed, nonce, expression }) {
  const { count, sides, modifier, canonical } = parseExpression(expression);
  const limit = 2 ** 32 - (2 ** 32 % sides);
  const blocks = [];
  const words = [];
  const dice = [];
  let view = null;
  for (let index = 0; dice.length < count; index++) {
    const position = index % 8;
    if (position === 0) {
      const input = `${randomness}|${clientSeed}|${nonce}|${blocks.length}`;
      const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', encoder.encode(input)));
      blocks.push({ counter: blocks.length, input, hash: Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('') });
      view = new DataView(digest.buffer);
    }
    const block = blocks.length - 1;
    const value = view.getUint32(4 * position, false);
    const hex = blocks[block].hash.slice(8 * position, 8 * position + 8);
    const rejected = value >= limit;
    const die = rejected ? null : (value % sides) + 1;
    if (!rejected) dice.push(die);
    words.push({ index, block, position, hex, value, rejected, die });
  }
  const total = dice.reduce((a, b) => a + b, 0) + modifier;
  return { expression: canonical, sides, modifier, limit, blocks, words, dice, total };
}

/** Link do round direto no drand (endpoint principal). */
export function drandRoundUrl(round) {
  return `https://api.drand.sh/${QUICKNET.chainHash}/public/${round}`;
}
