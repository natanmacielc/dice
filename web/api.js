// Acesso à API e verificação no navegador, compartilhado pelas páginas.
import { describeError, runChecks, summarizeLog } from './page-logic.js';

export async function getJson(url) {
  const response = await fetch(url, { headers: { accept: 'application/json' } });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(describeError(body, response.status));
    error.status = response.status;
    throw error;
  }
  return body;
}

export async function postJson(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(describeError(json, response.status));
    error.status = response.status;
    throw error;
  }
  return json;
}

/** Log inteiro da mesa, página por página. */
export async function fetchLog(sessionId) {
  const entries = [];
  let after = 0;
  for (;;) {
    const page = await getJson(`/sessions/${encodeURIComponent(sessionId)}/log?after=${after}`);
    entries.push(...page.entries);
    if (page.next === null) return entries;
    after = page.next;
  }
}

// O drand-client (~150 KB) só carrega quando alguém verifica.
const verifiers = () => Promise.all([import('./verifier/chain-v1.js'), import('./verifier/drand-verify.js')]);

/** Baixa a prova e roda o checklist (cadeia, drand direto, BLS, round futuro). */
export async function verifyRoll(rollId) {
  const [{ verifyProof, timeOf }, { fetchRound, verifyBls }] = await verifiers();
  let proof;
  try {
    proof = await getJson(`/rolls/${encodeURIComponent(rollId)}/proof`);
  } catch (e) {
    return { proof: null, checks: [{ id: 'proof', label: 'Prova obtida do servidor', ok: false, detail: e.message }] };
  }
  return { proof, checks: await runChecks({ proof, verifyProof, fetchRound, verifyBls, timeOf }) };
}

/** Confere a cadeia inteira da mesa desde o genesis. */
export async function verifyTable(sessionId) {
  const { GENESIS_PREV_HASH, verifySegment } = await import('./verifier/chain-v1.js');
  const entries = await fetchLog(sessionId);
  const result = await verifySegment(entries, GENESIS_PREV_HASH);
  return { entries, result, summary: summarizeLog(entries) };
}
