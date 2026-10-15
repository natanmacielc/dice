package io.github.natanmacielc.dice.app.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.natanmacielc.dice.app.beacon.Beacon;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.RollResult;
import io.github.natanmacielc.dice.core.chain.CanonicalJson;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.EntryType;
import io.github.natanmacielc.dice.core.chain.Payloads;
import io.github.natanmacielc.dice.core.chain.Player;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Livro-razão no DynamoDB (tabela única PK/SK). Toda escrita na cadeia é um TransactWriteItems
 * condicionado ao headSeq lido, mais attribute_not_exists na entrada: a cadeia nunca bifurca
 * (invariante 2).
 */
@ApplicationScoped
public class LedgerRepository {

    private static final Logger LOG = Logger.getLogger(LedgerRepository.class);
    static final int MAX_ATTEMPTS = 30;
    private static final String META = "META";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final DynamoDbClient dynamo;
    private final String table;
    private final Clock clock;
    private final ObjectMapper json;

    public LedgerRepository(DynamoDbClient dynamo, DynamoConfig config, Clock clock, ObjectMapper json) {
        this.dynamo = dynamo;
        this.table = config.table();
        this.clock = clock;
        this.json = json;
    }

    // ---------- Mesas ----------

    /** Mesa nova, sempre chain-v2. */
    public SessionRecord createSession(String name) {
        String sessionId = Ids.newId();
        String createdAt = Payloads.formatInstant(clock.instant());
        Map<String, AttributeValue> item = new HashMap<>(key(sessionPk(sessionId), META));
        item.put("sessionId", s(sessionId));
        if (name != null) {
            item.put("name", s(name));
        }
        item.put("createdAt", s(createdAt));
        item.put("headSeq", n(0));
        item.put("headHash", s(ChainEntry.GENESIS_PREV_HASH));
        item.put("nextNonce", n(1));
        item.put("chainFormat", s(ChainFormat.CHAIN_V2.id()));
        dynamo.putItem(r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(PK)"));
        return new SessionRecord(sessionId, name, createdAt, 0, ChainEntry.GENESIS_PREV_HASH, 1, ChainFormat.CHAIN_V2);
    }

    public Optional<SessionRecord> findSession(String sessionId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table)
                .key(key(sessionPk(sessionId), META)).consistentRead(true)).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        ChainFormat format = item.containsKey("chainFormat")
                ? ChainFormat.fromId(str(item, "chainFormat")) : ChainFormat.CHAIN_V1;
        return Optional.of(new SessionRecord(str(item, "sessionId"), item.containsKey("name") ? str(item, "name") : null,
                str(item, "createdAt"), num(item, "headSeq"), str(item, "headHash"), num(item, "nextNonce"), format));
    }

    // ---------- Rolls ----------

    public Optional<RollRecord> findRoll(String rollId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table)
                .key(key(rollPk(rollId), META)).consistentRead(true)).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        boolean resolved = RollRecord.RESOLVED.equals(str(item, "status"));
        return Optional.of(new RollRecord(str(item, "rollId"), str(item, "sessionId"), num(item, "nonce"),
                str(item, "clientSeed"), str(item, "expression"), num(item, "targetRound"), str(item, "requestedAt"),
                num(item, "requestSeq"), str(item, "status"),
                resolved ? num(item, "resultSeq") : null,
                resolved ? num(item, "round") : null,
                resolved ? str(item, "randomness") : null,
                resolved ? str(item, "signature") : null,
                resolved ? item.get("dice").l().stream().map(v -> Integer.parseInt(v.n())).toList() : null,
                resolved ? (int) num(item, "modifier") : null,
                resolved ? (int) num(item, "total") : null,
                item.containsKey("ogImage") && Boolean.TRUE.equals(item.get("ogImage").bool()),
                item.containsKey("player") ? toPlayer(item.get("player").m()) : null));
    }

    /**
     * Grava o REQUEST assinado (chain-v2). O nonce sai do nextNonce da mesa, na mesma transação, e o
     * marcador REQ#{requestId} impede reaproveitar o pedido. requestedAt é o relógio de cada tentativa;
     * se o round alvo já tiver sido publicado, desiste (o pedido não pode ser gravado depois de o
     * resultado existir).
     */
    public Requested appendRequest(String sessionId, String clientSeed, String expression, long targetRound,
                                   Player player) {
        String rollId = Ids.newId();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            if (!now.isBefore(Quicknet.timeOf(targetRound))) {
                throw new ChainContentionException("round alvo " + targetRound + " publicado antes da gravação do pedido");
            }
            SessionRecord session = findSession(sessionId)
                    .orElseThrow(() -> new NotFoundException("mesa " + sessionId));
            long seq = session.headSeq() + 1;
            long nonce = session.nextNonce();
            String requestedAt = Payloads.formatInstant(now);
            ChainEntry entry = ChainEntry.create(seq, EntryType.REQUEST, session.headHash(),
                    Payloads.request(seq, sessionId, rollId, clientSeed, expression, nonce, targetRound, now, player));

            Map<String, AttributeValue> roll = new HashMap<>(key(rollPk(rollId), META));
            roll.put("rollId", s(rollId));
            roll.put("sessionId", s(sessionId));
            roll.put("nonce", n(nonce));
            roll.put("clientSeed", s(clientSeed));
            roll.put("expression", s(expression));
            roll.put("targetRound", n(targetRound));
            roll.put("requestedAt", s(requestedAt));
            roll.put("requestSeq", n(seq));
            roll.put("status", s(RollRecord.PENDING));
            roll.put("player", AttributeValue.fromM(Map.of(
                    "publicKey", s(player.publicKey()),
                    "name", s(player.name()),
                    "requestId", s(player.requestId()),
                    "signature", s(player.signature()),
                    "identityKey", s(player.identityKey()),
                    "links", AttributeValue.fromL(player.links().stream()
                            .map(l -> AttributeValue.fromM(Map.of("deviceKey", s(l.deviceKey()), "signature", s(l.signature()))))
                            .toList()))));
            Map<String, AttributeValue> pending = new HashMap<>(key(sessionPk(sessionId), "PENDING#" + rollId));
            pending.put("rollId", s(rollId));
            pending.put("targetRound", n(targetRound));
            Map<String, AttributeValue> requestMarker = new HashMap<>(key(sessionPk(sessionId), requestSk(player)));
            requestMarker.put("rollId", s(rollId));

            List<TransactWriteItem> items = List.of(
                    advanceHead(sessionId, session.headSeq(), seq, entry.hash(), nonce + 1),
                    putEntry(sessionId, entry),
                    put(roll),
                    put(pending),
                    put(requestMarker));
            Outcome outcome = transact(items, "pedido na mesa " + sessionId, attempt);
            if (outcome == Outcome.DONE) {
                return new Requested(new RollRecord(rollId, sessionId, nonce, clientSeed, expression, targetRound,
                        requestedAt, seq, RollRecord.PENDING, null, null, null, null, null, null, null, false, player),
                        entry);
            }
            if (outcome == Outcome.REQUEST_REPLAYED) {
                throw new RequestReplayException("requestId " + player.requestId() + " já usado nesta mesa");
            }
        }
        throw new ChainContentionException("mesa " + sessionId + " ocupada; tente de novo");
    }

    /**
     * Grava ROUND e RESULT em sequência. O roll só muda se ainda estiver PENDING, então dois
     * resolvedores concorrentes não duplicam entradas: o perdedor relê e devolve o estado atual
     * com {@code won = false}.
     */
    public Resolution appendResolution(RollRecord roll, Beacon beacon, RollResult result) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            SessionRecord session = findSession(roll.sessionId())
                    .orElseThrow(() -> new NotFoundException("mesa " + roll.sessionId()));
            long roundSeq = session.headSeq() + 1;
            long resultSeq = roundSeq + 1;
            ChainEntry roundEntry = ChainEntry.create(roundSeq, EntryType.ROUND, session.headHash(),
                    Payloads.round(roundSeq, roll.sessionId(), roll.rollId(), beacon.round(), beacon.randomness(),
                            beacon.signature()));
            ChainEntry resultEntry = ChainEntry.create(resultSeq, EntryType.RESULT, roundEntry.hash(),
                    Payloads.result(resultSeq, roll.sessionId(), roll.rollId(), result.dice(), result.modifier(),
                            result.total()));

            Map<String, AttributeValue> values = new HashMap<>();
            values.put(":resolved", s(RollRecord.RESOLVED));
            values.put(":pending", s(RollRecord.PENDING));
            values.put(":roundSeq", n(roundSeq));
            values.put(":resultSeq", n(resultSeq));
            values.put(":round", n(beacon.round()));
            values.put(":randomness", s(beacon.randomness()));
            values.put(":signature", s(beacon.signature()));
            values.put(":dice", AttributeValue.fromL(result.dice().stream().map(d -> n(d)).toList()));
            values.put(":modifier", n(result.modifier()));
            values.put(":total", n(result.total()));
            values.put(":resolvedAt", s(Payloads.formatInstant(clock.instant())));
            TransactWriteItem markResolved = TransactWriteItem.builder().update(Update.builder()
                    .tableName(table)
                    .key(key(rollPk(roll.rollId()), META))
                    // Nomes via #alias: vários (round, total, status...) são palavras reservadas do DynamoDB.
                    .updateExpression("SET #status = :resolved, #roundSeq = :roundSeq, #resultSeq = :resultSeq, "
                            + "#round = :round, #randomness = :randomness, #signature = :signature, #dice = :dice, "
                            + "#modifier = :modifier, #total = :total, #resolvedAt = :resolvedAt")
                    .conditionExpression("#status = :pending")
                    .expressionAttributeNames(Map.of("#status", "status", "#roundSeq", "roundSeq",
                            "#resultSeq", "resultSeq", "#round", "round", "#randomness", "randomness",
                            "#signature", "signature", "#dice", "dice", "#modifier", "modifier", "#total", "total",
                            "#resolvedAt", "resolvedAt"))
                    .expressionAttributeValues(values)
                    .build()).build();
            TransactWriteItem dropPending = TransactWriteItem.builder().delete(Delete.builder()
                    .tableName(table)
                    .key(key(sessionPk(roll.sessionId()), "PENDING#" + roll.rollId()))
                    .build()).build();

            List<TransactWriteItem> items = List.of(
                    advanceHead(roll.sessionId(), session.headSeq(), resultSeq, resultEntry.hash(), session.nextNonce()),
                    putEntry(roll.sessionId(), roundEntry),
                    putEntry(roll.sessionId(), resultEntry),
                    markResolved,
                    dropPending);
            Outcome outcome = transact(items, "resolução do roll " + roll.rollId(), attempt);
            if (outcome == Outcome.DONE || outcome == Outcome.ROLL_ALREADY_RESOLVED) {
                return new Resolution(findRoll(roll.rollId()).orElseThrow(), outcome == Outcome.DONE);
            }
        }
        throw new ChainContentionException("mesa " + roll.sessionId() + " ocupada; tente de novo");
    }

    /** Estado do roll depois da resolução; {@code won} diz se foi esta chamada que gravou. */
    public record Resolution(RollRecord roll, boolean won) {
    }

    /** Marca que a imagem OG do roll foi publicada. */
    public void markOgImage(String rollId) {
        dynamo.updateItem(r -> r.tableName(table)
                .key(key(rollPk(rollId), META))
                .updateExpression("SET ogImage = :t")
                .conditionExpression("attribute_exists(PK)")
                .expressionAttributeValues(Map.of(":t", AttributeValue.fromBool(true))));
    }

    /** Roll recém-pedido e a entrada REQUEST gravada (para o recibo). */
    public record Requested(RollRecord roll, ChainEntry entry) {
    }

    // ---------- Leitura da cadeia ----------

    /** Entradas com seq em [fromSeq, toSeq], em ordem. */
    public List<ChainEntry> entries(String sessionId, long fromSeq, long toSeq, int limit) {
        List<ChainEntry> out = new ArrayList<>();
        if (fromSeq > toSeq) {
            return out; // o DynamoDB rejeita BETWEEN com limite inferior maior que o superior
        }
        Map<String, AttributeValue> startKey = null;
        do {
            Map<String, AttributeValue> exclusiveStart = startKey;
            QueryResponse page = dynamo.query(QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                    .expressionAttributeValues(Map.of(
                            ":pk", s(sessionPk(sessionId)),
                            ":from", s(entrySk(fromSeq)),
                            ":to", s(entrySk(toSeq))))
                    .consistentRead(true)
                    .limit(limit - out.size())
                    .exclusiveStartKey(exclusiveStart)
                    .build());
            page.items().forEach(item -> out.add(toEntry(item)));
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null && out.size() < limit);
        return out;
    }

    /** IDs de rolls pendentes da mesa (marcadores PENDING#). */
    public List<String> pendingRollIds(String sessionId, int limit) {
        return dynamo.query(QueryRequest.builder()
                        .tableName(table)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                        .expressionAttributeValues(Map.of(":pk", s(sessionPk(sessionId)), ":prefix", s("PENDING#")))
                        .consistentRead(true)
                        .limit(limit)
                        .build())
                .items().stream().map(item -> str(item, "rollId")).toList();
    }

    // ---------- Internos ----------

    private enum Outcome { DONE, RETRY, ROLL_ALREADY_RESOLVED, REQUEST_REPLAYED }

    private Outcome transact(List<TransactWriteItem> items, String what, int attempt) {
        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
            return Outcome.DONE;
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.hasCancellationReasons() ? e.cancellationReasons() : List.of();
            // Índice 3 da resolução = condição status = PENDING do roll.
            if (items.size() == 5 && reasons.size() == 5 && "ConditionalCheckFailed".equals(reasons.get(3).code())
                    && items.get(3).update() != null) {
                return Outcome.ROLL_ALREADY_RESOLVED;
            }
            // Índice 4 do pedido = marcador REQ#{requestId}: o pedido assinado já foi gravado.
            if (items.size() == 5 && reasons.size() == 5 && "ConditionalCheckFailed".equals(reasons.get(4).code())
                    && items.get(4).put() != null) {
                return Outcome.REQUEST_REPLAYED;
            }
            LOG.debugf("%s: tentativa %d cancelada (%s)", what, attempt, reasons.stream().map(CancellationReason::code).toList());
            backoff(attempt);
            return Outcome.RETRY;
        } catch (ConditionalCheckFailedException e) {
            backoff(attempt);
            return Outcome.RETRY;
        }
    }

    private static void backoff(int attempt) {
        long cap = Math.min(200, 5L << Math.min(attempt, 6));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, cap + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChainContentionException("interrompido");
        }
    }

    private TransactWriteItem advanceHead(String sessionId, long expectedHeadSeq, long newHeadSeq, String newHeadHash,
                                          long nextNonce) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(table)
                .key(key(sessionPk(sessionId), META))
                .updateExpression("SET headSeq = :new, headHash = :hash, nextNonce = :nonce")
                .conditionExpression("headSeq = :expected")
                .expressionAttributeValues(Map.of(
                        ":new", n(newHeadSeq),
                        ":hash", s(newHeadHash),
                        ":nonce", n(nextNonce),
                        ":expected", n(expectedHeadSeq)))
                .build()).build();
    }

    private TransactWriteItem putEntry(String sessionId, ChainEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>(key(sessionPk(sessionId), entrySk(entry.seq())));
        item.put("seq", n(entry.seq()));
        item.put("type", s(entry.type()));
        item.put("prevHash", s(entry.prevHash()));
        item.put("hash", s(entry.hash()));
        item.put("payload", s(CanonicalJson.write(entry.payload())));
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)")
                .build()).build();
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)")
                .build()).build();
    }

    private ChainEntry toEntry(Map<String, AttributeValue> item) {
        try {
            Map<String, Object> payload = json.readValue(str(item, "payload"), MAP);
            return new ChainEntry(num(item, "seq"), str(item, "type"), str(item, "prevHash"), payload, str(item, "hash"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload ilegível na entrada " + str(item, "SK"), e);
        }
    }

    static String sessionPk(String sessionId) {
        return "SESSION#" + sessionId;
    }

    static String rollPk(String rollId) {
        return "ROLL#" + rollId;
    }

    static String requestSk(Player player) {
        return "REQ#" + player.requestId();
    }

    private static Player toPlayer(Map<String, AttributeValue> m) {
        List<Player.Link> links = m.get("links").l().stream()
                .map(l -> new Player.Link(l.m().get("deviceKey").s(), l.m().get("signature").s()))
                .toList();
        return new Player(m.get("publicKey").s(), m.get("name").s(), m.get("requestId").s(), m.get("signature").s(),
                m.get("identityKey").s(), links);
    }

    static String entrySk(long seq) {
        return String.format("ENTRY#%012d", seq);
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("PK", s(pk), "SK", s(sk));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    private static String str(Map<String, AttributeValue> item, String k) {
        return item.get(k).s();
    }

    private static long num(Map<String, AttributeValue> item, String k) {
        return Long.parseLong(item.get(k).n());
    }
}
