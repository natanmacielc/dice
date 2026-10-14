// Teste de fumaça do scaffold: garante que node:test roda e que SHA-256 está disponível.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

test('SHA-256("abc") bate com o vetor do FIPS 180', () => {
  assert.equal(
    createHash('sha256').update('abc', 'utf8').digest('hex'),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  );
});
