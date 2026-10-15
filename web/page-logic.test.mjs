import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describeError, formatModifier, generateSeed, runChecks, secondsUntil, summarizeLog } from './page-logic.js';
import { timeOf, verifyProof } from './verifier/chain-v1.js';

const vectors = JSON.parse(readFileSync(new URL('../spec/test-vectors.json', import.meta.url), 'utf8'));
const validProof = vectors.proofVectors.find((v) => v.id === 'proof-valid-adjacent').proof;

test('mensagens de erro em português pelo código da API', () => {
  assert.equal(describeError({ error: 'UNSUPPORTED_DIE' }, 400), 'Dado não suportado. Use d4, d6, d8, d10, d12, d20 ou d100.');
  assert.equal(describeError({ error: 'X', message: 'm' }, 500), 'm');
  assert.equal(describeError(null, 502), 'Erro inesperado (HTTP 502).');
});

test('formatação', () => {
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
