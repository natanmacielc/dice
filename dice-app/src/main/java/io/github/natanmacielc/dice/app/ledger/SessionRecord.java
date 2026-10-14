package io.github.natanmacielc.dice.app.ledger;

public record SessionRecord(String sessionId, String name, String createdAt, long headSeq, String headHash,
                            long nextNonce) {
}
