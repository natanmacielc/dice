import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  buildExpression, criticalOf, formatBreakdown, describeError, drandRoundUrl, explainRoll, formatModifier, generateSeed, historyFromLog,
  parseRollInput, relativeTime, runChecks, secondsUntil, shellQuote, summarizeLog,
} from './page-logic.js';
import { timeOf, verifyProof } from './verifier/chain-v1.js';
import { roll } from './verifier/dice-v1.js';

const vectors = JSON.parse(readFileSync(new URL('../spec/test-vectors.json', import.meta.url), 'utf8'));
const validProof = vectors.proofVectors.find((v) => v.id === 'proof-valid-adjacent').proof;

test('mensagens de erro em português pelo código da API', () => {
  assert.equal(describeError({ error: 'UNSUPPORTED_DIE' }, 400), 'Dado não suportado. Use d4, d6, d8, d10, d12, d20 ou d100.');
  assert.equal(describeError({ error: 'X', message: 'm' }, 500), 'm');
  assert.equal(describeError(null, 502), 'Erro inesperado (HTTP 502).');
});

test('formatação', () => {
  assert.equal(formatBreakdown([17], 0), '');
  assert.equal(formatBreakdown([4, 2], 0), '4 + 2');
  assert.equal(formatBreakdown([4, 2], -1), '4 + 2 − 1');
  assert.equal(formatBreakdown([12], 5), '12 + 5');
  assert.equal(formatModifier(3), '+3');
  assert.equal(formatModifier(-2), '-2');
  assert.equal(formatModifier(0), '');
  assert.equal(secondsUntil('2026-01-01T00:00:05.000Z', Date.parse('2026-01-01T00:00:00.500Z')), 5);
  assert.equal(secondsUntil('2026-01-01T00:00:05.000Z', Date.parse('2026-01-01T00:00:09Z')), 0);
});

test('seed gerada tem 16 caracteres base32 Crockford', () => {
  assert.match(generateSeed(), /^[0-9a-hjkmnp-tv-z]{16}$/);
  assert.equal(generateSeed((n) => new Uint8Array(n)), '0'.repeat(16));
});

const fetchOk = async () => ({ beacon: { ...validProof.beacon }, endpoint: 'https://api.drand.sh' });

test('checklist todo verde para uma prova válida', async () => {
  const checks = await runChecks({ proof: validProof, verifyProof, fetchRound: fetchOk, verifyBls: async () => true, timeOf });
  assert.deepEqual(checks.map((c) => [c.id, c.ok]), [['proof', true], ['drand', true], ['bls', true], ['future', true]]);
});

test('checklist acusa drand divergente, drand fora, BLS inválido e prova adulterada', async () => {
  const other = async () => ({ beacon: { ...validProof.beacon, randomness: '0'.repeat(64) }, endpoint: 'https://api2.drand.sh' });
  let checks = await runChecks({ proof: validProof, verifyProof, fetchRound: other, verifyBls: async () => false, timeOf });
  assert.deepEqual(checks.map((c) => c.ok), [true, false, false, true]);

  const down = async () => { throw new Error('round indisponível'); };
  checks = await runChecks({ proof: validProof, verifyProof, fetchRound: down, verifyBls: async () => true, timeOf });
  assert.equal(checks[1].ok, false);
  assert.equal(checks[1].detail, 'round indisponível');

  const tampered = vectors.proofVectors.find((v) => v.id === 'proof-summary-total').proof;
  checks = await runChecks({ proof: tampered, verifyProof, fetchRound: fetchOk, verifyBls: async () => true, timeOf });
  assert.equal(checks[0].ok, false);
  assert.match(checks[0].detail, /^PROOF_SUMMARY_MISMATCH/);
});

test('resumo do log separa pendentes', () => {
  const entries = vectors.logVectors.find((v) => v.id === 'log-valid-full').entries;
  assert.deepEqual(summarizeLog(entries), { entries: 9, requests: 3, resolved: 3, pending: [] });
  assert.deepEqual(summarizeLog(entries.slice(0, 2)).pending.length, 2);
});

