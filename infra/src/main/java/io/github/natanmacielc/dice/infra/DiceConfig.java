package io.github.natanmacielc.dice.infra;

import java.util.Optional;
import software.constructs.Node;

/** Entradas do CDK, todas por contexto (cdk.json ou -c chave=valor). Nada aqui é segredo. */
public record DiceConfig(String account, Optional<String> hostedZoneId, String zoneName, String domainName,
                         String githubRepo, String githubEnvironment, Optional<String> existingOidcProviderArn,
                         String functionZip, String webDir, Optional<Integer> reservedConcurrency) {

    public static final String REGION = "sa-east-1";
    public static final String CERT_REGION = "us-east-1"; // CloudFront só aceita certificados de us-east-1

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
                optional(node, "reservedConcurrency").map(Integer::parseInt));
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

    private static String required(Node node, String key) {
        return optional(node, key).orElseThrow(() -> new IllegalStateException("contexto ausente: " + key));
    }
}
