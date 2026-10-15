package io.github.natanmacielc.dice.infra;

import java.util.Optional;
import software.constructs.Node;

/** Entradas do CDK, todas por contexto (cdk.json ou -c chave=valor). Nada aqui é segredo. */
public record DiceConfig(String account, Optional<String> hostedZoneId, String zoneName, String domainName,
                         String githubRepo, String githubEnvironment, Optional<String> existingOidcProviderArn,
                         String functionZip, String webDir, Optional<Integer> reservedConcurrency,
                         Optional<String> alertEmail, int tableReadCapacity, int tableWriteCapacity,
                         int killSwitchInvocationsPerHour, int killSwitchDurationMsPerHour) {

    public static final String REGION = "sa-east-1";
    public static final String CERT_REGION = "us-east-1"; // CloudFront só aceita certificados e WAF de us-east-1

    /** Cota gratuita permanente do DynamoDB provisionado (por conta): 25 RCU e 25 WCU. */
    static final int FREE_TIER_CAPACITY = 25;
    /** Acima disso numa hora o app é desligado (a cota gratuita do Lambda dá ~1.400 chamadas/h no mês). */
    static final int DEFAULT_KILL_INVOCATIONS_PER_HOUR = 10_000;
    /** 1.200 s de execução por hora a 1 GB = 1.200 GB-s/h (a cota gratuita é 400.000 GB-s/mês). */
    static final int DEFAULT_KILL_DURATION_MS_PER_HOUR = 1_200_000;

    static DiceConfig from(Node node) {
        String account = optional(node, "account").orElse(System.getenv("CDK_DEFAULT_ACCOUNT"));
        if (account == null || account.isBlank()) {
            throw new IllegalStateException("conta AWS ausente: CDK_DEFAULT_ACCOUNT ou -c account=...");
        }
        return new DiceConfig(account,
                optional(node, "hostedZoneId"),
                required(node, "zoneName"),
                required(node, "domainName"),
                required(node, "githubRepo"),
                required(node, "githubEnvironment"),
                optional(node, "existingOidcProviderArn"),
                required(node, "functionZip"),
                required(node, "webDir"),
                optional(node, "reservedConcurrency").map(Integer::parseInt),
                optional(node, "alertEmail"),
                integer(node, "tableReadCapacity", FREE_TIER_CAPACITY),
                integer(node, "tableWriteCapacity", FREE_TIER_CAPACITY),
                integer(node, "killSwitchInvocationsPerHour", DEFAULT_KILL_INVOCATIONS_PER_HOUR),
                integer(node, "killSwitchDurationMsPerHour", DEFAULT_KILL_DURATION_MS_PER_HOUR));
    }

    String publicBaseUrl() {
        return "https://" + domainName;
    }

    String recordName() {
        if (!domainName.endsWith("." + zoneName)) {
            throw new IllegalStateException(domainName + " não está na zona " + zoneName);
        }
        return domainName.substring(0, domainName.length() - zoneName.length() - 1);
    }

    private static Optional<String> optional(Node node, String key) {
        Object v = node.tryGetContext(key);
        return v == null || v.toString().isBlank() ? Optional.empty() : Optional.of(v.toString());
    }

    private static int integer(Node node, String key, int fallback) {
        return optional(node, key).map(Integer::parseInt).orElse(fallback);
    }

    private static String required(Node node, String key) {
        return optional(node, key).orElseThrow(() -> new IllegalStateException("contexto ausente: " + key));
    }
}
