// Lógica das páginas sem DOM (testável no Node).

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
};

export function describeError(body, status) {
  if (body && ERROR_MESSAGES[body.error]) return ERROR_MESSAGES[body.error];
  return body?.message ?? `Erro inesperado (HTTP ${status}).`;
}

export function formatDice(dice) {
  return dice.join(' · ');
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
export async function runChecks({ proof, verifyProof, fetchRound, verifyBls, timeOf }) {
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
