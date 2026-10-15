# infra

AWS CDK (Java) do Dado Verificável. Quatro stacks:

| Stack | Região | Quem aplica |
|---|---|---|
| `DiceGithubOidcStack` | sa-east-1 | você, uma vez, à mão (bootstrap) |
| `DiceCertStack` | us-east-1 | GitHub Actions (certificado e WAF do CloudFront, orçamento) |
| `DiceDevStack` | sa-east-1 | GitHub Actions (CloudFront, Lambda com Function URL, DynamoDB, S3, DNS, kill switch) |
| `DicePlanStack` | us-east-1 | GitHub Actions (plano flat-rate Free do CloudFront) |

O deploy normal acontece **só** pelo workflow `.github/workflows/deploy-dev.yml`, depois do CI verde na `main`.

## Desenvolvimento

```sh
./mvnw -f infra/pom.xml verify          # testes do template (CDK assertions); precisa de Node
cd infra && npx --yes aws-cdk@2.1143.0 synth -c account=111111111111 -c hostedZoneId=Z000EXAMPLE
```

O synth precisa de `dice-app/target/function.zip` (`./mvnw -pl dice-core,dice-app -am package`). Nenhuma credencial AWS é usada no synth: a zona do Route 53 entra por contexto, sem lookup.

Contexto (em `cdk.json` ou `-c chave=valor`):

| chave | padrão | |
|---|---|---|
| `hostedZoneId` | — | obrigatório para Cert/Dev; sem ele só a stack do OIDC é sintetizada |
| `zoneName` / `domainName` | `nimbos.solutions` / `dices.nimbos.solutions` | |
| `githubRepo` / `githubEnvironment` | `natanmacielc/dices` / `dev` | quem pode assumir a role de deploy |
| `existingOidcProviderArn` | — | se a conta já tem o provider OIDC do GitHub |
| `reservedConcurrency` | — | contas novas (limite 10) não podem reservar |
| `alertEmail` | — | e-mail dos alertas (SNS do kill switch e AWS Budgets); no deploy vem da variável `ALERT_EMAIL` |
| `tableReadCapacity` / `tableWriteCapacity` | `25` / `25` | capacidade provisionada do DynamoDB (a cota gratuita é 25/25 **por conta**: reduza se houver outras tabelas provisionadas) |
| `killSwitchInvocationsPerHour` | `10000` | invocações do app numa hora que o desligam |
| `killSwitchDurationMsPerHour` | `1200000` | tempo de execução (ms) do app numa hora que o desliga |
| `account` | `CDK_DEFAULT_ACCOUNT` | |

## Bootstrap (uma vez, com suas credenciais de administrador)

1. Bootstrap do CDK nas duas regiões:
   ```sh
   npx aws-cdk@2.1143.0 bootstrap aws://<CONTA>/sa-east-1 aws://<CONTA>/us-east-1
   ```
2. Role de deploy do GitHub (OIDC). Se a conta já tiver o provider `token.actions.githubusercontent.com`, acrescente `-c existingOidcProviderArn=<arn>`:
   ```sh
   cd infra && npx aws-cdk@2.1143.0 deploy DiceGithubOidcStack
   ```
   Anote a saída `DeployRoleArn`.
3. No GitHub, em *Settings → Environments*, crie o environment `dev`:
   - restrinja *Deployment branches* à `main`;
   - variáveis: `AWS_DEPLOY_ROLE_ARN` (da etapa 2), `HOSTED_ZONE_ID` (zona `nimbos.solutions` no Route 53) e, opcional, `ALERT_EMAIL` (alertas de custo e do kill switch).
4. Faça merge na `main`. O CI roda; se passar, o *Deploy dev* aplica `DiceCertStack`, `DiceDevStack` e `DicePlanStack` e roda o smoke test contra `https://dices.nimbos.solutions`.
5. Com `ALERT_EMAIL`, confirme as duas assinaturas do SNS que chegam por e-mail (sem a confirmação, o kill switch desliga o app do mesmo jeito, mas você não fica sabendo).

No primeiro deploy, a validação do certificado por DNS pode levar alguns minutos.

A role do GitHub só pode assumir as roles do bootstrap do CDK (`cdk-hnb659fds-*`); quem tem permissão de criar recursos são elas.

## Custo zero e teto de gasto

Meta: US$ 0 por mês no uso normal e, no pior caso, site fora do ar em vez de conta alta.

| Camada | Como fica de graça | O que acontece no limite |
|---|---|---|
| CloudFront + WAF + DDoS | plano flat-rate **Free** (`DicePlanStack`): 1 milhão de pedidos e 100 GB por mês, sem cobrança de excedente; pedido bloqueado pelo WAF não conta | uso alto e contínuo por meses: a AWS reduz o desempenho, não cobra |
| WAF | 3 das 5 regras do plano: 1000 pedidos/5 min por IP em tudo, 300/5 min por IP em `/api/*`, lista de IPs de má reputação da AWS | o IP toma 429 até a janela passar |
| Origem | sem API Gateway: o CloudFront chama a Function URL do Lambda (alias `live`) com OAC; a URL exige assinatura IAM | quem chamar a URL direto toma 403 |
| Lambda | cota gratuita (1 milhão de chamadas e 400 mil GB-s por mês) | **kill switch**: acima de 10 mil invocações ou 1.200 s de execução numa hora, a concorrência reservada do app vai a 0 |
| DynamoDB | provisionado em 25 RCU/25 WCU (cota gratuita permanente), sem autoscaling | o DynamoDB recusa o excedente e o app responde 503 `SERVER_BUSY`; um alarme avisa por e-mail |
| Orçamento | AWS Budgets de US$ 1/mês (com `alertEmail`) | e-mail no gasto real e no previsto |

Custos que não chegam a zero: o PITR do DynamoDB (centavos por GB) e a zona do Route 53 (US$ 0,50/mês, fora do plano porque a zona `nimbos.solutions` é compartilhada).

POST para a API precisa do header `x-amz-content-sha256` com o SHA-256 (hex) do corpo: o CloudFront assina o pedido à Function URL com esse hash. A web (`web/api.js`) e o smoke mandam.

## Derrubar e religar

Para derrubar o app à mão, faça o mesmo que o kill switch: no console da AWS (sa-east-1), *Lambda → função do app (`DiceDevStack-App…`) → Configuration → Concurrency → Edit → Reserve concurrency = 0*. O site estático continua no ar; a API responde erro.

Para religar, no mesmo lugar, escolha *Use unreserved account concurrency* (ou volte ao valor de `reservedConcurrency`, se você usa). O deploy **não** religa sozinho: a concorrência zerada fica fora do template até a propriedade mudar.

Antes de religar depois de um disparo, veja nos alarmes (`InvocationsAlarm`, `DurationAlarm`) e nas métricas do WAF o que aconteceu.
