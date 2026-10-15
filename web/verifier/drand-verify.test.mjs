// BLS com assinaturas reais do quicknet e failover com fetch falso (sem rede).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DRAND_ENDPOINTS, fetchRound, QUICKNET_INFO, verifyBls } from './drand-verify.js';

const REAL = {
  1: ['1466a6cd24e327188770752f6134001c64d6efcc590ccc26b721611ad96f165a',
    'b55e7cb2d5c613ee0b2e28d6750aabbb78c39dcc96bd9d38c2c2e12198df95571de8e8e402a0cc48871c7089a2b3af4b'],
  1000000: ['b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3',
    '83ad29e4c409f9470fc2ef02f90214df49e02b441a1a241a82d622d9f608ef98fd8b11a029f1bee9d9e83b45088abe72'],
  1000001: ['9f45f439afd81e9846b3b4dc5e3e6051922c73c8459d18e9d507b52ddbd884ff',
    'a5bd91e5e2d8c0bf51bffdfad87eef34348fd9c0b2df2bee39db90bdef7e1399b1a77bb2fe98b24d84c0936a306c4218'],
  1000002: ['018e0e0c9e0d7906762eca633fab3ec5e97ee4cb8949e4eb9ee589160b49263d',
    'a96e2a020098645aa4f912dcca317a67e98c39909fe1a037798fb503f04272b8153bab438c7e8d298593af1bdf29e5c5'],
};
const beacon = (round) => ({ round, randomness: REAL[round][0], signature: REAL[round][1] });

// Silencia os console.error internos do drand-client nos casos negativos.
const quiet = async (fn) => {
  const original = console.error;
  console.error = () => {};
  try {
    return await fn();
  } finally {
    console.error = original;
  }
};

for (const round of Object.keys(REAL).map(Number)) {
  test(`BLS válido no round real ${round}`, async () => assert.equal(await verifyBls(beacon(round), round), true));
}

test('BLS rejeita assinatura de outro round mesmo com randomness coerente', async () => {
  // randomness = SHA-256(signature) vale, mas a mensagem assinada é a do round 1000001.
  const forged = { round: 1000000, randomness: REAL[1000001][0], signature: REAL[1000001][1] };
  assert.equal(await quiet(() => verifyBls(forged, 1000000)), false);
});

test('BLS rejeita round diferente do esperado e assinatura adulterada', async () => {
  assert.equal(await quiet(() => verifyBls(beacon(1000000), 1000001)), false);
  const s = REAL[1000000][1];
  const tampered = { ...beacon(1000000), signature: s.slice(0, -1) + (s.at(-1) === '0' ? '1' : '0') };
  assert.equal(await quiet(() => verifyBls(tampered, 1000000)), false);
});

test('chain info fixo é o do quicknet', () => {
  assert.equal(QUICKNET_INFO.hash, '52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971');
  assert.equal(QUICKNET_INFO.schemeID, 'bls-unchained-g1-rfc9380');
});

function fakeFetch(responses) {
  const calls = [];
  const fn = async (url) => {
    calls.push(url);
    const r = responses.shift();
    if (r instanceof Error) throw r;
    return { ok: r.status === 200, status: r.status, json: async () => r.body };
  };
  fn.calls = calls;
  return fn;
}

test('fetchRound pula endpoint com erro, round errado ou randomness inválido', async () => {
  const fetch = fakeFetch([
    { status: 500 },
    new Error('rede'),
    { status: 200, body: beacon(1000001) },
    { status: 200, body: { ...beacon(1000000), randomness: '0'.repeat(64) } },
  ]);
  await assert.rejects(fetchRound(1000000, { fetch }), (e) => e.failures.length === 4);
  assert.equal(fetch.calls.length, 4);
  assert.ok(fetch.calls[0].endsWith('/52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971/public/1000000'));
});

test('fetchRound devolve o primeiro beacon válido', async () => {
  const fetch = fakeFetch([{ status: 425 }, { status: 200, body: beacon(1000000) }]);
  const r = await fetchRound(1000000, { fetch });
  assert.deepEqual(r.beacon, beacon(1000000));
  assert.equal(r.endpoint, DRAND_ENDPOINTS[1]);
  assert.equal(r.failures.length, 1);
});

test('live: busca e verifica no drand real', { skip: !process.env.DRAND_LIVE }, async () => {
  const info = await (await fetch(`${DRAND_ENDPOINTS[0]}/${QUICKNET_INFO.hash}/info`)).json();
  assert.equal(info.public_key, QUICKNET_INFO.public_key);
  const { beacon: b } = await fetchRound(1000000);
  assert.equal(await verifyBls(b, 1000000), true);
});
