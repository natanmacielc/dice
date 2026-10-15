# Checklist de segurança (ambiente dev)

Status: ✅ feito e coberto por teste · ⚠️ risco aceito no dev · ⏳ pendente

## Identidade e acesso
- ✅ **Deploy sem chaves de longa duração.** GitHub Actions usa OIDC; a role `dice-github-deploy` só confia em `repo:natanmacielc/dices:environment:dev` (`aud=sts.amazonaws.com`) e só pode `sts:AssumeRole` nas roles do bootstrap do CDK. Sessão de 1 h. (`githubRoleTrustsOnlyTheDevEnvironmentOfTheRepo`, `githubRoleCanOnlyAssumeCdkBootstrapRoles`)
- ✅ **Workflow de deploy não roda código de fork.** `workflow_run` exige `event == push` e o mesmo repositório (um PR de fork com branch `main` também casaria com o filtro de branch).
- ✅ **IAM mínimo do Lambda.** DynamoDB: `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `ConditionCheckItem` só na tabela; S3: `PutObject` só em `og/*`; logs pela policy básica. Nenhum curinga. (`functionRoleHasLeastPrivilege`; mutação com `dynamodb:*` quebra o teste)
- ✅ **Nenhum segredo no repositório.** Conta, role e zona entram por variáveis do environment do GitHub; varredura de segredos no repo e no `cdk.out`.
- ✅ **Actions fixadas por SHA**, versões com pelo menos duas semanas.

## Dados
- ✅ **Livro-razão protegido.** DynamoDB com PITR, deletion protection e `RETAIN`. (`ledgerTableIsProtected`)
- ✅ **A cadeia nunca bifurca.** Escrita com `TransactWriteItems` condicionada ao head (testes de concorrência e mutação na Fase 3).
- ✅ **Buckets privados.** BlockPublicAccess total, SSE-S3, `enforceSSL`, leitura só pelo CloudFront via OAC com `SourceArn` da distribuição. (`bucketsArePrivateEncryptedAndTlsOnly`; mutação de bucket público quebra o teste)

## Borda e transporte
- ✅ **TLS 1.2+ e HTTPS obrigatório** no CloudFront; certificado ACM validado por DNS. (`distributionServesTheDomainOverModernTls`, `certificateIsDnsValidatedInUsEast1`)
- ✅ **Headers de segurança em toda resposta**: CSP (`'self'` + endpoints do drand), HSTS 1 ano, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`. (`securityHeadersOnEveryResponse`)
- ✅ **API sem cache no CloudFront** e sem repassar o `Host`.
- ✅ **Throttling.** Stage: 20 rps / burst 40; `POST /sessions`: 1/5; `POST /sessions/{id}/rolls`: 5/10. (`stageThrottlesByDefaultAndPerRoute`)
- ✅ **Superfície mínima da API.** Só as 6 rotas usadas, sem `$default`. (`apiHasOnlyExplicitRoutes`)
- ⚠️ **Endpoint `execute-api` acessível direto** (sem passar pelo CloudFront). O throttling vale lá também; desligar exige domínio próprio na API.
- ⚠️ **Sem WAF** (limite por IP) no dev. Previsto para prod.
- ⚠️ **Sem access logs do CloudFront** no dev.

## Aplicação
- ✅ **Entrada validada** com os códigos da spec (seed até 64 bytes, N 1–20, X em {4,6,8,10,12,20,100}, |M| ≤ 100).
- ✅ **Sem XSS.** Qute escapa tudo no `/r/{id}`; o JS só usa `textContent`. (`userTextIsEscapedInHtml`)
- ✅ **SnapStart e unicidade.** IDs usam `java.security.SecureRandom`, que a AWS lista como seguro com SnapStart (o Lambda refaz a semente no restore). As fontes extraídas para `/tmp` fazem parte do snapshot (memória e disco).
- ✅ **drand.** Backend: chain hash fixo, `randomness == SHA-256(signature)`, guarda de relógio no latest e round alvo exato. Navegador: busca o round direto no drand e verifica a assinatura BLS com o drand-client oficial vendorizado (SHA-256 registrado).
- ⚠️ **Mesa sem token de escrita.** Qualquer um com o ID da mesa pode rolar nela. Ninguém altera resultados de ninguém, mas dá para poluir o log da mesa.

## Pendências
- ⏳ Branch protection da `main` (CI obrigatório) e regras do environment `dev`.
- ⏳ Dependabot (actions, Maven, npm do vendor).
- ⏳ Alarmes (erros e throttles do Lambda/API) e orçamento (AWS Budgets).
- ⏳ Prod: WAF, token de mesa, access logs.
