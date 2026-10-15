package io.github.natanmacielc.dice.app.pairing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.natanmacielc.dice.app.ledger.DynamoConfig;
import io.github.natanmacielc.dice.app.ledger.Ids;
import io.github.natanmacielc.dice.core.chain.Player;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Repasse do pareamento de aparelhos (seção 7.7). O servidor não participa da confiança: guarda a chave do
 * aparelho novo e o pacote de vínculos, cada um uma única vez, e os dois lados conferem os MACs. Os itens
 * expiram em 10 minutos (TTL do DynamoDB em {@code expiresAt}, conferido também na leitura).
 */
@ApplicationScoped
public class PairingService {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final TypeReference<List<Player.Link>> LINKS = new TypeReference<>() {
    };

    private final DynamoDbClient dynamo;
    private final String table;
    private final Clock clock;
    private final ObjectMapper json;

    public PairingService(DynamoDbClient dynamo, DynamoConfig config, Clock clock, ObjectMapper json) {
        this.dynamo = dynamo;
        this.table = config.table();
        this.clock = clock;
        this.json = json;
    }

    public PairingRecord create() {
        String id = Ids.newId();
        long expiresAt = clock.instant().plus(TTL).getEpochSecond();
        Map<String, AttributeValue> item = new HashMap<>(key(id));
        item.put("pairingId", AttributeValue.fromS(id));
        item.put("expiresAt", AttributeValue.fromN(Long.toString(expiresAt)));
        dynamo.putItem(r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(PK)"));
        return new PairingRecord(id, expiresAt, null, null);
    }

    public PairingRecord get(String id) {
        if (!Ids.isValid(id)) {
            throw notFound(id);
        }
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).key(key(id)).consistentRead(true)).item();
        if (item == null || item.isEmpty() || expired(item)) {
            throw notFound(id);
        }
        PairingRecord.Device device = item.containsKey("deviceKey")
                ? new PairingRecord.Device(item.get("deviceKey").s(), item.get("deviceMac").s()) : null;
        PairingRecord.Identity identity = null;
        if (item.containsKey("identityKey")) {
            try {
                identity = new PairingRecord.Identity(item.get("identityKey").s(),
                        json.readValue(item.get("links").s(), LINKS), item.get("name").s(), item.get("identityMac").s());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("vínculos ilegíveis no pareamento " + id, e);
            }
        }
        return new PairingRecord(id, Long.parseLong(item.get("expiresAt").n()), device, identity);
    }

    /** Etapa 2: o aparelho novo registra a chave dele, com o MAC feito com o segredo do link. */
    public PairingRecord registerDevice(String id, String deviceKey, String mac) {
        if (!hex64(deviceKey) || !hex64(mac)) {
            throw new PairingException(400, "PAIRING_INVALID", "deviceKey e mac devem ter 64 hex minúsculos");
        }
        get(id);
        Map<String, AttributeValue> values = Map.of(
                ":key", AttributeValue.fromS(deviceKey),
                ":mac", AttributeValue.fromS(mac),
                ":now", AttributeValue.fromN(Long.toString(clock.instant().getEpochSecond())));
        update(id, "SET deviceKey = :key, deviceMac = :mac",
                "attribute_exists(PK) AND attribute_not_exists(deviceKey) AND expiresAt > :now", values);
        return get(id);
    }

    /**
     * Etapa 3: o aparelho que já tem a identidade manda os vínculos até a chave registrada. Os vínculos têm
     * de fechar (mesma regra da verificação do REQUEST), senão o aparelho novo não conseguiria rolar.
     */
    public PairingRecord registerIdentity(String id, String identityKey, List<Player.Link> links, String name,
                                          String mac) {
        if (!hex64(identityKey) || !hex64(mac) || links == null || links.isEmpty()
                || Player.linksProblem(links) != null || Player.nameProblem(name) != null) {
            throw new PairingException(400, "PAIRING_INVALID",
                    "identityKey e mac com 64 hex, de 1 a " + Player.MAX_LINKS + " vínculos e apelido de 1 a "
                            + Player.MAX_NAME_BYTES + " bytes");
        }
        PairingRecord pairing = get(id);
        if (pairing.device() == null) {
            throw new PairingException(409, "PAIRING_WAITING_DEVICE", "o aparelho novo ainda não se registrou");
        }
        if (pairing.identity() != null) {
            throw done(id);
        }
        if (!Player.linksValid(identityKey, links, pairing.device().deviceKey())) {
            throw new PairingException(400, "PAIRING_LINK_INVALID",
                    "os vínculos não ligam a identidade à chave do aparelho novo");
        }
        String linksJson;
        try {
            linksJson = json.writeValueAsString(links);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Map<String, AttributeValue> values = Map.of(
                ":identity", AttributeValue.fromS(identityKey),
                ":links", AttributeValue.fromS(linksJson),
                ":name", AttributeValue.fromS(name),
                ":mac", AttributeValue.fromS(mac),
                ":now", AttributeValue.fromN(Long.toString(clock.instant().getEpochSecond())));
        update(id, "SET identityKey = :identity, links = :links, #name = :name, identityMac = :mac",
                "attribute_exists(deviceKey) AND attribute_not_exists(identityKey) AND expiresAt > :now", values);
        return get(id);
    }

    private void update(String id, String expression, String condition, Map<String, AttributeValue> values) {
        try {
            dynamo.updateItem(r -> {
                r.tableName(table).key(key(id)).updateExpression(expression).conditionExpression(condition)
                        .expressionAttributeValues(values);
                if (expression.contains("#name")) {
                    r.expressionAttributeNames(Map.of("#name", "name")); // name é palavra reservada do DynamoDB
                }
            });
        } catch (ConditionalCheckFailedException e) {
            get(id); // expirado ou inexistente vira 404
            throw done(id);
        }
    }

    private boolean expired(Map<String, AttributeValue> item) {
        return Long.parseLong(item.get("expiresAt").n()) <= clock.instant().getEpochSecond();
    }

    private static boolean hex64(String s) {
        return s != null && KEY.matcher(s).matches();
    }

    private static Map<String, AttributeValue> key(String id) {
        return Map.of("PK", AttributeValue.fromS("PAIRING#" + id), "SK", AttributeValue.fromS("META"));
    }

    private static PairingException notFound(String id) {
        return new PairingException(404, "NOT_FOUND", "pareamento " + id + " não existe ou expirou");
    }

    private static PairingException done(String id) {
        return new PairingException(409, "PAIRING_STEP_DONE", "esta etapa do pareamento " + id + " já foi feita");
    }
}
