// Verificador independente da cadeia chain-v1 e da prova de um roll (spec/ALGORITHM.md, seção 6).
// ES module sem dependências; usa Web Crypto e o verificador dice-v1.

import { DiceError, parseExpression, randomnessMatchesSignature, roll } from './dice-v1.js';

export const CHAIN_FORMAT = 'chain-v1';
export const QUICKNET = Object.freeze({
  network: 'quicknet',
  chainHash: '52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971',
  genesisTime: 1692803367,
  periodSeconds: 3,
});
export const GENESIS_PREV_HASH = '0'.repeat(64);

const TYPES = new Set(['REQUEST', 'ROUND', 'RESULT']);
const HEX64 = /^[0-9a-f]{64}$/;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const utf8 = new TextEncoder();

export class ChainError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.name = 'ChainError';
    this.code = code;
  }
}

const isPlainObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);

function escapeString(s) {
  if (!s.isWellFormed()) throw new ChainError('CHAIN_STRUCTURE', 'string com surrogate sem par');
  let out = '"';
  for (const ch of s) {
    const c = ch.codePointAt(0);
    if (ch === '"') out += '\\"';
    else if (ch === '\\') out += '\\\\';
    else if (c === 0x08) out += '\\b';
    else if (c === 0x09) out += '\\t';
    else if (c === 0x0a) out += '\\n';
    else if (c === 0x0c) out += '\\f';
    else if (c === 0x0d) out += '\\r';
    else if (c < 0x20) out += `\\u${c.toString(16).padStart(4, '0')}`;
    else out += ch;
  }
  return `${out}"`;
}

