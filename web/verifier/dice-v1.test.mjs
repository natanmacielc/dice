// Roda spec/test-vectors.json, os mesmos vetores do Java e do Python.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DiceError, parseExpression, randomnessMatchesSignature, roll, verifyRoll } from './dice-v1.js';

const vectors = JSON.parse(readFileSync(new URL('../../spec/test-vectors.json', import.meta.url), 'utf8'));

test('cabeçalho e mínimo de 30 vetores', () => {
  assert.equal(vectors.algorithm, 'dice-v1');
  const total = vectors.beaconVectors.length + vectors.rollVectors.length + vectors.errorVectors.length;
  assert.ok(total >= 30, `vetores: ${total}`);
});

for (const v of vectors.beaconVectors) {
  test(`beacon: ${v.id}`, async () => {
    assert.equal(await randomnessMatchesSignature(v.signature, v.randomness), v.valid);
  });
}

for (const v of vectors.rollVectors) {
  test(`roll: ${v.id}`, async () => {
    const seedHex = Array.from(new TextEncoder().encode(v.clientSeed), (b) => b.toString(16).padStart(2, '0')).join('');
    assert.equal(seedHex, v.clientSeedUtf8Hex);
    const r = await roll(v);
    assert.deepEqual(r, v.expected);
    const ok = await verifyRoll({ ...v, claimedDice: r.dice, claimedTotal: r.total });
    assert.deepEqual(ok, { valid: true, reason: null });
  });
}

for (const v of vectors.errorVectors) {
  test(`erro: ${v.id}`, async () => {
    await assert.rejects(roll(v), (e) => e instanceof DiceError && e.code === v.error);
  });
}

test('-0 vira expressão sem modificador', () => {
  assert.equal(parseExpression('2d6-0').canonical, '2d6');
  assert.ok(Object.is(parseExpression('2d6-0').modifier, 0));
});

test('verifyRoll rejeita total adulterado e assinatura adulterada', async () => {
  const v = vectors.rollVectors[0];
  const r = await roll(v);
  assert.equal((await verifyRoll({ ...v, claimedDice: r.dice, claimedTotal: r.total + 1 })).valid, false);
  const b = vectors.beaconVectors.find((x) => x.valid);
  const tampered = (b.signature[0] === 'a' ? 'b' : 'a') + b.signature.slice(1);
  const res = await verifyRoll({ ...v, randomness: b.randomness, signature: tampered, claimedDice: [], claimedTotal: 0 });
  assert.deepEqual(res, { valid: false, reason: 'randomness não é SHA-256(signature)' });
});
