# infra

AWS CDK (Java) do Dado Verificável. Três stacks:

| Stack | Região | Quem aplica |
|---|---|---|
| `DiceGithubOidcStack` | sa-east-1 | você, uma vez, à mão (bootstrap) |
| `DiceCertStack` | us-east-1 | GitHub Actions (certificado do CloudFront) |
| `DiceDevStack` | sa-east-1 | GitHub Actions (CloudFront, API, Lambda, DynamoDB, S3, DNS) |

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
   - variáveis: `AWS_DEPLOY_ROLE_ARN` (da etapa 2) e `HOSTED_ZONE_ID` (zona `nimbos.solutions` no Route 53).
4. Faça merge na `main`. O CI roda; se passar, o *Deploy dev* aplica `DiceCertStack` e `DiceDevStack` e roda o smoke test contra `https://dices.nimbos.solutions`.

No primeiro deploy, a validação do certificado por DNS pode levar alguns minutos.

A role do GitHub só pode assumir as roles do bootstrap do CDK (`cdk-hnb659fds-*`); quem tem permissão de criar recursos são elas.