/** JSON canônico (seção 6.3). Lança ChainError(CHAIN_STRUCTURE) para tipos proibidos. */
export function canonicalJson(value) {
  if (typeof value === 'string') return escapeString(value);
  if (typeof value === 'number') {
    // JSON.parse não distingue 1.0 de 1; floats com parte fracionária são rejeitados.
    if (!Number.isSafeInteger(value)) throw new ChainError('CHAIN_STRUCTURE', `número não permitido: ${value}`);
    return Object.is(value, -0) ? '0' : String(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (isPlainObject(value)) {
    const keys = Object.keys(value).sort(); // ordem por unidades UTF-16
    return `{${keys.map((k) => `${escapeString(k)}:${canonicalJson(value[k])}`).join(',')}}`;
  }
  throw new ChainError('CHAIN_STRUCTURE', `tipo não permitido: ${value === null ? 'null' : typeof value}`);
}

async function sha256Hex(text) {
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', utf8.encode(text)));
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** hash = SHA-256(utf8(prevHash) || utf8(type) || utf8(canonical(payload))) */
export async function entryHash(prevHash, type, payload) {
  return sha256Hex(prevHash + type + canonicalJson(payload));
}

/** Instante de publicação do round, em milissegundos desde a epoch. */
export function timeOf(round) {
  return (QUICKNET.genesisTime + (round - 1) * QUICKNET.periodSeconds) * 1000;
}

/** Checagens 1 a 5 da seção 6.4 para uma entrada. A chain-v2 reaproveita. */
export async function checkEntry(e, previous, prevHashOfFirst) {
  // 1. Estrutura
  if (!isPlainObject(e) || !Number.isSafeInteger(e.seq) || e.seq < 1 || !TYPES.has(e.type)
    || typeof e.prevHash !== 'string' || !HEX64.test(e.prevHash)
    || typeof e.hash !== 'string' || !HEX64.test(e.hash) || !isPlainObject(e.payload)) {
    throw new ChainError('CHAIN_STRUCTURE', `entrada mal formada (seq ${e?.seq})`);
  }
  const canonical = canonicalJson(e.payload);
  // 2. Contiguidade
  if (previous !== null && e.seq !== previous.seq + 1) {
    throw new ChainError('CHAIN_SEQ_GAP', `seq ${e.seq} depois de ${previous.seq}`);
  }
  // 3. Seq no payload
  if (e.payload.seq !== e.seq) throw new ChainError('CHAIN_SEQ_MISMATCH', `payload.seq != seq ${e.seq}`);
  // 4. Encadeamento
  const expectedPrev = previous === null ? prevHashOfFirst : previous.hash;
  if (e.prevHash !== expectedPrev) throw new ChainError('CHAIN_LINK_BROKEN', `prevHash da seq ${e.seq} não encadeia`);
  // 5. Hash
  if ((await sha256Hex(e.prevHash + e.type + canonical)) !== e.hash) {
    throw new ChainError('CHAIN_HASH_MISMATCH', `hash da seq ${e.seq} não confere`);
  }
}

async function checkSegment(entries, prevHashOfFirst) {
  let previous = null;
  for (const e of entries) {
    await checkEntry(e, previous, prevHashOfFirst);
    previous = e;
  }
}

function outcome(promise) {
  return promise.then(
    () => ({ valid: true, code: null, message: null }),
    (e) => {
      if (e instanceof ChainError) return { valid: false, code: e.code, message: e.message };
      throw e;
    },
  );
}

/** Seção 6.4. prevHashOfFirst: 64 zeros se o segmento começa no seq 1. */
export function verifySegment(entries, prevHashOfFirst = GENESIS_PREV_HASH) {
  return outcome(checkSegment(entries, prevHashOfFirst));
}

const structure = (msg) => new ChainError('PROOF_STRUCTURE', msg);

function str(payload, key) {
  if (typeof payload[key] !== 'string') throw structure(`campo ${key} ausente ou não-string`);
  return payload[key];
}

function int(payload, key) {
  if (!Number.isSafeInteger(payload[key])) throw structure(`campo ${key} ausente ou não-inteiro`);
  return payload[key];
}

function sameCanonical(a, b) {
  try {
    return canonicalJson(a) === canonicalJson(b);
  } catch {
    return false;
  }
}

async function checkProof(proof) {
  // 1. Cabeçalho
  if (!isPlainObject(proof) || proof.algorithm !== 'dice-v1' || proof.chainFormat !== CHAIN_FORMAT
    || !isPlainObject(proof.drand) || proof.drand.network !== QUICKNET.network
    || proof.drand.chainHash !== QUICKNET.chainHash || !Array.isArray(proof.entries) || proof.entries.length === 0) {
    throw structure('cabeçalho da prova inválido');
  }
  const entries = proof.entries;
  // 2. Segmento (prevHash esperado = o da própria primeira entrada)
  await checkSegment(entries, isPlainObject(entries[0]) ? entries[0].prevHash : GENESIS_PREV_HASH);

  // 3. Forma da prova
  const request = entries[0];
  const result = entries[entries.length - 1];
  if (request.type !== 'REQUEST' || result.type !== 'RESULT') throw structure('deve ir do REQUEST ao RESULT');
  const rollId = str(request.payload, 'rollId');
  const sessionId = str(request.payload, 'sessionId');
  let round = null;
  entries.forEach((e, i) => {
    if (str(e.payload, 'sessionId') !== sessionId) throw structure(`seq ${e.seq} de outra mesa`);
    if (str(e.payload, 'rollId') !== rollId) return;
    if (e.type === 'REQUEST' && i !== 0) throw structure('REQUEST repetido');
    if (e.type === 'RESULT' && i !== entries.length - 1) throw structure('RESULT fora do fim');
    if (e.type === 'ROUND') {
      if (round !== null) throw structure('mais de um ROUND');
      round = e;
    }
  });
  if (round === null) throw structure('sem ROUND do roll');
  if (str(result.payload, 'rollId') !== rollId) throw structure('RESULT final é de outro roll');
  const req = request.payload;
  if (str(req, 'algorithm') !== 'dice-v1' || str(req, 'drandChainHash') !== QUICKNET.chainHash) {
    throw structure('algorithm ou drandChainHash inesperado');
  }
  const clientSeed = str(req, 'clientSeed');
  const expression = str(req, 'expression');
  const nonce = int(req, 'nonce');
  const targetRound = int(req, 'targetRound');
  const requestedAtText = str(req, 'requestedAt');
  const requestedAt = Date.parse(requestedAtText);
  if (!INSTANT.test(requestedAtText) || Number.isNaN(requestedAt)) throw structure('requestedAt fora do formato');
  let canonicalExpression;
  try {
    canonicalExpression = parseExpression(expression).canonical;
  } catch (e) {
    if (e instanceof DiceError) throw structure(e.message);
    throw e;
  }
  if (canonicalExpression !== expression) throw structure('expressão fora da forma canônica');
  const rnd = round.payload;
  const roundNumber = int(rnd, 'round');
  const randomness = str(rnd, 'randomness');
  const signature = str(rnd, 'signature');
  const res = result.payload;
  if (!Array.isArray(res.dice) || !res.dice.every(Number.isSafeInteger)) throw structure('dice inválido');
  const modifier = int(res, 'modifier');
  const total = int(res, 'total');

  // 4. Round alvo
  if (roundNumber !== targetRound) {
    throw new ChainError('PROOF_WRONG_ROUND', `ROUND ${roundNumber} != targetRound ${targetRound}`);
  }
  // 5. Beacon
  let beaconOk;
  try {
    beaconOk = await randomnessMatchesSignature(signature, randomness);
  } catch (e) {
    if (!(e instanceof DiceError)) throw e;
    beaconOk = false;
  }
  if (!beaconOk) throw new ChainError('PROOF_BEACON_INVALID', 'randomness != SHA-256(signature)');
  // 6. Pedido antes do round alvo
  if (targetRound < 1 || !(requestedAt < timeOf(targetRound))) {
    throw new ChainError('PROOF_TARGET_NOT_FUTURE', 'requestedAt não é anterior ao round alvo');
  }
  // 7. Resultado
  let actual;
  try {
    actual = await roll({ randomness, clientSeed, nonce, expression });
  } catch (e) {
    if (e instanceof DiceError) throw structure(e.message);
    throw e;
  }
  if (!sameCanonical(actual.dice, res.dice) || actual.modifier !== modifier || actual.total !== total) {
    throw new ChainError('PROOF_RESULT_MISMATCH', `recalculado [${actual.dice}] total ${actual.total}`);
  }
  // 8. Resumos
  if (!isPlainObject(proof.beacon) || !isPlainObject(proof.roll)) {
    throw new ChainError('PROOF_SUMMARY_MISMATCH', 'resumos ausentes');
  }
  const checks = [
    [proof.beacon, rnd, ['round', 'randomness', 'signature']],
    [proof.roll, req, ['rollId', 'sessionId', 'nonce', 'clientSeed', 'expression', 'targetRound', 'requestedAt']],
    [proof.roll, res, ['dice', 'modifier', 'total']],
  ];
  for (const [summary, source, fields] of checks) {
    for (const f of fields) {
      if (summary[f] === undefined || !sameCanonical(summary[f], source[f])) {
        throw new ChainError('PROOF_SUMMARY_MISMATCH', `campo ${f} do resumo não bate`);
      }
    }
  }
}

/** Seção 6.5. Devolve { valid, code, message }. */
export function verifyProof(proof) {
  return outcome(checkProof(proof));
}
