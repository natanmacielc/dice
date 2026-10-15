// Verificador da chain-v2 (spec/ALGORITHM.md, seção 7): a chain-v1 com o player assinado em
// Ed25519 em todo REQUEST, pelo aparelho, ligado à identidade do jogador por vínculos.
// ES module sem dependências; usa Web Crypto e o verificador chain-v1.

import { canonicalJson, ChainError, checkEntry, GENESIS_PREV_HASH, QUICKNET, timeOf, verifyProof as verifyProofV1 } from './chain-v1.js';

export { GENESIS_PREV_HASH, timeOf };
export const CHAIN_FORMAT = 'chain-v2';
export const PLAYER_CONTEXT = 'dice-player-v1';
export const LINK_CONTEXT = 'dice-device-v1';
export const MAX_NAME_BYTES = 32;
export const MAX_LINKS = 8;

const KEY = /^[0-9a-f]{64}$/;
const REQUEST_ID = /^[0-9a-f]{32}$/;
const SIGNATURE = /^[0-9a-f]{128}$/;
const PLAYER_FIELDS = ['identityKey', 'links', 'name', 'publicKey', 'requestId', 'signature'];
const LINK_FIELDS = ['deviceKey', 'signature'];
const utf8 = new TextEncoder();

const isPlainObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);

function fromHex(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(2 * i, 2 * i + 2), 16);
  return out;
}

const toHex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

/** Regras do apelido: as da clientSeed (seção 3.2), com no máximo 32 bytes. null se estiver ok. */
export function nameProblem(name) {
  if (typeof name !== 'string') return 'ausente';
  if (!name.isWellFormed()) return 'com surrogate sem par';
  const bytes = utf8.encode(name).length;
  if (bytes === 0 || bytes > MAX_NAME_BYTES) return `deve ter de 1 a ${MAX_NAME_BYTES} bytes UTF-8`;
  if (/[\u0000-\u001f\u007f]/.test(name)) return 'com caractere de controle';
  return null;
}

const sameKeys = (o, fields) => isPlainObject(o) && Object.keys(o).sort().join() === fields.join();

/** Formato da lista de vínculos (seção 7.2), ou null. */
export function linksProblem(links) {
  if (!Array.isArray(links) || links.length > MAX_LINKS) return `links deve ser uma lista de 0 a ${MAX_LINKS} vínculos`;
  const ok = (l) => sameKeys(l, LINK_FIELDS) && typeof l.deviceKey === 'string' && KEY.test(l.deviceKey)
    && typeof l.signature === 'string' && SIGNATURE.test(l.signature);
  return links.every(ok) ? null : 'links: cada vínculo tem exatamente deviceKey (64 hex) e signature (128 hex)';
}

/** Primeiro problema de formato do player (seção 7.2), ou null. */
export function playerProblem(player) {
  if (!sameKeys(player, PLAYER_FIELDS)) {
    return 'player deve ter exatamente publicKey, name, requestId, signature, identityKey e links';
  }
  if (typeof player.publicKey !== 'string' || !KEY.test(player.publicKey)) return 'publicKey deve ter 64 hex minúsculos';
  const n = nameProblem(player.name);
  if (n) return `name ${n}`;
  if (typeof player.requestId !== 'string' || !REQUEST_ID.test(player.requestId)) return 'requestId deve ter 32 hex minúsculos';
  if (typeof player.signature !== 'string' || !SIGNATURE.test(player.signature)) return 'signature deve ter 128 hex minúsculos';
  if (typeof player.identityKey !== 'string' || !KEY.test(player.identityKey)) return 'identityKey deve ter 64 hex minúsculos';
  return linksProblem(player.links);
}

/** Bytes assinados pelo aparelho: utf8(canonical({clientSeed, context, expression, identityKey, name, requestId, sessionId})). */
export function playerMessage({ sessionId, clientSeed, expression, identityKey, name, requestId }) {
  return utf8.encode(canonicalJson({ context: PLAYER_CONTEXT, sessionId, clientSeed, expression, identityKey, name, requestId }));
}

/** Bytes de um vínculo: utf8(canonical({context, deviceKey, identityKey})), assinado pela chave anterior da cadeia. */
export function linkMessage({ identityKey, deviceKey }) {
  return utf8.encode(canonicalJson({ context: LINK_CONTEXT, identityKey, deviceKey }));
}

