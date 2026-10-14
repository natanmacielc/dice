# Algoritmo dice-v1

Esta é a fonte da verdade. Os verificadores Java (`dice-core`), JavaScript (`web/verifier/dice-v1.js`) e Python (`tools/verify-py`) implementam exatamente o que está aqui, e todos passam em `spec/test-vectors.json`.

Mudou qualquer coisa que altere um resultado ou uma validação? Então nasce a `dice-v2`, com spec, vetores e os três verificadores atualizados no mesmo PR. A v1 não muda em silêncio.

## 1. Ideia

O jogador escolhe uma `clientSeed` antes do roll. O servidor fixa um round futuro do drand (`targetRound`) e grava o pedido. O resultado só depende de:

- `randomness` do round alvo (público, imprevisível antes de ser publicado);
- `clientSeed` (do jogador);
- `nonce` (sequencial por mesa);
- a expressão (`NdX+M`).

Não existe `server_seed`. Qualquer pessoa com esses quatro valores recalcula o resultado.

## 2. Beacon

| Campo | Valor |
|---|---|
| Rede | drand quicknet |
| Chain hash | `52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971` |
| Período | 3 s |
| Esquema | `bls-unchained-g1-rfc9380` |

**Round alvo.** No pedido, `targetRound = latest.round + 2`. Na resolução, usa-se o `randomness` do round alvo, **nunca** o do latest.

**Checagem do beacon (obrigatória).** `randomness` tem de ser o SHA-256 dos **bytes** da assinatura, e não da string hex:

```
hex(SHA-256(hexdecode(signature))) == randomness
```

A assinatura é hex minúsculo de tamanho par; outro formato gera `SIGNATURE_FORMAT`. Conferir à mão (round 1000000):

```sh
printf 83ad29e4c409f9470fc2ef02f90214df49e02b441a1a241a82d622d9f608ef98fd8b11a029f1bee9d9e83b45088abe72 \
  | xxd -r -p | sha256sum
# b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3
```

A verificação da assinatura BLS contra a chave pública da rede ainda não faz parte da v1 (decisão em aberto).

## 3. Entradas e validação

A validação segue esta ordem, e o primeiro erro vence: randomness, clientSeed, nonce, expressão. Os códigos de erro fazem parte do contrato e são iguais nas três linguagens.

### 3.1 randomness

Exatamente 64 caracteres em `[0-9a-f]`; maiúsculas não são aceitas. Erro: `RANDOMNESS_FORMAT`.

### 3.2 clientSeed

Texto Unicode. Usam-se os bytes UTF-8 exatamente como recebidos, **sem normalização** (NFC e NFD dão resultados diferentes; veja os vetores `seed-nfc` e `seed-nfd`). As checagens são feitas nesta ordem:

| Regra | Erro |
|---|---|
| UTF-8 bem formado (em UTF-16, sem surrogate solto) | `SEED_INVALID_UTF8` |
| Não vazia | `SEED_EMPTY` |
| No máximo 64 bytes em UTF-8 | `SEED_TOO_LONG` |
| Sem U+0000 a U+001F nem U+007F | `SEED_CONTROL_CHAR` |

`|` é permitido (ver 4.1).

### 3.3 nonce

Inteiro de 0 a 2^53 − 1 (9007199254740991), para caber exato num Number do JavaScript. Erro: `NONCE_OUT_OF_RANGE`. O valor inicial por mesa é definido pelo servidor (Fase 3).

### 3.4 Expressão

Tudo em ASCII. A gramática é leniente na entrada e tem uma forma canônica na saída.

```
expr     = ws [count] ("d" | "D") sides [ws sign ws modifier] ws
count    = "0" | [1-9] [0-9]?
sides    = "0" | [1-9] [0-9]{0,2}
modifier = "0" | [1-9] [0-9]{0,2}
sign     = "+" | "-"
ws       = (" " | "\t")*
```

- A expressão casa a string inteira; uma quebra de linha no fim invalida. Dígitos não-ASCII (como `１`) não valem. Zeros à esquerda não valem.
- Sem `count`, N = 1.
- Erro de sintaxe: `INVALID_EXPRESSION`.

Depois vem a checagem semântica, nesta ordem:

