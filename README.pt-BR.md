# Roll Dice

[![CI](https://github.com/natanmacielc/dice/actions/workflows/ci.yml/badge.svg)](https://github.com/natanmacielc/dice/actions/workflows/ci.yml)
[![Licença: MIT](https://img.shields.io/badge/licen%C3%A7a-MIT-blue.svg)](LICENSE)

**Dados de RPG com imparcialidade comprovável.** Cada rolagem vem de uma aleatoriedade pública que ainda não existia quando o pedido foi feito. Ninguém (nem os jogadores, nem o mestre, nem o servidor) consegue prever ou escolher o resultado, e qualquer pessoa pode recalcular e conferir.

🎲 **No ar:** <https://dice.nimbos.solutions> · 🇺🇸 [Read in English](README.md)

## Como funciona

1. **Você se compromete primeiro.** Seu aparelho escolhe uma `clientSeed` e assina o pedido (Ed25519) antes de qualquer rolagem.
2. **O servidor fixa um round futuro.** A rolagem fica presa a um round do [drand](https://drand.love) *quicknet* uns 6 segundos no futuro (`targetRound = latest + 2`). O drand é um farol público de aleatoriedade mantido por organizações independentes; ninguém sabe o valor de um round antes de ele ser publicado.
3. **O round chega e os dados saem dele.** `SHA-256(randomness | clientSeed | nonce | contador)` vira palavras de 32 bits, com rejeição para evitar viés de módulo. O resultado é `v % lados + 1`.
4. **Tudo vai para uma cadeia de hash só de acréscimo**, uma por mesa: entradas de pedido, round e resultado, cada uma ligada à anterior por SHA-256. Pedidos pendentes e expirados também ficam no log público.
5. **Seu navegador confere.** Ele busca o round direto no drand, verifica a assinatura BLS e recalcula os dados. O servidor é só um livro-razão: não pode prever, escolher nem reescrever um resultado.

Não existe semente do servidor. O algoritmo completo está em [`spec/ALGORITHM.md`](spec/ALGORITHM.md) e é fixado por [`spec/test-vectors.json`](spec/test-vectors.json).

## Confira você mesmo

Toda rolagem tem uma página em `/r/{rollId}` e uma prova para baixar. Três implementações independentes passam nos mesmos vetores de teste:

| Verificador | Onde |
|---|---|
| Java | [`dice-core/`](dice-core) (só o JDK, sem dependências) |
| JavaScript (roda no seu navegador) | [`web/verifier/`](web/verifier) |
| Python (linha de comando) | [`tools/verify-py/`](tools/verify-py) |

Para conferir uma prova com o verificador Python:

```sh
curl -s https://dice.nimbos.solutions/api/rolls/<rollId>/proof > prova.json
cd tools/verify-py && python3 -m pip install cryptography==50.0.2
python3 -m dice_verify.proof ../../prova.json
# {"valid": true, "rollId": "...", "dice": [4, 2], "total": 9, ...}
```

Ou recalcule uma rolagem a partir das entradas:

```sh
python3 -m dice_verify --randomness <hex> --seed "<clientSeed>" --nonce 1 --expr 2d6+3
```

O log inteiro da mesa é público em `GET /api/sessions/{sessionId}/log`.

## Recursos

- Mesas compartilhadas: convide jogadores por link, e todo mundo vê todas as rolagens, em ordem.
- Identidade do jogador sem conta: uma chave não exportável gerada no navegador (IndexedDB), que nunca sai do aparelho.
- Vários aparelhos por jogador, pareados por QR code.
- Imagem Open Graph para cada rolagem: o link compartilhado já mostra o resultado.
- Página "Por que confiar?" com a explicação passo a passo e um "confira você mesmo".

## Arquitetura

Monólito modular em Java 21 (Maven multi-módulo, um único artefato implantável).

| Módulo | O que é |
|---|---|
| [`spec/`](spec) | Especificação do algoritmo e vetores de teste: a fonte da verdade |
| [`dice-core/`](dice-core) | Java puro (só o JDK): rolagem, parser de expressão e verificador |
| [`dice-app/`](dice-app) | Quarkus: API REST, DynamoDB, S3, imagens OG e cliente do drand |
| [`web/`](web) | Site estático e verificador em JS (CSP rígido, sem framework) |
| [`tools/verify-py/`](tools/verify-py) | Verificador independente em Python |
| [`infra/`](infra) | AWS CDK em Java |

Na AWS: CloudFront (plano flat-rate **Free**, com WAF) → Lambda (Quarkus com SnapStart) por Function URL com OAC, mais DynamoDB (provisionado, dentro da cota sempre gratuita) e S3. Não há API Gateway. Um kill switch zera a concorrência do Lambda quando o tráfego foge do normal, então o pior caso é o site fora do ar, não uma conta alta. O CI/CD roda no GitHub Actions com OIDC (sem chaves de longa duração). Detalhes em [`infra/README.md`](infra/README.md) e [`infra/SECURITY.md`](infra/SECURITY.md).

### Invariantes

1. Usar sempre o randomness do round **alvo**, nunca o do latest.
2. A cadeia nunca bifurca: escrita atômica condicionada ao número de sequência.
3. Mudar o algoritmo exige versão nova (v2), com spec, vetores e os três verificadores atualizados juntos.
4. O `dice-core` não depende de nada além do JDK.
5. Nada de criptografia caseira.
6. O randomness do drand tem de ser igual a `SHA-256(signature)`.
7. O log público inclui pedidos pendentes e expirados.

## Desenvolvimento

Precisa de JDK 21, Docker (DynamoDB Local via Testcontainers), Node 22 e Python 3.12.

```sh
./mvnw -q verify                                   # build e testes Java
./mvnw -q -pl dice-core test                       # só o núcleo
node --test "web/**/*.test.mjs"                    # verificador JS e lógica da web
python3 -m pytest tools/verify-py                  # verificador Python (precisa de pytest e cryptography)
./mvnw -f infra/pom.xml verify                     # testes do template do CDK
```

Para rodar o app localmente: `./mvnw -pl dice-app quarkus:dev` com DynamoDB Local e s3mock (os endpoints estão no [`CLAUDE.md`](CLAUDE.md)). Deploy só pelo GitHub Actions.

## Licença

[MIT](LICENSE). O código de terceiros em [`web/vendor/`](web/vendor) e as fontes mantêm as próprias licenças (MIT, Apache-2.0, BSD-3-Clause, OFL); veja os READMEs ao lado deles.
