# Dado Verificável

Sistema que prova que rolagens de dados em RPGs são imparciais. O resultado deriva de um valor público do drand que não existia quando o pedido foi feito; qualquer pessoa recalcula e confere. O servidor é só um livro-razão: não pode prever, escolher nem reescrever resultados.

## Arquitetura

Monólito modular, Java 21, Maven multi-módulo, um único artefato implantável. Sem microsserviços.

- `spec/`: ALGORITHM.md e test-vectors.json. Fonte da verdade do algoritmo.
- `dice-core/`: Java puro, só JDK. Rolagem, parser e verificador.
- `dice-app/`: Quarkus. REST, DynamoDB, S3, imagem OG e cliente drand.
- `web/`: site estático e verificador em JS.
- `tools/verify-py/`: verificador independente em Python.
- `infra/`: AWS CDK em Java.

Deploy: CloudFront, API Gateway HTTP API, Lambda (Quarkus, SnapStart), DynamoDB on-demand e S3. CI/CD no GitHub Actions com OIDC.

## Comandos

JDK 21 obrigatório (o enforcer bloqueia versões menores). Nesta máquina o `java` padrão é 17, então antes do Maven rode `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

- Build e testes: `./mvnw -q verify`. Os testes do dice-app precisam do Docker (DynamoDB Local via Testcontainers).
- Só o núcleo: `./mvnw -q -pl dice-core test`
- Verificador Python: `python3 -m pytest tools/verify-py`. Localmente, use o venv: `python3 -m venv .venv && .venv/bin/pip install pytest==9.1.1`, depois `.venv/bin/python -m pytest tools/verify-py`.
- CLI Python: `cd tools/verify-py && python3 -m dice_verify --randomness HEX --seed TEXTO --nonce N --expr 2d6+3 [--signature HEX]`
- Verificar uma prova: `cd tools/verify-py && python3 -m dice_verify.proof prova.json`
- Verificador JS: `node --test "web/**/*.test.mjs"`. O Node 22 não aceita diretório como argumento do `--test`.
- Regerar vetores (só quando a spec mudar, com versão nova): `./mvnw -q -pl dice-core compile`, depois `java -cp dice-core/target/classes tools/vectorgen/GenerateVectors.java spec/test-vectors.json`. Para achar seeds com rejeição real, use `tools/vectorgen/FindRejections.java` (leva alguns minutos).
- Teste contra o drand real (fora do CI): `./mvnw -pl dice-app -am test -Plive`
- Infra, sem deploy: `cdk synth` e `cdk diff` (Fase 5; o módulo infra/ fica fora do reactor Maven).
- CI: `.github/workflows/ci.yml` roda os três jobs (Java, Python e JS) em todo PR e push na main e confere se `spec/test-vectors.json` bate com o gerador. As actions são fixadas por SHA.
- Guarda da invariante 4: o enforcer do dice-core falha o build se aparecer uma dependência que não seja de teste.

## Algoritmo v1 (detalhes em spec/ALGORITHM.md)

- Rede: drand quicknet, chain hash fixo no código, rounds de 3 segundos.
- Pedido: clientSeed (UTF-8, 1 a 64 bytes, escolhida antes do roll), expressão NdX+M e nonce sequencial por mesa. targetRound = latest.round + 2.
- Cálculo: bloco(c) = SHA-256(randomness + "|" + clientSeed + "|" + nonce + "|" + c). Use palavras de 4 bytes big-endian sem sinal, em ordem. Descarte v >= 2^32 - (2^32 % lados). Resultado = v % lados + 1.
- Cadeia de hash (chain-v1): uma por mesa, entradas REQUEST, ROUND e RESULT. hash = SHA-256(prevHash + tipo + payload canônico). Payload em JSON canônico (subconjunto do RFC 8785) com seq, sessionId e rollId. prevHash da seq 1 = 64 zeros. Nonce começa em 1.
- Resolução sob demanda: qualquer leitura (roll, prova, log) resolve pendentes cujo round alvo já existe. EXPIRED é só exibição (pendente mais de 60 s após o round alvo); não é estado gravado.
- IDs de mesa e roll: 16 caracteres base32 Crockford minúsculo (80 bits). Página do roll: /r/{rollId}.
- Não existe server_seed.
- Verificação BLS: só no navegador (Fase 4). O backend checa randomness == SHA-256(signature) e o guarda de relógio: latest só é aceito a ±1 round do esperado por floor((agora - genesis)/3) + 1.

## Invariantes (nunca quebrar)

1. Usar o randomness do round ALVO, nunca o latest.
2. A cadeia nunca bifurca: escrita atômica com condição de seq.
3. Mudou o algoritmo? Suba a versão (v2) e atualize spec/, test-vectors e os três verificadores (Java, JS, Python) no mesmo PR. Nunca altere a v1 silenciosamente.
4. dice-core não depende de nada além do JDK.
5. Não implemente criptografia própria. Use MessageDigest e bibliotecas consolidadas.
6. O randomness do drand deve bater com SHA-256(signature).
7. O log público inclui pedidos pendentes e expirados.

## Como trabalhar

- Comece em plan mode e espere minha aprovação do plano.
- Uma fase por vez. Ao fim, rode os testes, resuma o que foi feito e pare.
- Não altere comportamento fora do escopo da fase. Sem refatoração oportunista.
- Em caso de ambiguidade, pergunte e liste as suposições.
- Núcleo com testes primeiro. Cada bug corrigido ganha um teste de regressão.

## Segurança

- Nunca leia nem grave segredos. Nada de credenciais no repositório.
- Nunca rode deploy: `cdk deploy` e comandos `aws` são bloqueados e passam só pelo GitHub Actions.
- Valide toda entrada: clientSeed até 64 bytes; N de 1 a 20; X em 4, 6, 8, 10, 12, 20 ou 100; |M| <= 100.
- IAM com permissão mínima.

## Git

- Uma branch por fase, PR com CI verde, commits no padrão Conventional Commits.
- Nunca faça force push nem commit direto na main.