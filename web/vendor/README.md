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

## qrcode-generator-2.0.4.mjs

- Origem: [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) 2.0.4 (MIT, Kazuhiko Arase), só o gerador da matriz. O pacote não traz comentário legal que o esbuild preserve, então o `build.sh` põe o aviso da MIT no início do arquivo (`--banner:js`). O desenho em SVG é `qrSvg` em `web/ui.js`, sem estilo inline.
- Usado pela folha "Usar em outro aparelho" (`web/app.js`), carregado só quando ela abre.
- SHA-256: `3ae0453cc6b13a2e9b0a776547b363aa93e52265a502e54f8520767791c8987a`
- Reproduzir: `tools/vendor/qrcode/build.sh` (`npm ci --ignore-scripts` a partir do `package-lock.json` e esbuild 0.28.2). O hash tem de sair igual.
