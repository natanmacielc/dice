package io.github.natanmacielc.dice.core.chain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entrada da cadeia chain-v1. {@code payload} é normalizado (Long, String, List, Map).
 * hash = SHA-256(utf8(prevHash) || utf8(type) || utf8(canonical(payload))).
 */
public record ChainEntry(long seq, String type, String prevHash, Map<String, Object> payload, String hash) {

    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    @SuppressWarnings("unchecked")
    public ChainEntry {
        payload = (Map<String, Object>) CanonicalJson.normalize(payload);
    }

    public static ChainEntry create(long seq, EntryType type, String prevHash, Map<String, Object> payload) {
        return new ChainEntry(seq, type.name(), prevHash, payload, computeHash(prevHash, type.name(), payload));
    }

    public static String computeHash(String prevHash, String type, Map<String, Object> payload) {
        String preimage = prevHash + type + CanonicalJson.write(payload);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(preimage.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Árvore JSON genérica: {seq, type, prevHash, hash, payload}. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("type", type);
        m.put("prevHash", prevHash);
        m.put("hash", hash);
        m.put("payload", payload);
        return m;
    }

    /** Lê uma entrada de uma árvore JSON genérica. Lança ChainFormatException(CHAIN_STRUCTURE). */
    public static ChainEntry fromJson(Object json) {
        if (!(json instanceof Map<?, ?> m)) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE, "entrada não é objeto");
        }
        Object seq = m.get("seq");
        Object type = m.get("type");
        Object prevHash = m.get("prevHash");
        Object hash = m.get("hash");
        Object payload = m.get("payload");
        if (!(seq instanceof Integer || seq instanceof Long) || !(type instanceof String t)
                || !(prevHash instanceof String p) || !(hash instanceof String h) || !(payload instanceof Map<?, ?>)) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE, "campos da entrada ausentes ou com tipo errado");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) CanonicalJson.normalize(payload);
            return new ChainEntry(((Number) seq).longValue(), t, p, normalized, h);
        } catch (IllegalArgumentException e) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE, "payload não canonizável: " + e.getMessage());
        }
    }
}
