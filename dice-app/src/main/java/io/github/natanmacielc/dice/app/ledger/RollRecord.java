package io.github.natanmacielc.dice.app.ledger;

import java.util.List;

/** Estado de um roll. Os campos de resolução ficam nulos enquanto ele está pendente. */
public record RollRecord(String rollId, String sessionId, long nonce, String clientSeed, String expression,
                         long targetRound, String requestedAt, long requestSeq, String status,
                         Long resultSeq, Long round, String randomness, String signature,
                         List<Integer> dice, Integer modifier, Integer total) {

    public static final String PENDING = "PENDING";
    public static final String RESOLVED = "RESOLVED";

    public boolean resolved() {
        return RESOLVED.equals(status);
    }
}
