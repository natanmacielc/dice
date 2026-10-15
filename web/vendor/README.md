# web/vendor

Código de terceiros servido pelo próprio site (sem CDN em runtime; o CSP só libera `'self'` e os endpoints do drand).

## drand-client-1.4.2.mjs

- Origem: [drand-client](https://github.com/drand/drand-client) 1.4.2, a biblioteca oficial do drand, empacotada só com `fetchBeacon` (que roda o `verifyBeacon` interno: round, randomness e assinatura BLS em G1, RFC 9380).
- Usado por `web/verifier/drand-verify.js`.
- SHA-256: `812c231be25d9df31557532760a4743ce91c6259c0c7dd3e30258701df564eef`
- Reproduzir: `tools/vendor/drand-client/build.sh` (`npm ci --ignore-scripts` a partir do `package-lock.json` e esbuild 0.28.2). O hash tem de sair igual.

Pacotes incluídos no bundle (versões travadas no lockfile):

| pacote | versão | licença |
|---|---|---|
| drand-client | 1.4.2 | Apache-2.0 OR MIT |
| @noble/curves | 1.9.7 | MIT |
| @noble/hashes | 1.8.0 | MIT |
| @kevincharm/noble-bn254-drand | 0.0.1 | MIT |
| buffer | 6.0.3 | MIT |
| base64-js | 1.5.1 | MIT |
| ieee754 | 1.2.1 | BSD-3-Clause |
| isomorphic-fetch / whatwg-fetch | 3.0.0 / 3.6.20 | MIT |

Os avisos de licença ficam no fim do próprio arquivo (`--legal-comments=eof`).
