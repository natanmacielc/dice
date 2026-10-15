# Algoritmo dice-v1 (cadeias chain-v1 e chain-v2)

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

## 6. Cadeia de hash (chain-v1)

Cada mesa (sessão) tem um log público, append-only, onde cada entrada aponta para a anterior por hash. O servidor não consegue reescrever, apagar nem reordenar uma entrada sem quebrar todos os hashes seguintes. O log inclui pedidos ainda pendentes.

### 6.1 Entrada

```json
{"seq": 3, "type": "ROUND", "prevHash": "<64 hex>", "hash": "<64 hex>", "payload": { ... }}
```

- `seq`: inteiro ≥ 1, contíguo dentro da mesa. A primeira entrada da mesa tem seq 1.
- `type`: `REQUEST`, `ROUND` ou `RESULT`.
- `prevHash`: 64 zeros na entrada seq 1. Nas demais, é o `hash` da entrada anterior.
- `hash = hex(SHA-256( utf8(prevHash) || utf8(type) || utf8(canonical(payload)) ))`.
  - prevHash tem tamanho fixo, `type` vem de um vocabulário fechado e o JSON canônico começa com `{`, então a concatenação não é ambígua.
- Todo payload tem `seq` (igual ao da entrada, o que amarra a numeração ao hash), `sessionId` e `rollId`.

### 6.2 Payloads

| type | campos (além de seq, sessionId, rollId) |
|---|---|
| REQUEST | `algorithm` (`"dice-v1"`), `drandChainHash` (o chain hash do quicknet), `clientSeed`, `expression` (forma canônica), `nonce`, `targetRound`, `requestedAt` |
| ROUND | `round`, `randomness`, `signature` |
| RESULT | `dice` (array de inteiros), `modifier`, `total` |

`requestedAt` é o relógio do servidor no pedido, em ISO-8601 UTC com milissegundos: `2026-10-14T22:50:01.123Z`.

Um pedido grava uma entrada REQUEST. A resolução grava ROUND e RESULT em sequência, numa única transação atômica. Entre o REQUEST e o ROUND de um roll podem existir entradas de outros rolls.

### 6.3 JSON canônico

É um subconjunto do RFC 8785 (JCS):

