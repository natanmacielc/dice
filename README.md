# Roll Dice

[![CI](https://github.com/natanmacielc/dice/actions/workflows/ci.yml/badge.svg)](https://github.com/natanmacielc/dice/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Provably fair dice for tabletop RPGs.** Every roll comes from public randomness that did not exist yet when the roll was requested, so nobody (not the players, not the game master, not the server) can predict or pick the result. Anyone can recompute it and check.

🎲 **Live:** <https://dice.nimbos.solutions> · 🇧🇷 [Leia em português](README.pt-BR.md)

## How it works

1. **You commit first.** Your device picks a `clientSeed` and signs the request (Ed25519) before anything is rolled.
2. **The server picks a future round.** The roll is tied to a [drand](https://drand.love) *quicknet* round about 6 seconds in the future (`targetRound = latest + 2`). drand is a public randomness beacon run by independent organizations; nobody knows a round's value before it's published.
3. **The round arrives and the dice are computed.** `SHA-256(randomness | clientSeed | nonce | counter)` is split into 32-bit words, with rejection sampling to avoid modulo bias. The result is `v % sides + 1`.
4. **Everything goes into an append-only hash chain**, one per table: request, round and result entries, each linked to the previous one by SHA-256. Pending and expired rolls stay in the public log too.
5. **Your browser checks it.** It fetches the round straight from drand, verifies the BLS signature and recomputes the dice. The server is just a ledger: it can't predict, choose or rewrite a result.

There is no server seed. The full algorithm is in [`spec/ALGORITHM.md`](spec/ALGORITHM.md) (in Portuguese) and is pinned by [`spec/test-vectors.json`](spec/test-vectors.json).

## Verify it yourself

Every roll has a page at `/r/{rollId}` and a downloadable proof. Three independent implementations check the same test vectors:

| Verifier | Where |
|---|---|
| Java | [`dice-core/`](dice-core) (JDK only, no dependencies) |
| JavaScript (runs in your browser) | [`web/verifier/`](web/verifier) |
| Python (CLI) | [`tools/verify-py/`](tools/verify-py) |

Check a proof with the Python verifier:

```sh
curl -s https://dice.nimbos.solutions/api/rolls/<rollId>/proof > proof.json
cd tools/verify-py && python3 -m pip install cryptography==50.0.2
python3 -m dice_verify.proof ../../proof.json
# {"valid": true, "rollId": "...", "dice": [4, 2], "total": 9, ...}
```

Or recompute a roll from its inputs:

```sh
python3 -m dice_verify --randomness <hex> --seed "<clientSeed>" --nonce 1 --expr 2d6+3
```

The whole table log is public at `GET /api/sessions/{sessionId}/log`.

## Features

- Shared tables: invite players by link, and everyone sees every roll, in order.
- Player identity without accounts: a non-exportable key generated in the browser (IndexedDB). It never leaves the device.
- Multiple devices per player, paired by QR code.
- Open Graph images for each roll, so a shared link shows the result.
- "Why trust it?" page with a step-by-step explanation and a "check it yourself" tool.

## Architecture

A modular monolith in Java 21 (Maven multi-module, one deployable artifact).

| Module | What it is |
|---|---|
| [`spec/`](spec) | Algorithm spec and test vectors: the source of truth |
| [`dice-core/`](dice-core) | Pure Java (JDK only): roller, expression parser, verifier |
| [`dice-app/`](dice-app) | Quarkus: REST API, DynamoDB, S3, OG images, drand client |
| [`web/`](web) | Static site and JS verifier (strict CSP, no framework) |
| [`tools/verify-py/`](tools/verify-py) | Independent Python verifier |
| [`infra/`](infra) | AWS CDK in Java |

On AWS: CloudFront (flat-rate **Free** plan, with WAF) → Lambda (Quarkus with SnapStart) through a Function URL with OAC, plus DynamoDB (provisioned, within the always-free tier) and S3. There's no API Gateway. A kill switch drops the Lambda's concurrency to zero on unusual traffic, so the worst case is downtime, not a bill. CI/CD runs on GitHub Actions with OIDC (no long-lived keys). Details: [`infra/README.md`](infra/README.md) and [`infra/SECURITY.md`](infra/SECURITY.md).

### Invariants

1. Always use the **target** round's randomness, never the latest.
2. The hash chain never forks: atomic writes conditioned on the sequence number.
3. Changing the algorithm means a new version (v2) with the spec, vectors and all three verifiers updated together.
4. `dice-core` depends on nothing but the JDK.
5. No homemade crypto.
6. drand's randomness must equal `SHA-256(signature)`.
7. The public log includes pending and expired requests.

## Development

Requires JDK 21, Docker (DynamoDB Local via Testcontainers), Node 22 and Python 3.12.

```sh
./mvnw -q verify                                   # Java build and tests
./mvnw -q -pl dice-core test                       # core only
node --test "web/**/*.test.mjs"                    # JS verifier and web logic
python3 -m pytest tools/verify-py                  # Python verifier (needs pytest and cryptography)
./mvnw -f infra/pom.xml verify                     # CDK template tests
```

To run the app locally: `./mvnw -pl dice-app quarkus:dev` with DynamoDB Local and s3mock (endpoint overrides in [`CLAUDE.md`](CLAUDE.md)). Deploys go only through GitHub Actions.

## License

[MIT](LICENSE). Third-party code in [`web/vendor/`](web/vendor) and fonts keep their own licenses (MIT, Apache-2.0, BSD-3-Clause, OFL); see the READMEs next to them.
