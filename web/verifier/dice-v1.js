// Verificador independente do Dado Verificável, algoritmo dice-v1 (spec/ALGORITHM.md).
// ES module sem dependências. Usa Web Crypto, então roda no navegador e no Node 22.

export const ALGORITHM = 'dice-v1';

const SEED_MAX_BYTES = 64;
const COUNT_MIN = 1;
const COUNT_MAX = 20;
const MAX_ABS_MODIFIER = 100;
const SUPPORTED_SIDES = new Set([4, 6, 8, 10, 12, 20, 100]);
const WORDS_PER_BLOCK = 8;
const TWO_32 = 2 ** 32;

const RANDOMNESS_RE = /^[0-9a-f]{64}$/;
const SIGNATURE_RE = /^(?:[0-9a-f]{2})+$/;
const EXPRESSION_RE =
  /^[ \t]*(0|[1-9][0-9]?)?[dD](0|[1-9][0-9]{0,2})(?:[ \t]*([+-])[ \t]*(0|[1-9][0-9]{0,2}))?[ \t]*$/;

const utf8 = new TextEncoder();

export class DiceError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.name = 'DiceError';
    this.code = code;
  }
}

export function parseExpression(text) {
  const m = typeof text === 'string' ? EXPRESSION_RE.exec(text) : null;
  if (m === null) throw new DiceError('INVALID_EXPRESSION', `expressão inválida: ${JSON.stringify(text)}`);
  const count = m[1] === undefined ? 1 : Number(m[1]);
  const sides = Number(m[2]);
  let modifier = m[3] === undefined ? 0 : Number(m[4]);
  if (m[3] === '-') modifier = -modifier;
  if (count < COUNT_MIN || count > COUNT_MAX) throw new DiceError('DICE_COUNT_OUT_OF_RANGE', `N fora de 1..20: ${count}`);
  if (!SUPPORTED_SIDES.has(sides)) throw new DiceError('UNSUPPORTED_DIE', `d${sides}`);
  if (Math.abs(modifier) > MAX_ABS_MODIFIER) throw new DiceError('MODIFIER_OUT_OF_RANGE', `|M| > 100: ${modifier}`);
  modifier = modifier === 0 ? 0 : modifier; // -0 vira 0
  let canonical = `${count}d${sides}`;
  if (modifier > 0) canonical += `+${modifier}`;
  if (modifier < 0) canonical += `-${-modifier}`;
  return { count, sides, modifier, canonical };
}

function checkRandomness(randomness) {
  if (typeof randomness !== 'string' || !RANDOMNESS_RE.test(randomness)) {
    throw new DiceError('RANDOMNESS_FORMAT', 'esperado 64 caracteres hex minúsculos');
  }
}

function encodeSeed(seed) {
  if (typeof seed !== 'string' || !seed.isWellFormed()) throw new DiceError('SEED_INVALID_UTF8', 'surrogate sem par');
  const bytes = utf8.encode(seed);
  if (bytes.length === 0) throw new DiceError('SEED_EMPTY', 'clientSeed vazia');
  if (bytes.length > SEED_MAX_BYTES) throw new DiceError('SEED_TOO_LONG', `${bytes.length} bytes`);
  for (const ch of seed) {
    const cp = ch.codePointAt(0);
    if (cp < 0x20 || cp === 0x7f) throw new DiceError('SEED_CONTROL_CHAR', 'caractere de controle');
  }
  return bytes;
}

function checkNonce(nonce) {
  if (!Number.isSafeInteger(nonce) || nonce < 0) {
    throw new DiceError('NONCE_OUT_OF_RANGE', `nonce fora de [0, 2^53-1]: ${nonce}`);
  }
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

async function sha256(bytes) {
  return new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', bytes));
}

function toHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function fromHex(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(2 * i, 2 * i + 2), 16);
  return out;
}

/** Valida na ordem da spec (randomness, clientSeed, nonce, expressão) e rola. */
export async function roll({ randomness, clientSeed, nonce, expression }) {
  checkRandomness(randomness);
  const seedBytes = encodeSeed(clientSeed);
  checkNonce(nonce);
  const expr = parseExpression(expression);

  const prefix = concat(utf8.encode(`${randomness}|`), seedBytes, utf8.encode(`|${nonce}|`));
  const limit = TWO_32 - (TWO_32 % expr.sides);
  const dice = [];
  const rejections = [];
  let block = null;
  let counter = 0;
  let index = 0;
  while (dice.length < expr.count) {
    const position = index % WORDS_PER_BLOCK;
    if (position === 0) block = new DataView((await sha256(concat(prefix, utf8.encode(String(counter++))))).buffer);
    const v = block.getUint32(4 * position, false); // big-endian, sem sinal
    if (v >= limit) rejections.push({ wordIndex: index, value: v });
    else dice.push((v % expr.sides) + 1);
    index++;
  }
  const total = dice.reduce((a, b) => a + b, 0) + expr.modifier;
  return { expression: expr.canonical, dice, modifier: expr.modifier, total, wordsConsumed: index, rejections };
}

/** randomness do drand = SHA-256(bytes da assinatura). Não verifica BLS. */
export async function randomnessMatchesSignature(signature, randomness) {
  checkRandomness(randomness);
  if (typeof signature !== 'string' || !SIGNATURE_RE.test(signature)) {
    throw new DiceError('SIGNATURE_FORMAT', 'assinatura deve ser hex minúsculo com tamanho par');
  }
  return toHex(await sha256(fromHex(signature))) === randomness;
}

/** Recalcula e compara com o declarado. Devolve { valid, reason }. */
export async function verifyRoll({ randomness, clientSeed, nonce, expression, signature, claimedDice, claimedTotal }) {
  let actual;
  try {
    if (signature !== undefined && !(await randomnessMatchesSignature(signature, randomness))) {
      return { valid: false, reason: 'randomness não é SHA-256(signature)' };
    }
    actual = await roll({ randomness, clientSeed, nonce, expression });
  } catch (e) {
    if (e instanceof DiceError) return { valid: false, reason: `entrada inválida: ${e.message}` };
    throw e;
  }
  if (claimedDice.length !== actual.dice.length || claimedDice.some((d, i) => d !== actual.dice[i])) {
    return { valid: false, reason: `dados declarados [${claimedDice}] != recalculados [${actual.dice}]` };
  }
  if (claimedTotal !== actual.total) {
    return { valid: false, reason: `total declarado ${claimedTotal} != recalculado ${actual.total}` };
  }
  return { valid: true, reason: null };
}