- **Tipos:** só objeto, array, string e inteiro com |n| ≤ 2^53 − 1. Float, booleano e null são proibidos (`CHAIN_STRUCTURE`).
- **Objetos:** chaves em ordem crescente de unidades UTF-16 (todas as chaves do formato são ASCII) e sem espaços: `{"a":1,"b":[1,2]}`.
- **Inteiros:** em decimal, sem zeros à esquerda, com `-` nos negativos.
- **Strings:** entre aspas. Escapa-se só o seguinte:

  | caractere | escape |
  |---|---|
  | `"` | `\"` |
  | `\` | `\\` |
  | U+0008 | `\b` |
  | U+0009 | `\t` |
  | U+000A | `\n` |
  | U+000C | `\f` |
  | U+000D | `\r` |
  | outros abaixo de U+0020 | `\u00xx` (hex minúsculo) |

  Todo o resto, inclusive não-ASCII, `/` e U+007F, vai literal em UTF-8.

Isso é o que fazem `JSON.stringify` com chaves ordenadas e `json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)` em Python.

### 6.4 Verificação de um segmento do log

O verificador recebe as entradas em ordem e o `prevHash` esperado para a primeira: 64 zeros se ela tiver seq 1, ou o hash conhecido da anterior. As checagens de cada entrada seguem esta ordem, e o primeiro erro encontrado é o reportado:

1. **Estrutura.** `seq` inteiro ≥ 1, `type` válido, `prevHash` e `hash` com 64 hex minúsculos, `payload` objeto canonizável. Erro: `CHAIN_STRUCTURE`.
2. **Contiguidade.** A partir da segunda entrada, `seq` = seq anterior + 1. Erro: `CHAIN_SEQ_GAP`.
3. **Seq no payload.** `payload.seq` = `seq`. Erro: `CHAIN_SEQ_MISMATCH`.
4. **Encadeamento.** `prevHash` é igual ao esperado: o informado, na primeira entrada; o `hash` da anterior, nas demais. Erro: `CHAIN_LINK_BROKEN`.
5. **Hash.** `hash` é igual ao recalculado. Erro: `CHAIN_HASH_MISMATCH`.

### 6.5 Prova de um roll

```json
{
  "algorithm": "dice-v1",
  "chainFormat": "chain-v1",
  "drand": {"network": "quicknet", "chainHash": "52db…e971"},
  "beacon": {"round": 1000000, "randomness": "…", "signature": "…"},
  "roll": {"rollId": "…", "sessionId": "…", "nonce": 1, "clientSeed": "…", "expression": "2d6+3",
           "targetRound": 1000000, "requestedAt": "…", "dice": [4, 2], "modifier": 3, "total": 9},
  "entries": [ …entradas contíguas, do REQUEST até o RESULT do roll… ]
}
```

A verificação segue esta ordem, e o primeiro erro encontrado é o reportado:

1. **Cabeçalho.** `algorithm` = `dice-v1`, `chainFormat` = `chain-v1`, `drand.network` = `quicknet`, `drand.chainHash` igual ao fixo, e `entries` é um array não vazio. Erro: `PROOF_STRUCTURE`.
2. **Segmento.** As entradas passam na seção 6.4, usando como prevHash esperado o `prevHash` da própria primeira entrada. Erro: os códigos `CHAIN_*`.
3. **Forma da prova.** Erro: `PROOF_STRUCTURE` para qualquer uma destas condições:
   - a primeira entrada é um REQUEST e a última é um RESULT, os dois do mesmo `rollId`;
   - há exatamente um ROUND desse `rollId`, entre os dois;
   - não há outro REQUEST nem outro RESULT desse `rollId`;
   - todas as entradas têm o mesmo `sessionId`;
   - os campos dos payloads existem e têm os tipos certos;
   - no REQUEST, `algorithm` = `dice-v1`, `drandChainHash` é o fixo, `expression` já está na forma canônica e `requestedAt` está no formato da seção 6.2.
4. **Round alvo.** `ROUND.round` = `REQUEST.targetRound`, nunca o latest. Erro: `PROOF_WRONG_ROUND`.
5. **Beacon.** `randomness` = SHA-256(bytes de `signature`). Erro: `PROOF_BEACON_INVALID`.
6. **Pedido antes do round.** `requestedAt` < `timeOf(targetRound)`, ou seja, o round alvo ainda não existia no momento do pedido. Erro: `PROOF_TARGET_NOT_FUTURE`.
   - `timeOf(r) = 1692803367 + (r − 1) × 3` segundos (epoch UTC).
7. **Resultado.** O resultado recalculado com a dice-v1 (seção 4), usando `REQUEST.clientSeed`, `REQUEST.nonce`, `REQUEST.expression` e `ROUND.randomness`, é igual a `dice`, `modifier` e `total` do RESULT. Erro: `PROOF_RESULT_MISMATCH`.
8. **Resumos.** `beacon` e `roll` batem campo a campo com as entradas. Erro: `PROOF_SUMMARY_MISMATCH`.

Para verificar a mesa inteira, e não só um roll, aplique a seção 6.4 ao log completo desde o seq 1, que o servidor expõe em `GET /sessions/{id}/log`.

## 7. Cadeia com identidade do jogador (chain-v2)

A chain-v2 é a chain-v1 com uma diferença: todo REQUEST traz o `player`, assinado pelo aparelho de quem pediu o roll e ligado à identidade do jogador. Assim ninguém, nem o servidor, consegue atribuir um roll a outro jogador, trocar o apelido ou a seed depois do pedido, nem reaproveitar um pedido assinado. A conta do resultado continua sendo a `dice-v1` (seção 4), e hash, encadeamento e JSON canônico são os da seção 6.

Cada mesa tem um formato só, fixado na criação. Mesas novas são chain-v2. As mesas chain-v1 continuam legíveis e verificáveis, mas não aceitam rolls novos. O log (`GET /sessions/{id}/log`) informa o `chainFormat` da mesa.

### 7.1 Chaves, aparelhos e identidade

- Ed25519 (RFC 8032), verificação padrão da biblioteca de cada linguagem (JDK, WebCrypto e pyca/cryptography). Nada de implementação própria.
- Chaves públicas em 64 hex minúsculos (os 32 bytes crus); assinaturas em 128 hex minúsculos (64 bytes).
- Cada **aparelho** (navegador) tem a sua chave. A chave privada nasce nele e nunca sai dele; o servidor nunca a vê.
- A **identidade** do jogador é a chave do primeiro aparelho dele. Outros aparelhos entram por **vínculos**: o primeiro vínculo é assinado pela identidade, e cada vínculo seguinte pelo aparelho do vínculo anterior. Assim qualquer aparelho vinculado pode vincular outro, e o jogador continua sendo um só em todos.
- Não há revogação nesta versão: um aparelho vinculado rola em nome da identidade enquanto existir.

### 7.2 Payload do REQUEST

Os campos da seção 6.2, mais `player`, um objeto com exatamente estes seis campos:

| campo | regra |
|---|---|
| `publicKey` | chave do aparelho que assina o pedido |
| `name` | apelido: as regras da `clientSeed` (seção 3.2), mas com no máximo **32** bytes UTF-8 |
| `requestId` | 32 hex minúsculos, sorteados pelo cliente a cada pedido |
| `signature` | assinatura do aparelho sobre a mensagem do pedido |
| `identityKey` | chave da identidade do jogador |
| `links` | lista de 0 a **8** vínculos, cada um um objeto com exatamente `deviceKey` (64 hex) e `signature` (128 hex) |

ROUND e RESULT não mudam.

**Mensagem do pedido.** O aparelho (`publicKey`) assina os bytes UTF-8 do JSON canônico (seção 6.3) deste objeto:

```json
{"clientSeed": "…", "context": "dice-player-v1", "expression": "…", "identityKey": "…", "name": "…", "requestId": "…", "sessionId": "…"}
```

`expression` é a forma canônica, a mesma gravada no REQUEST. `context` separa esta assinatura de qualquer outro uso da chave. Nonce, rollId e targetRound ficam de fora porque o servidor só os define depois que o cliente assina; o `requestId` é o que impede reaproveitar a assinatura. A `identityKey` entra na mensagem: sem ela, outro jogador poderia vincular a chave do seu aparelho à identidade dele (um vínculo só exige a assinatura de quem vincula) e tomar seus pedidos (vetor `log-v2-reattributed`).

Exemplo (vetor `request-valid` de `playerSignatureVectors`):

```
{"clientSeed":"mesa|\"dragão\"","context":"dice-player-v1","expression":"2d6+3","identityKey":"3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c","name":"Thorin 🪓","requestId":"0f1e2d3c4b5a69788796a5b4c3d2e1f0","sessionId":"kq1t9m3x7c2a8z5r"}
```

**Mensagem do vínculo.** Os bytes UTF-8 do JSON canônico de:

```json
{"context": "dice-device-v1", "deviceKey": "…", "identityKey": "…"}
```

Exemplo (vetor `link-identity-to-phone` de `linkSignatureVectors`, assinado pela identidade):

```
{"context":"dice-device-v1","deviceKey":"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025","identityKey":"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"}
```

**Cadeia de vínculos.** Sem vínculos, `identityKey` tem de ser igual a `publicKey` (o aparelho é a identidade). Com vínculos, o vínculo 0 é assinado pela `identityKey`, o vínculo i pelo `deviceKey` do vínculo i − 1, e o `deviceKey` do último tem de ser a `publicKey`.

### 7.3 Verificação de um segmento

As checagens 1 a 5 da seção 6.4 e, em cada entrada REQUEST, mais estas, na ordem:

6. **Player.** `player` existe e segue a seção 7.2; `sessionId`, `clientSeed` e `expression` do payload são strings. Erro: `CHAIN_STRUCTURE`.
7. **Vínculos.** A cadeia de vínculos liga a `identityKey` à `publicKey`. Erro: `CHAIN_PLAYER_SIGNATURE`.
8. **Assinatura.** A assinatura do aparelho vale para a mensagem montada com `sessionId`, `clientSeed` e `expression` do payload e `identityKey`, `name` e `requestId` do `player`. Erro: `CHAIN_PLAYER_SIGNATURE`.
9. **Replay.** O `requestId` não apareceu em nenhum REQUEST anterior do segmento. Erro: `CHAIN_REQUEST_REPLAY`.

Como na seção 6.4, uma entrada passa por todas as checagens antes da próxima, e o primeiro erro vence. A checagem de replay só cobre o segmento recebido; para cobrir a mesa inteira, verifique o log desde o seq 1.

### 7.4 Prova de um roll

O formato da seção 6.5, com `"chainFormat": "chain-v2"` e o resumo `roll` com mais um campo, `player`, igual ao do REQUEST (inclusive `identityKey` e `links`). A verificação segue a seção 6.5 com duas diferenças:

- no passo 2, o segmento é verificado pela seção 7.3;
- no passo 8, por último, `roll.player` tem de ser igual ao `player` do REQUEST. Erro: `PROOF_SUMMARY_MISMATCH`.

Um `chainFormat` que não seja `chain-v1` nem `chain-v2` dá `PROOF_STRUCTURE`. Uma prova declarada `chain-v1` ignora o `player`, mesmo que ele exista (vetor `proof-v2-declared-v1`): ela não atesta quem rolou. Quem exibe o jogador deve exigir uma prova `chain-v2` válida.

### 7.5 Impressão da identidade (só exibição)

Para mostrar o jogador, usa-se o apelido com a impressão da **identidade**: os 4 primeiros bytes de SHA-256(bytes da `identityKey`), em hex, com um hífen no meio (`3f9a-c2e1`). É a mesma em todos os aparelhos do jogador. O apelido pode se repetir, e a impressão é o que diferencia os jogadores. A impressão não participa da verificação.

### 7.6 Vetores

Em `test-vectors.json`: `playerSignatureVectors` (inclui os vetores TEST 1 e TEST 2 do RFC 8032, para conferir a biblioteca), `linkSignatureVectors`, `logVectorsV2` (verificados pela seção 7.3) e `proofVectorsV2`. As chaves dos vetores são as dos testes 1, 2, 3 e 1024 do RFC 8032, nunca as de um jogador: Ana (identidade, TEST 1) com celular (TEST 3) e tablet (TEST 1024), e Thorin (TEST 2).

### 7.7 Pareamento de aparelhos (informativo)

Como um aparelho novo ganha o vínculo. Não faz parte da verificação: a cadeia de vínculos se sustenta sozinha. O servidor só repassa dados públicos, e os itens expiram em 10 minutos.

1. O aparelho **A**, que já rola com a identidade, sorteia um segredo `S` de 16 bytes, cria o pareamento (`POST /pairings`, devolve `pairingId`) e mostra o link `/#parear={pairingId}.{hex(S)}` em QR. O fragmento depois de `#` nunca é enviado ao servidor.
2. O aparelho **B** abre o link e registra a chave dele: `POST /pairings/{id}/device {deviceKey, mac}`, com `mac = hex(HMAC-SHA256(S, utf8("dice-pair-v1|device|" + id + "|" + deviceKey)))`.
3. **A** lê o pareamento (`GET /pairings/{id}`) e confere o `mac`: se o servidor trocasse a chave de B pela dele, o MAC não fecharia. Então A assina o vínculo de `deviceKey`, acrescenta-o aos vínculos dele e envia `POST /pairings/{id}/identity {identityKey, links, name, mac}`, com `mac = hex(HMAC-SHA256(S, utf8("dice-pair-v1|identity|" + id + "|" + canonical({identityKey, links, name}))))`. O servidor recusa vínculos que não terminem no `deviceKey` registrado.
4. **B** lê o pacote, confere o `mac` e a cadeia de vínculos até a própria chave, mostra quem vai virar (apelido e impressão) e, se o jogador confirmar, passa a assinar com `identityKey` e `links`.

