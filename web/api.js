// Acesso à API e verificação no navegador, compartilhado pelas páginas.
import { signTables } from './identity.js';
import { describeError, runChecks, summarizeLog } from './page-logic.js';

/** Sem resposta em timeoutMs, o fetch é abortado (e.g. conexão que nunca termina). */
function fetchWithTimeout(url, options, timeoutMs) {
  return fetch(url, timeoutMs ? { ...options, signal: AbortSignal.timeout(timeoutMs) } : options);
}

function apiError(body, status) {
  const error = new Error(describeError(body, status));
  error.status = status;
  error.code = body?.error ?? null;
  return error;
}

export async function getJson(url, { timeoutMs = null } = {}) {
  const response = await fetchWithTimeout(url, { headers: { accept: 'application/json' } }, timeoutMs);
  const body = await response.json().catch(() => null);
  if (!response.ok) throw apiError(body, response.status);
  return body;
}

export async function postJson(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await response.json().catch(() => null);
  if (!response.ok) throw apiError(json, response.status);
  return json;
}

/** Lista de mesas do jogador (seção 7.8): list, add ou remove, sempre assinado por este aparelho. */
export async function myTables(action, { sessionId = null, cursor = null } = {}) {
  const body = { auth: await signTables({ action, sessionId, cursor }), action };
  if (sessionId) body.sessionId = sessionId;
  if (cursor) body.cursor = cursor;
  return postJson('/me/tables', body);
}

const LOG_TIMEOUT_MS = 15_000;

/** Log inteiro da mesa, página por página, com o formato da cadeia. */
export async function fetchTable(sessionId) {
  const entries = [];
  let after = 0;
  for (;;) {
    const page = await getJson(`/sessions/${encodeURIComponent(sessionId)}/log?after=${after}`, { timeoutMs: LOG_TIMEOUT_MS });
    entries.push(...page.entries);
    if (page.next === null) return { entries, chainFormat: page.chainFormat };
    after = page.next;
  }
}

export async function fetchLog(sessionId) {
  return (await fetchTable(sessionId)).entries;
}

// Verificador do formato declarado. Formato desconhecido cai na chain-v2, que o recusa.
const chainVerifier = (format) => (format === 'chain-v1' ? import('./verifier/chain-v1.js') : import('./verifier/chain-v2.js'));

/** Baixa a prova e roda o checklist (cadeia, assinatura do jogador, drand direto, BLS, round futuro). */
export async function verifyRoll(rollId) {
  let proof;
  try {
    proof = await getJson(`/rolls/${encodeURIComponent(rollId)}/proof`);
  } catch (e) {
    return { proof: null, checks: [{ id: 'proof', label: 'Prova obtida do servidor', ok: false, detail: e.message }] };
  }
  // O drand-client (~150 KB) só carrega quando alguém verifica.
  const [{ verifyProof, timeOf }, { fetchRound, verifyBls }, { fingerprint }] = await Promise.all([
    chainVerifier(proof.chainFormat), import('./verifier/drand-verify.js'), import('./verifier/chain-v2.js')]);
  return { proof, checks: await runChecks({ proof, verifyProof, fetchRound, verifyBls, timeOf, fingerprint }) };
}

/** Confere a cadeia inteira da mesa desde o genesis, no formato dela. */
export async function verifyTable(sessionId) {
  const { entries, chainFormat } = await fetchTable(sessionId);
  const { GENESIS_PREV_HASH, verifySegment } = await chainVerifier(chainFormat);
  const result = await verifySegment(entries, GENESIS_PREV_HASH);
  return { entries, result, summary: summarizeLog(entries) };
}
