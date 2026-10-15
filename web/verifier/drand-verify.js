// Busca um round direto no drand (sem passar pelo backend) e verifica a assinatura BLS com o
// drand-client oficial (vendorizado em web/vendor, ver README lá).

import { fetchBeacon } from '../vendor/drand-client-1.4.2.mjs';
import { randomnessMatchesSignature } from './dice-v1.js';
import { QUICKNET } from './chain-v1.js';

/** Chain info do quicknet, fixo no código (o mesmo que /{chain}/info devolve). */
export const QUICKNET_INFO = Object.freeze({
  public_key:
    '83cf0f2896adee7eb8b5f01fcad3912212c437e0073e911fb90022d3e760183c8c4b450b6a0a6c3ac6a5776a2d1064510d'
    + '1fec758c921cc22b0e17e63aaf4bcb5ed66304de9cf809bd274ca73bab4af5a6e9c76a4bc09e76eae8991ef5ece45a',
  period: QUICKNET.periodSeconds,
  genesis_time: QUICKNET.genesisTime,
  hash: QUICKNET.chainHash,
  groupHash: 'f477d5c89f21a17c863a7f937c6a6d15859414d2be09cd448d4279af331c5d3e',
  schemeID: 'bls-unchained-g1-rfc9380',
  metadata: Object.freeze({ beaconID: 'quicknet' }),
});

export const DRAND_ENDPOINTS = Object.freeze([
  'https://api.drand.sh',
  'https://api2.drand.sh',
  'https://api3.drand.sh',
  'https://drand.cloudflare.com',
]);

/**
 * Busca exatamente o round pedido, com failover. Só aceita resposta com o round certo e
 * randomness = SHA-256(signature). Devolve { beacon, endpoint, failures }.
 */
export async function fetchRound(round, { fetch = globalThis.fetch, endpoints = DRAND_ENDPOINTS, timeoutMs = 5000 } = {}) {
  const failures = [];
  for (const endpoint of endpoints) {
    try {
      const response = await fetch(`${endpoint}/${QUICKNET.chainHash}/public/${round}`, {
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const body = await response.json();
      if (body.round !== round) throw new Error(`pedido round ${round}, veio ${body.round}`);
      if (!(await randomnessMatchesSignature(body.signature, body.randomness))) {
        throw new Error('randomness != SHA-256(signature)');
      }
      return { beacon: { round: body.round, randomness: body.randomness, signature: body.signature }, endpoint, failures };
    } catch (e) {
      failures.push(`${endpoint}: ${e.message}`);
    }
  }
  const error = new Error(`round ${round} indisponível em todos os endpoints`);
  error.failures = failures;
  throw error;
}

/** Assinatura BLS do beacon válida para o round esperado, com a chave pública do quicknet. */
export async function verifyBls(beacon, expectedRound) {
  // ChainClient mínimo: devolve o beacon já obtido e o chain info fixo; o fetchBeacon do
  // drand-client roda o verifyBeacon (round, randomness e BLS em G1, RFC 9380).
  const client = {
    options: { disableBeaconVerification: false, noCache: true },
    chain: () => ({ baseUrl: '', info: async () => QUICKNET_INFO }),
    get: async () => ({ round: beacon.round, randomness: beacon.randomness, signature: beacon.signature }),
    latest: async () => beacon,
  };
  try {
    await fetchBeacon(client, expectedRound);
    return true;
  } catch {
    return false;
  }
}
