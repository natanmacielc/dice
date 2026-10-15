package io.github.natanmacielc.dice.core.chain;

import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Quicknet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Payloads das entradas REQUEST, ROUND e RESULT (spec/ALGORITHM.md, seções 6.2 e 7.2). */
public final class Payloads {

    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern INSTANT_FORMAT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    private Payloads() {
    }

    public static Map<String, Object> request(long seq, String sessionId, String rollId, String clientSeed,
                                              String expression, long nonce, long targetRound, Instant requestedAt) {
        return request(seq, sessionId, rollId, clientSeed, expression, nonce, targetRound, requestedAt, null);
    }

    /** REQUEST da chain-v2 quando {@code player} não é null (seção 7.2); sem player, o da chain-v1. */
    public static Map<String, Object> request(long seq, String sessionId, String rollId, String clientSeed,
                                              String expression, long nonce, long targetRound, Instant requestedAt,
                                              Player player) {
        Map<String, Object> m = base(seq, sessionId, rollId);
        m.put("algorithm", DiceRoller.ALGORITHM);
        m.put("drandChainHash", Quicknet.CHAIN_HASH);
        m.put("clientSeed", clientSeed);
        m.put("expression", expression);
        m.put("nonce", nonce);
        m.put("targetRound", targetRound);
        m.put("requestedAt", formatInstant(requestedAt));
        if (player != null) {
            m.put("player", player.toPayload());
        }
        return m;
    }

    public static Map<String, Object> round(long seq, String sessionId, String rollId, long round,
                                            String randomness, String signature) {
        Map<String, Object> m = base(seq, sessionId, rollId);
        m.put("round", round);
        m.put("randomness", randomness);
        m.put("signature", signature);
        return m;
    }

    public static Map<String, Object> result(long seq, String sessionId, String rollId, List<Integer> dice,
                                             int modifier, int total) {
        Map<String, Object> m = base(seq, sessionId, rollId);
        m.put("dice", dice.stream().map(Integer::longValue).toList());
        m.put("modifier", (long) modifier);
        m.put("total", (long) total);
        return m;
    }

    /** ISO-8601 UTC, sempre com milissegundos: 2026-10-14T22:50:01.123Z. */
    public static String formatInstant(Instant t) {
        return INSTANT.format(t.truncatedTo(ChronoUnit.MILLIS));
    }

    public static Instant parseInstant(String s) {
        if (!INSTANT_FORMAT.matcher(s).matches()) {
            throw new IllegalArgumentException("requestedAt fora do formato: " + s);
        }
        return Instant.parse(s);
    }

    private static Map<String, Object> base(long seq, String sessionId, String rollId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("sessionId", sessionId);
        m.put("rollId", rollId);
        return m;
    }
}
