// Roda as seções chain-v2 de spec/test-vectors.json, as mesmas do Java e do Python.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  fingerprint, linkMessage, nameProblem, playerMessage, verifyEd25519, verifyProof, verifySegment,
} from './chain-v2.js';

const vectors = JSON.parse(readFileSync(new URL('../../spec/test-vectors.json', import.meta.url), 'utf8'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');

for (const v of vectors.playerSignatureVectors) {
  test(`assinatura: ${v.id}`, async () => {
    if (v.request) {
      const built = playerMessage(v.request);
      assert.equal(new TextDecoder().decode(built), v.message);
      assert.equal(hex(built), v.messageHex);
    }
    assert.equal(await verifyEd25519(v.publicKey, Buffer.from(v.messageHex, 'hex'), v.signature), v.valid);
  });
}

for (const v of vectors.linkSignatureVectors) {
  test(`vínculo: ${v.id}`, async () => {
    const built = linkMessage(v.link);
    assert.equal(new TextDecoder().decode(built), v.message);
    assert.equal(hex(built), v.messageHex);
    assert.equal(await verifyEd25519(v.issuerKey, built, v.signature), v.valid);
  });
}

for (const v of vectors.logVectorsV2) {
  test(`log v2: ${v.id}`, async () => {
    const r = await verifySegment(v.entries, v.prevHashOfFirst);
    assert.equal(r.valid, v.valid, r.message ?? '');
    if (!v.valid) assert.equal(r.code, v.error, r.message);
  });
}

for (const v of vectors.proofVectorsV2) {
  test(`prova v2: ${v.id}`, async () => {
    const r = await verifyProof(v.proof);
    // proof-v2-declared-v1 é uma prova chain-v1: este módulo só aceita chain-v2.
    if (v.proof.chainFormat === 'chain-v1') {
      assert.equal(r.code, 'PROOF_STRUCTURE');
      return;
    }
    assert.equal(r.valid, v.valid, r.message ?? '');
    if (!v.valid) assert.equal(r.code, v.error, r.message);
  });
}

test('impressão = 4 primeiros bytes de SHA-256(chave)', async () => {
  const key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a';
  const digest = hex(await crypto.subtle.digest('SHA-256', Buffer.from(key, 'hex'))).slice(0, 8);
  assert.equal(await fingerprint(key), `${digest.slice(0, 4)}-${digest.slice(4)}`);
});

test('apelido segue as regras da seed com 32 bytes', () => {
  assert.equal(nameProblem('Ana'), null);
  assert.equal(nameProblem('é'.repeat(16)), null);
  assert.notEqual(nameProblem('é'.repeat(16) + 'a'), null);
  assert.notEqual(nameProblem(''), null);
  assert.notEqual(nameProblem('a\tb'), null);
  assert.notEqual(nameProblem('a\u007f'), null);
  assert.notEqual(nameProblem('a\uD800'), null);
});
