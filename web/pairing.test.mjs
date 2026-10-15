// Pareamento entre dois aparelhos simulados: A é o identity.js deste processo, B é uma chave avulsa.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { adoptIdentity, identity, linkDevice, signRequest } from './identity.js';
import { acceptIdentity, answerDevice, deviceMac, newSecret, pairingUrl, parsePairingFragment } from './pairing.js';
import { linksValid, playerMessage, playerProblem, verifyEd25519 } from './verifier/chain-v2.js';

const ID = 'abcdefghjkmnpqrs';
const hex = (bytes) => Buffer.from(bytes).toString('hex');

async function newDevice() {
  const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, false, ['sign', 'verify']);
  return { privateKey: pair.privateKey, publicKey: hex(await crypto.subtle.exportKey('raw', pair.publicKey)) };
}

test('link do pareamento: só aceita id de 16 caracteres e segredo de 32 hex', () => {
  const secret = newSecret();
  const url = pairingUrl('https://dices.nimbos.solutions', ID, secret);
  assert.deepEqual(parsePairingFragment(new URL(url).hash), { pairingId: ID, secret });
  for (const bad of ['', '#parear=', `#parear=${ID}`, `#parear=${ID}.xyz`, `#parear=ABC.${secret}`, `#mesa=${ID}.${secret}`]) {
    assert.equal(parsePairingFragment(bad), null, bad);
  }
});

test('fluxo completo: B passa a rolar como a identidade de A', async () => {
  const a = await identity();
  const b = await newDevice();
  const secret = newSecret();
  const pairing = { pairingId: ID, device: { deviceKey: b.publicKey, mac: await deviceMac(secret, ID, b.publicKey) } };

  pairing.identity = await answerDevice({ pairing, secret, name: 'Ana', linkDevice });
  const got = await acceptIdentity({ pairing, secret, deviceKey: b.publicKey });
  assert.equal(got.identityKey, a.identityKey);
  assert.equal(got.name, 'Ana');
  assert.equal(await linksValid(got.identityKey, got.links, b.publicKey), true);

  // B assina um pedido como a identidade de A: o verificador aceita.
  const requestId = '0123456789abcdef0123456789abcdef';
  const request = { sessionId: 'kq1t9m3x7c2a8z5r', clientSeed: 's', expression: '1d20', identityKey: got.identityKey, name: 'Ana', requestId };
  const signature = hex(await crypto.subtle.sign({ name: 'Ed25519' }, b.privateKey, playerMessage(request)));
  const player = { publicKey: b.publicKey, name: 'Ana', requestId, signature, identityKey: got.identityKey, links: got.links };
  assert.equal(playerProblem(player), null);
  assert.equal(await verifyEd25519(b.publicKey, playerMessage(request), signature), true);
});

test('A recusa chave trocada no caminho e B recusa pacote adulterado ou de outro segredo', async () => {
  const b = await newDevice();
  const intruder = await newDevice();
  const secret = newSecret();
  const mac = await deviceMac(secret, ID, b.publicKey);

  // O servidor troca a chave de B pela dele, mantendo o MAC: A percebe.
  await assert.rejects(answerDevice({
    pairing: { pairingId: ID, device: { deviceKey: intruder.publicKey, mac } }, secret, name: 'Ana', linkDevice,
  }), /não veio do aparelho/);
  // Sem conhecer o segredo, não dá para fazer um MAC válido.
  await assert.rejects(answerDevice({
    pairing: { pairingId: ID, device: { deviceKey: intruder.publicKey, mac: await deviceMac(newSecret(), ID, intruder.publicKey) } },
    secret, name: 'Ana', linkDevice,
  }), /não veio do aparelho/);

  const pairing = { pairingId: ID, device: { deviceKey: b.publicKey, mac } };
  pairing.identity = await answerDevice({ pairing, secret, name: 'Ana', linkDevice });
  for (const tampered of [
    { ...pairing.identity, name: 'Outra' },
    { ...pairing.identity, identityKey: intruder.publicKey },
    { ...pairing.identity, links: [] },
  ]) {
    await assert.rejects(acceptIdentity({ pairing: { ...pairing, identity: tampered }, secret, deviceKey: b.publicKey }), /não confere/);
  }
  await assert.rejects(acceptIdentity({ pairing, secret: newSecret(), deviceKey: b.publicKey }), /não confere/);
  await assert.rejects(acceptIdentity({ pairing, secret, deviceKey: intruder.publicKey }), /não confere/);
});

test('aparelho vinculado assina como a identidade e pode vincular o próximo', async () => {
  // Este processo passa a ser um aparelho vinculado à identidade de outro (root).
  const root = await newDevice();
  const me = await identity();
  const { linkMessage } = await import('./verifier/chain-v2.js');
  const sig = hex(await crypto.subtle.sign({ name: 'Ed25519' }, root.privateKey,
    linkMessage({ identityKey: root.publicKey, deviceKey: me.publicKey })));
  const adopted = await adoptIdentity({ identityKey: root.publicKey, links: [{ deviceKey: me.publicKey, signature: sig }] });
  assert.equal(adopted.identityKey, root.publicKey);

  const player = await signRequest({ sessionId: 'kq1t9m3x7c2a8z5r', clientSeed: 's', expression: '1d20', name: 'Ana' });
  assert.equal(player.identityKey, root.publicKey);
  assert.equal(await linksValid(player.identityKey, player.links, player.publicKey), true);

  const next = await newDevice();
  const linked = await linkDevice(next.publicKey);
  assert.equal(linked.links.length, 2);
  assert.equal(await linksValid(root.publicKey, linked.links, next.publicKey), true);
});