| Regra | Erro |
|---|---|
| 1 ≤ N ≤ 20 | `DICE_COUNT_OUT_OF_RANGE` |
| X ∈ {4, 6, 8, 10, 12, 20, 100} | `UNSUPPORTED_DIE` |
| \|M\| ≤ 100 | `MODIFIER_OUT_OF_RANGE` |

**Forma canônica:** `NdX` quando M = 0, `NdX+M` quando M > 0 e `NdX-|M|` quando M < 0. Assim, `-0` e `+0` somem. Exemplos: `d20` vira `1d20`; ` 2d6 + 3 ` vira `2d6+3`; `2D6-0` vira `2d6`.

## 4. Cálculo

### 4.1 Fluxo de palavras

```
bloco(c) = SHA-256( utf8(randomness) || "|" || utf8(clientSeed) || "|" || dec(nonce) || "|" || dec(c) )
```

- `c` = 0, 1, 2, …
- `dec` é o inteiro em decimal ASCII sem zeros à esquerda.
- `||` é concatenação de bytes, o que equivale a `utf8(randomness + "|" + clientSeed + "|" + nonce + "|" + c)`.

Cada bloco de 32 bytes rende 8 palavras: os bytes `[4k, 4k+4)` lidos como inteiro **big-endian sem sinal** de 32 bits, com k = 0..7. O fluxo é a sequência das palavras do bloco 0, depois do bloco 1, e assim por diante. Cada roll tem seu próprio fluxo, e os dados de um roll o consomem em sequência.

**Sem ambiguidade com `|` na seed.** `randomness` tem tamanho fixo (64) e `nonce` e `c` são inteiros sem `|`. Lendo a string da direita, os campos se separam de um único jeito.

### 4.2 Sorteio de um dado (rejeição de viés)

```
limite = 2^32 − (2^32 mod lados)
repita:
    v = próxima palavra do fluxo
    se v ≥ limite: descarta v (rejeição) e continua
    senão: dado = (v mod lados) + 1; pare
```

| lados | 2^32 mod lados | limite |
|---|---|---|
| 4 | 0 | 4294967296 (nunca rejeita) |
| 6 | 4 | 4294967292 |
| 8 | 0 | 4294967296 (nunca rejeita) |
| 10 | 6 | 4294967290 |
| 12 | 4 | 4294967292 |
| 20 | 16 | 4294967280 |
| 100 | 96 | 4294967200 |

### 4.3 Roll

```
dados = [sorteio() for i in 1..N]      # mesmo fluxo, em sequência
total = soma(dados) + M
```

Os vetores também registram `wordsConsumed` (palavras lidas, inclusive as rejeitadas) e `rejections`, a lista de `{wordIndex, value}` descartados, com `wordIndex` contado a partir de 0 no fluxo do roll.

## 5. Exemplo resolvido (vetor `d20-basic`)

Entradas:

- `randomness = b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3` (round 1000000)
- `clientSeed = mesa-do-dragao`
- `nonce = 6`
- expressão `1d20`

**Passo 1.** Monte a string do bloco 0 e calcule o SHA-256:

```sh
printf '%s' 'b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3|mesa-do-dragao|6|0' | sha256sum
# cb28774c49d86affdcecfaa4d0434a226b971cbfafab46b2a70f9cf3f4c48cde
```

**Passo 2.** A primeira palavra são os 4 primeiros bytes, `cb28774c`, lidos em big-endian: 3408426828.

**Passo 3.** O limite do d20 é 2^32 − 16 = 4294967280. Como 3408426828 < 4294967280, a palavra é aceita.

**Passo 4.** dado = 3408426828 mod 20 + 1 = 8 + 1 = **9**. total = 9 + 0 = **9**. Foi consumida 1 palavra e não houve rejeição.

Para um roll com vários dados, as próximas palavras são `49d86aff`, `dcecfaa4`, … até a oitava (`f4c48cde`). Depois vem o bloco 1 (`…|6|1`).

## 6. Fora da v1 por enquanto

- Cadeia de hash do log (REQUEST, ROUND, RESULT) e payload canônico: entram na Fase 3, junto com o modelo de dados. Spec, vetores e os três verificadores serão atualizados no mesmo PR.
- Formato do recibo e do link de prova.
- Verificação BLS da assinatura.
