// Sem IndexedDB no Node: a chave vale só no processo, como numa aba sem armazenamento.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identity, signRequest } from './identity.js';
import { linksValid, playerMessage, playerProblem, verifyEd25519 } from './verifier/chain-v2.js';

test('assina um pedido que o verificador aceita', async () => {
  const request = { sessionId: 'kq1t9m3x7c2a8z5r', clientSeed: 'mesa|"x"', expression: '2d6+3', name: 'Thorin 🪓' };
  const player = await signRequest(request);
  assert.equal(playerProblem(player), null);
  assert.equal(await linksValid(player.identityKey, player.links, player.publicKey), true);
  const message = playerMessage({ ...request, identityKey: player.identityKey, requestId: player.requestId });
  assert.equal(await verifyEd25519(player.publicKey, message, player.signature), true);
  const renamed = playerMessage({ ...request, name: 'Outro', identityKey: player.identityKey, requestId: player.requestId });
  assert.equal(await verifyEd25519(player.publicKey, renamed, player.signature), false);
});

test('primeiro aparelho: a identidade é a própria chave; requestId novo a cada pedido', async () => {
  const request = { sessionId: 'kq1t9m3x7c2a8z5r', clientSeed: 's', expression: '1d20', name: 'Ana' };
  const [a, b] = [await signRequest(request), await signRequest(request)];
  assert.equal(a.publicKey, b.publicKey);
  assert.notEqual(a.requestId, b.requestId);
  const id = await identity();
  assert.equal(id.identityKey, id.publicKey);
  assert.deepEqual(id.links, []);
  assert.equal(id.persistent, false);
  assert.match(id.fingerprint, /^[0-9a-f]{4}-[0-9a-f]{4}$/);
});
