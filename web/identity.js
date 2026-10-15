// Identidade do jogador (chain-v2, spec/ALGORITHM.md seção 7). Cada aparelho tem a sua chave Ed25519,
// criada e guardada neste navegador (não exportável, nunca sai daqui). A identidade é a chave raiz do
// jogador: no primeiro aparelho, a própria chave; nos vinculados, a de quem os vinculou (seção 7.7).
import { canonicalJson } from './verifier/chain-v1.js';
import { fingerprint, linkMessage, MAX_LINKS, nameProblem, playerMessage } from './verifier/chain-v2.js';

export { nameProblem };

const DB_NAME = 'dice-identity';
const STORE = 'keys';
const KEY_ID = 'player';
const NAME_KEY = 'dice.playerName';
export const TABLES_CONTEXT = 'dice-tables-v1';
export const UNSUPPORTED = 'Este navegador não assina com Ed25519, que identifica quem rolou. Atualize o navegador para rolar.';

const toHex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

function done(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function withStore(mode, fn) {
  const open = globalThis.indexedDB.open(DB_NAME, 1);
  open.onupgradeneeded = () => open.result.createObjectStore(STORE);
  const db = await done(open);
  try {
    return await done(fn(db.transaction(STORE, mode).objectStore(STORE)));
  } finally {
    db.close();
  }
}

async function generate() {
  let pair;
  try {
    pair = await globalThis.crypto.subtle.generateKey({ name: 'Ed25519' }, false, ['sign', 'verify']);
  } catch {
    throw new Error(UNSUPPORTED);
  }
  const raw = new Uint8Array(await globalThis.crypto.subtle.exportKey('raw', pair.publicKey));
  const publicKey = toHex(raw);
  return { privateKey: pair.privateKey, publicKey, identityKey: publicKey, links: [] };
}

async function describe(stored, persistent) {
  // Registros antigos (antes dos vínculos) não têm identityKey: o aparelho é a própria identidade.
  const record = { identityKey: stored.publicKey, links: [], ...stored };
  return {
    ...record,
    fingerprint: await fingerprint(record.identityKey),
    deviceFingerprint: await fingerprint(record.publicKey),
    persistent,
  };
}

/** Lê a chave do IndexedDB ou cria uma. Sem IndexedDB (ex.: Node, modo restrito), a chave vale só nesta aba. */
async function load() {
  let stored = null;
  let persistent = true;
  try {
    stored = await withStore('readonly', (s) => s.get(KEY_ID));
  } catch {
    persistent = false;
  }
  if (!stored) {
    stored = await generate();
    if (persistent) {
      try {
        await withStore('readwrite', (s) => s.add(stored, KEY_ID));
      } catch {
        // Outra aba gravou primeiro: vale a dela, para o aparelho ter uma chave só.
        stored = await withStore('readonly', (s) => s.get(KEY_ID)).catch(() => null) ?? stored;
      }
    }
  }
  return describe(stored, persistent);
}

let current = null;

/**
 * { privateKey, publicKey, identityKey, links, fingerprint (da identidade), deviceFingerprint, persistent }.
 * A chave nasce no primeiro uso.
 */
export function identity() {
  current ??= load().catch((e) => {
    current = null;
    throw e;
  });
  return current;
}

/** Passa a rolar como a identidade de outro aparelho. Os vínculos já foram conferidos por quem chama. */
export async function adoptIdentity({ identityKey, links }) {
  const me = await identity();
  const record = { privateKey: me.privateKey, publicKey: me.publicKey, identityKey, links };
  if (me.persistent) await withStore('readwrite', (s) => s.put(record, KEY_ID));
  current = describe(record, me.persistent);
  return current;
}

/** Vínculo de outro aparelho a esta identidade, assinado por este aparelho (o último da cadeia). */
export async function linkDevice(deviceKey) {
  const me = await identity();
  if (me.links.length >= MAX_LINKS) throw new Error(`Esta identidade já passou por ${MAX_LINKS} aparelhos em cadeia.`);
  const message = linkMessage({ identityKey: me.identityKey, deviceKey });
  const signature = new Uint8Array(await globalThis.crypto.subtle.sign({ name: 'Ed25519' }, me.privateKey, message));
  return { identityKey: me.identityKey, links: [...me.links, { deviceKey, signature: toHex(signature) }] };
}

export function storedName() {
  try {
    return globalThis.localStorage?.getItem(NAME_KEY) ?? null;
  } catch {
    return null;
  }
}

export function saveName(name) {
  try {
    globalThis.localStorage?.setItem(NAME_KEY, name);
  } catch {
    // sem localStorage: o apelido vale só nesta aba
  }
}

/** Mensagem da lista de mesas (seção 7.8): JSON canônico, campos ausentes ficam de fora. */
export function tablesMessage({ action, identityKey, at, sessionId = null, cursor = null }) {
  const m = { context: TABLES_CONTEXT, action, identityKey, at };
  if (sessionId) m.sessionId = sessionId;
  if (cursor) m.cursor = cursor;
  return new TextEncoder().encode(canonicalJson(m));
}

/** auth de POST /me/tables, assinado por este aparelho (o servidor aceita até 5 min de diferença no relógio). */
export async function signTables({ action, sessionId = null, cursor = null, now = new Date() }) {
  const { privateKey, publicKey, identityKey, links } = await identity();
  const at = now.toISOString(); // sempre com milissegundos: 2026-10-15T12:00:00.000Z
  const message = tablesMessage({ action, identityKey, at, sessionId, cursor });
  const signature = new Uint8Array(await globalThis.crypto.subtle.sign({ name: 'Ed25519' }, privateKey, message));
  return { publicKey, identityKey, links, at, signature: toHex(signature) };
}

/** Assina o pedido (seção 7.2). expression tem de estar na forma canônica. Devolve o player do POST. */
export async function signRequest({ sessionId, clientSeed, expression, name }) {
  const { privateKey, publicKey, identityKey, links } = await identity();
  const requestId = toHex(globalThis.crypto.getRandomValues(new Uint8Array(16)));
  const message = playerMessage({ sessionId, clientSeed, expression, identityKey, name, requestId });
  const signature = new Uint8Array(await globalThis.crypto.subtle.sign({ name: 'Ed25519' }, privateKey, message));
  return { publicKey, name, requestId, signature: toHex(signature), identityKey, links };
}
