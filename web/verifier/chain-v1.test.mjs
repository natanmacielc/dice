// Roda as seções chain-v1 de spec/test-vectors.json, as mesmas do Java e do Python.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { canonicalJson, ChainError, timeOf, verifyProof, verifySegment } from './chain-v1.js';

const vectors = JSON.parse(readFileSync(new URL('../../spec/test-vectors.json', import.meta.url), 'utf8'));

test('formato da cadeia', () => assert.equal(vectors.chainFormat, 'chain-v1'));

for (const v of vectors.canonicalJsonVectors) {
  test(`canônico: ${v.id}`, () => assert.equal(canonicalJson(v.value), v.canonical));
}

for (const v of vectors.logVectors) {
  test(`log: ${v.id}`, async () => {
    const r = await verifySegment(v.entries, v.prevHashOfFirst);
    assert.equal(r.valid, v.valid, r.message ?? '');
    if (!v.valid) assert.equal(r.code, v.error, r.message);
  });
}

for (const v of vectors.proofVectors) {
  test(`prova: ${v.id}`, async () => {
    const r = await verifyProof(v.proof);
    assert.equal(r.valid, v.valid, r.message ?? '');
    if (!v.valid) assert.equal(r.code, v.error, r.message);
  });
}

test('canonicalJson rejeita bool, null e float', () => {
  for (const bad of [true, null, 1.5, [1, null], { a: false }]) {
    assert.throws(() => canonicalJson(bad), (e) => e instanceof ChainError && e.code === 'CHAIN_STRUCTURE');
  }
});

test('timeOf do round 1 é o genesis', () => assert.equal(timeOf(1), 1692803367000));
