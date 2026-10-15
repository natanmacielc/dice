// Pareamento de aparelhos (spec/ALGORITHM.md, seção 7.7). Sem DOM, testado no Node.
// O servidor só repassa dados públicos. O segredo vai no fragmento do link (#parear=...), que o
// navegador nunca envia, e autentica com HMAC-SHA256 (Web Crypto) os dois sentidos da troca.
import { canonicalJson } from './verifier/chain-v1.js';
import { linksProblem, linksValid, nameProblem } from './verifier/chain-v2.js';

const ID = /^[0-9a-hjkmnp-tv-z]{16}$/;
const SECRET = /^[0-9a-f]{32}$/;
const MAC = /^[0-9a-f]{64}$/;
const utf8 = new TextEncoder();

const toHex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
const fromHex = (hex) => Uint8Array.from(hex.match(/../g), (b) => parseInt(b, 16));

export function newSecret() {
  return toHex(globalThis.crypto.getRandomValues(new Uint8Array(16)));
}

/** Link que o aparelho novo abre. */
export function pairingUrl(origin, pairingId, secret) {
  return `${origin}/#parear=${pairingId}.${secret}`;
}

/** { pairingId, secret } a partir de location.hash, ou null. */
export function parsePairingFragment(hash) {
  const m = /^#parear=([^.]+)\.([^.&]+)$/.exec(String(hash ?? ''));
  if (!m || !ID.test(m[1]) || !SECRET.test(m[2])) return null;
  return { pairingId: m[1], secret: m[2] };
}

const hmacKey = (secret, usage) => globalThis.crypto.subtle.importKey(
  'raw', fromHex(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [usage],
);

const deviceData = (pairingId, deviceKey) => utf8.encode(`dice-pair-v1|device|${pairingId}|${deviceKey}`);
const identityData = (pairingId, { identityKey, links, name }) => utf8.encode(
  `dice-pair-v1|identity|${pairingId}|${canonicalJson({ identityKey, links, name })}`,
);

async function sign(secret, data) {
  return toHex(new Uint8Array(await globalThis.crypto.subtle.sign('HMAC', await hmacKey(secret, 'sign'), data)));
}

// verify da Web Crypto compara em tempo constante.
async function check(secret, data, mac) {
  if (typeof mac !== 'string' || !MAC.test(mac)) return false;
  return globalThis.crypto.subtle.verify('HMAC', await hmacKey(secret, 'verify'), fromHex(mac), data);
}

export const deviceMac = (secret, pairingId, deviceKey) => sign(secret, deviceData(pairingId, deviceKey));
export const identityMac = (secret, pairingId, pkg) => sign(secret, identityData(pairingId, pkg));

/**
 * Aparelho que já tem a identidade, ao ver o aparelho novo no pareamento: confere que a chave veio de
 * quem abriu o link e devolve o corpo de POST /api/pairings/{id}/identity. linkDevice assina o vínculo.
 */
export async function answerDevice({ pairing, secret, name, linkDevice }) {
  const device = pairing.device;
  if (!device || !(await check(secret, deviceData(pairing.pairingId, device.deviceKey), device.mac))) {
    throw new Error('A chave recebida não veio do aparelho que abriu o link. Gere um novo link.');
  }
  const { identityKey, links } = await linkDevice(device.deviceKey);
  const pkg = { identityKey, links, name };
  return { ...pkg, mac: await identityMac(secret, pairing.pairingId, pkg) };
}

/**
 * Aparelho novo, ao receber o pacote: confere o MAC e que os vínculos chegam à própria chave.
 * Devolve { identityKey, links, name } para adotar.
 */
export async function acceptIdentity({ pairing, secret, deviceKey }) {
  const pkg = pairing.identity;
  const fail = () => new Error('O vínculo recebido não confere. Gere um novo link no outro aparelho.');
  if (!pkg || typeof pkg.identityKey !== 'string' || linksProblem(pkg.links) || nameProblem(pkg.name)) throw fail();
  const { identityKey, links, name } = pkg;
  if (!(await check(secret, identityData(pairing.pairingId, { identityKey, links, name }), pkg.mac))) throw fail();
  if (pairing.device?.deviceKey !== deviceKey || !(await linksValid(identityKey, links, deviceKey))) throw fail();
  return { identityKey, links, name };
}