Cada etapa só pode ser feita uma vez por pareamento.

### 7.8 Lista de mesas do jogador (informativo)

Para navegar entre mesas sem decorar IDs, o servidor guarda, por identidade, as mesas em que o jogador rolou ou entrou. A lista não participa da verificação e não sustenta confiança nenhuma: é só um índice. Como o ID da mesa é o que dá acesso a ela, todo pedido à lista vai assinado, senão quem visse sua chave no log de uma mesa poderia listar as outras.

`POST /me/tables` com `{auth, action, sessionId?, cursor?}`:

- `action`: `list` (página de 20, mais recentes primeiro, com `next` como cursor), `add` (entrar numa mesa sem rolar; a mesa tem de existir) ou `remove` (tirar da lista; rolar de novo a traz de volta). Rolar numa mesa também a põe na lista.
- `auth`: `{publicKey, identityKey, links, at, signature}`. O aparelho (`publicKey`) assina os bytes UTF-8 do JSON canônico de `{"action", "at", "context": "dice-tables-v1", "cursor"?, "identityKey", "sessionId"?}` (campos ausentes ficam de fora). `at` é o instante do pedido no formato de `requestedAt` (seção 6.2), aceito a até 5 minutos do relógio do servidor. Os vínculos seguem a seção 7.2.

## 8. Fora das versões atuais por enquanto

- Verificação BLS da assinatura: só no verificador do navegador (Fase 4).
