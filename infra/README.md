# infra

AWS CDK em Java (Fase 5). Fica fora do reactor Maven para que `./mvnw verify` não baixe o aws-cdk-lib. Nunca rode `cdk deploy` localmente: o deploy passa só pelo GitHub Actions.
