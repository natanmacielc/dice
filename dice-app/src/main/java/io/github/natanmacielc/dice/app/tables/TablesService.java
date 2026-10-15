package io.github.natanmacielc.dice.app.tables;

import io.github.natanmacielc.dice.app.ledger.DynamoConfig;
import io.github.natanmacielc.dice.app.ledger.Ids;
import io.github.natanmacielc.dice.app.ledger.LedgerRepository;
import io.github.natanmacielc.dice.app.ledger.SessionRecord;
import io.github.natanmacielc.dice.core.chain.Payloads;
import io.github.natanmacielc.dice.core.chain.Player;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Lista de mesas por identidade (spec/ALGORITHM.md, seção 7.8). Não sustenta confiança nenhuma: só
 * ajuda a navegar. Item {@code IDENTITY#{identityKey}} / {@code TABLE#{sessionId}}, gravado ao rolar e
 * ao entrar numa mesa. Toda operação exige pedido assinado por um aparelho da identidade, porque o ID
 * da mesa é o que dá acesso a ela.
 */
@ApplicationScoped
public class TablesService {

    private static final Logger LOG = Logger.getLogger(TablesService.class);
    static final Duration MAX_SKEW = Duration.ofMinutes(5);
    static final int PAGE = 20;
    static final int MAX_TABLES = 1000;
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{128}");
    private static final Comparator<TableRef> RECENT_FIRST =
            Comparator.comparing(TableRef::lastActiveAt).thenComparing(TableRef::sessionId).reversed();

    /** Autenticação do pedido: aparelho, identidade, vínculos até o aparelho, instante e assinatura. */
    public record Auth(String publicKey, String identityKey, List<Player.Link> links, String at, String signature) {
    }

    public record Page(List<TableRef> tables, String next) {
    }

    private final DynamoDbClient dynamo;
    private final String table;
    private final Clock clock;
    private final LedgerRepository ledger;

    public TablesService(DynamoDbClient dynamo, DynamoConfig config, Clock clock, LedgerRepository ledger) {
        this.dynamo = dynamo;
        this.table = config.table();
        this.clock = clock;
        this.ledger = ledger;
    }

    /**
     * Confere o pedido assinado e devolve a identidade. Mensagem: {@link Player#tablesMessage}; o aparelho
     * tem de estar ligado à identidade pelos vínculos, e {@code at} a no máximo 5 minutos do relógio.
     */
    public String authenticate(Auth auth, String action, String sessionId, String cursor) {
        if (auth == null || auth.publicKey() == null || !KEY.matcher(auth.publicKey()).matches()
                || auth.identityKey() == null || !KEY.matcher(auth.identityKey()).matches()
                || Player.linksProblem(auth.links()) != null || auth.at() == null
                || auth.signature() == null || !SIGNATURE.matcher(auth.signature()).matches()) {
            throw invalid("auth mal formado");
        }
        Instant at;
        try {
            at = Payloads.parseInstant(auth.at());
        } catch (RuntimeException e) {
            throw invalid("at fora do formato");
        }
        if (Duration.between(at, clock.instant()).abs().compareTo(MAX_SKEW) > 0) {
            throw new TablesException(401, "AUTH_EXPIRED", "pedido assinado há mais de 5 minutos (ou relógio adiantado)");
        }
        byte[] message = Player.tablesMessage(action, auth.identityKey(), auth.at(), sessionId, cursor);
        if (!Player.linksValid(auth.identityKey(), auth.links(), auth.publicKey())
                || !Player.verify(auth.publicKey(), message, auth.signature())) {
            throw invalid("assinatura ou vínculos não conferem");
        }
        return auth.identityKey();
    }

    public Page list(String identityKey, String cursor) {
        List<TableRef> all = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            Map<String, AttributeValue> exclusiveStart = startKey;
            QueryResponse page = dynamo.query(QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(pk(identityKey)),
                            ":prefix", AttributeValue.fromS("TABLE#")))
                    .limit(MAX_TABLES - all.size())
                    .exclusiveStartKey(exclusiveStart)
                    .build());
            page.items().forEach(item -> all.add(new TableRef(item.get("sessionId").s(),
                    item.containsKey("name") ? item.get("name").s() : null,
                    item.get("joinedAt").s(), item.get("lastActiveAt").s())));
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null && all.size() < MAX_TABLES);
        all.sort(RECENT_FIRST);

        int from = 0;
        if (cursor != null) {
            TableRef after = decodeCursor(cursor);
            while (from < all.size() && RECENT_FIRST.compare(all.get(from), after) <= 0) {
                from++;
            }
        }
        int to = Math.min(all.size(), from + PAGE);
        String next = to < all.size() ? encodeCursor(all.get(to - 1)) : null;
        return new Page(List.copyOf(all.subList(from, to)), next);
    }

    /** Entrar numa mesa sem rolar (criar, entrar por ID ou por convite). 404 se a mesa não existe. */
    public TableRef add(String identityKey, String sessionId) {
        SessionRecord session = (sessionId == null || !Ids.isValid(sessionId)) ? null
                : ledger.findSession(sessionId).orElse(null);
        if (session == null) {
            throw new TablesException(404, "NOT_FOUND", "mesa " + sessionId);
        }
        return touch(identityKey, session);
    }

    public void remove(String identityKey, String sessionId) {
        if (sessionId == null || !Ids.isValid(sessionId)) {
            throw new TablesException(400, "INVALID_REQUEST", "sessionId inválido");
        }
        dynamo.deleteItem(r -> r.tableName(table).key(key(identityKey, sessionId)));
    }

    /** Registra atividade na mesa. Chamado depois de gravar um REQUEST: falha aqui só vira log. */
    public void touchQuietly(String identityKey, SessionRecord session) {
        try {
            touch(identityKey, session);
        } catch (RuntimeException e) {
            LOG.warnf("lista de mesas não atualizada (%s): %s", session.sessionId(), e.toString());
        }
    }

    private TableRef touch(String identityKey, SessionRecord session) {
        String now = Payloads.formatInstant(clock.instant());
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":sid", AttributeValue.fromS(session.sessionId()));
        values.put(":now", AttributeValue.fromS(now));
        String update = "SET sessionId = :sid, lastActiveAt = :now, joinedAt = if_not_exists(joinedAt, :now)";
        if (session.name() != null) {
            values.put(":name", AttributeValue.fromS(session.name()));
            update += ", #name = :name";
        }
        String expression = update;
        Map<String, AttributeValue> item = dynamo.updateItem(r -> {
            r.tableName(table).key(key(identityKey, session.sessionId())).updateExpression(expression)
                    .expressionAttributeValues(values).returnValues("ALL_NEW");
            if (expression.contains("#name")) {
                r.expressionAttributeNames(Map.of("#name", "name")); // name é palavra reservada do DynamoDB
            }
        }).attributes();
        return new TableRef(session.sessionId(), session.name(), item.get("joinedAt").s(), now);
    }

    private static String encodeCursor(TableRef t) {
        String raw = t.lastActiveAt() + "|" + t.sessionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static TableRef decodeCursor(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length == 2 && Ids.isValid(parts[1])) {
                return new TableRef(parts[1], null, null, parts[0]);
            }
        } catch (IllegalArgumentException e) {
            // cai no erro abaixo
        }
        throw new TablesException(400, "INVALID_REQUEST", "cursor inválido");
    }

    private static String pk(String identityKey) {
        return "IDENTITY#" + identityKey;
    }

    private static Map<String, AttributeValue> key(String identityKey, String sessionId) {
        return Map.of("PK", AttributeValue.fromS(pk(identityKey)), "SK", AttributeValue.fromS("TABLE#" + sessionId));
    }

    private static TablesException invalid(String message) {
        return new TablesException(401, "AUTH_INVALID", message);
    }
}