test('expressão montada pelo seletor sai canônica e respeita os limites', () => {
  assert.equal(buildExpression({ count: 1, sides: 20 }), '1d20');
  assert.equal(buildExpression({ count: 2, sides: 6, modifier: 3 }), '2d6+3');
  assert.equal(buildExpression({ count: 4, sides: 100, modifier: -100 }), '4d100-100');
  assert.throws(() => buildExpression({ count: 21, sides: 6 }), /DICE_COUNT_OUT_OF_RANGE/);
  assert.throws(() => buildExpression({ count: 1, sides: 7 }), /UNSUPPORTED_DIE/);
  assert.throws(() => buildExpression({ count: 1, sides: 6, modifier: 101 }), /MODIFIER_OUT_OF_RANGE/);
});

test('rollId a partir de ID ou de link', () => {
  assert.equal(parseRollInput('  0123456789ABCDEF '), '0123456789abcdef');
  assert.equal(parseRollInput('https://dices.nimbos.solutions/r/0123456789abcdef'), '0123456789abcdef');
  assert.equal(parseRollInput('/r/0123456789abcdef?x=1'), '0123456789abcdef');
  assert.equal(parseRollInput('0123456789abcdeu'), null); // u não é Crockford
  assert.equal(parseRollInput('/r/0123456789abcdef0'), null);
  assert.equal(parseRollInput(''), null);
});

test('histórico vem do log, do mais novo para o mais antigo, com pendentes e atrasados', () => {
  const entries = vectors.logVectors.find((v) => v.id === 'log-valid-full').entries;
  const history = historyFromLog(entries, Date.parse('2030-01-01T00:00:00Z'));
  assert.deepEqual(history.map((h) => h.nonce), [3, 2, 1]);
  assert.ok(history.every((h) => h.status === 'RESOLVED' && Array.isArray(h.dice) && Number.isInteger(h.total)));

  const requestOnly = entries.filter((e) => e.type === 'REQUEST').slice(0, 1);
  const target = timeOf(requestOnly[0].payload.targetRound);
  assert.equal(historyFromLog(requestOnly, target + 59_000)[0].status, 'PENDING');
  assert.equal(historyFromLog(requestOnly, target + 61_000)[0].status, 'EXPIRED');
});

test('crítico e falha crítica só em d20', () => {
  assert.equal(criticalOf('1d20', [20]), 'crit');
  assert.equal(criticalOf('2d20+5', [3, 20]), 'crit');
  assert.equal(criticalOf('1d20', [1]), 'fumble');
  assert.equal(criticalOf('2d20', [1, 7]), null);
  assert.equal(criticalOf('1d6', [6]), null);
  assert.equal(criticalOf('1d20', null), null);
});

test('tempo relativo e aspas de shell', () => {
  const now = Date.parse('2026-01-01T12:00:00Z');
  assert.equal(relativeTime('2026-01-01T11:59:50Z', now), 'agora');
  assert.equal(relativeTime('2026-01-01T11:57:00Z', now), 'há 3 min');
  assert.equal(relativeTime('2026-01-01T10:00:00Z', now), 'há 2 h');
  assert.equal(relativeTime('2025-12-31T12:00:00Z', now), 'há 1 dia');
  assert.equal(shellQuote("it's"), "'it'\\''s'");
  assert.equal(drandRoundUrl(7), 'https://api.drand.sh/52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971/public/7');
});

for (const v of vectors.rollVectors) {
  test(`passo a passo bate com roll(): ${v.id}`, async () => {
    const actual = await roll(v);
    const e = await explainRoll(v);
    assert.deepEqual(e.dice, actual.dice);
    assert.equal(e.total, actual.total);
    assert.equal(e.words.length, actual.wordsConsumed);
    assert.deepEqual(e.words.filter((w) => w.rejected).map((w) => ({ wordIndex: w.index, value: w.value })), actual.rejections);
    assert.equal(e.blocks[0].input, `${v.randomness}|${v.clientSeed}|${v.nonce}|0`);
    for (const w of e.words) assert.equal(parseInt(w.hex, 16), w.value);
  });
}
