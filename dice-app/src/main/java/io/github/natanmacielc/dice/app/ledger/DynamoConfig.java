package io.github.natanmacielc.dice.app.ledger;

import io.smallrye.config.ConfigMapping;
import java.net.URI;
import java.util.Optional;

@ConfigMapping(prefix = "dice.dynamodb")
public interface DynamoConfig {

    String table();

    /** Só para DynamoDB Local (testes e desenvolvimento). Em produção fica vazio. */
    Optional<URI> endpointOverride();
}