/**
 * Os vínculos ligam identityKey a endKey? O primeiro é assinado pela identidade, cada seguinte pelo
 * deviceKey do anterior; sem vínculos, identityKey tem de ser endKey. Supõe o formato já conferido.
 */
export async function linksValid(identityKey, links, endKey) {
  let issuer = identityKey;
  for (const l of links) {
    if (!(await verifyEd25519(issuer, linkMessage({ identityKey, deviceKey: l.deviceKey }), l.signature))) return false;
    issuer = l.deviceKey;
  }
  return issuer === endKey;
}

/** Ed25519 pela Web Crypto. Chave fora da curva ou assinatura recusada dão false. */
export async function verifyEd25519(publicKeyHex, message, signatureHex) {
  try {
    const key = await globalThis.crypto.subtle.importKey('raw', fromHex(publicKeyHex), { name: 'Ed25519' }, false, ['verify']);
    return await globalThis.crypto.subtle.verify({ name: 'Ed25519' }, key, fromHex(signatureHex), message);
  } catch {
    return false;
  }
}

/** Impressão para exibição (seção 7.5): 4 primeiros bytes de SHA-256(chave), como "3f9a-c2e1". */
export async function fingerprint(publicKeyHex) {
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', fromHex(publicKeyHex)));
  const hex = toHex(digest.slice(0, 4));
  return `${hex.slice(0, 4)}-${hex.slice(4)}`;
}

// Checagens 6 a 9 da seção 7.3, num REQUEST.
async function checkPlayer(e, seen) {
  const p = e.payload;
  const problem = playerProblem(p.player);
  if (problem) throw new ChainError('CHAIN_STRUCTURE', `REQUEST seq ${e.seq}: ${problem}`);
  if (typeof p.sessionId !== 'string' || typeof p.clientSeed !== 'string' || typeof p.expression !== 'string') {
    throw new ChainError('CHAIN_STRUCTURE', `REQUEST seq ${e.seq} sem sessionId, clientSeed ou expression`);
  }
  const { publicKey, name, requestId, signature, identityKey, links } = p.player;
  if (!(await linksValid(identityKey, links, publicKey))) {
    throw new ChainError('CHAIN_PLAYER_SIGNATURE', `vínculos do aparelho à identidade inválidos no REQUEST seq ${e.seq}`);
  }
  const message = playerMessage({
    sessionId: p.sessionId, clientSeed: p.clientSeed, expression: p.expression, identityKey, name, requestId,
  });
  if (!(await verifyEd25519(publicKey, message, signature))) {
    throw new ChainError('CHAIN_PLAYER_SIGNATURE', `assinatura do jogador inválida no REQUEST seq ${e.seq}`);
  }
  if (seen.has(requestId)) throw new ChainError('CHAIN_REQUEST_REPLAY', `requestId repetido no REQUEST seq ${e.seq}`);
  seen.add(requestId);
}

async function checkSegment(entries, prevHashOfFirst) {
  const seen = new Set();
  let previous = null;
  for (const e of entries) {
    await checkEntry(e, previous, prevHashOfFirst);
    if (e.type === 'REQUEST') await checkPlayer(e, seen);
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

/** Seção 7.3. prevHashOfFirst: 64 zeros se o segmento começa no seq 1. */
export function verifySegment(entries, prevHashOfFirst = GENESIS_PREV_HASH) {
  return outcome(checkSegment(entries, prevHashOfFirst));
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
    throw new ChainError('PROOF_STRUCTURE', 'cabeçalho da prova inválido');
  }
  // 2. Segmento pela seção 7.3
  const entries = proof.entries;
  await checkSegment(entries, isPlainObject(entries[0]) ? entries[0].prevHash : GENESIS_PREV_HASH);
  // 3 a 8: iguais aos da chain-v1 (o segmento já passou, então a v1 refaz só as checagens dela)
  const v1 = await verifyProofV1({ ...proof, chainFormat: 'chain-v1' });
  if (!v1.valid) throw new ChainError(v1.code, v1.message.replace(`${v1.code}: `, ''));
  // 8, por último: o player do resumo
  if (proof.roll.player === undefined || !sameCanonical(proof.roll.player, entries[0].payload.player)) {
    throw new ChainError('PROOF_SUMMARY_MISMATCH', 'campo player do resumo não bate');
  }
}

/** Seção 7.4. Devolve { valid, code, message }. */
export function verifyProof(proof) {
  return outcome(checkProof(proof));
}
