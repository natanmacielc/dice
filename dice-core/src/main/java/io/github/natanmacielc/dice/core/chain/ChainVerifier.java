package io.github.natanmacielc.dice.core.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verificação de um segmento do log (spec/ALGORITHM.md, seção 6.4). Cada entrada passa por todas
 * as checagens, na ordem da spec, antes da próxima; o primeiro erro vence.
 */
public final class ChainVerifier {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> TYPES = Set.of("REQUEST", "ROUND", "RESULT");

    private ChainVerifier() {
    }

    public static ChainVerification verifySegment(List<ChainEntry> entries, String prevHashOfFirst) {
        return verifySegmentJson(entries.stream().map(e -> (Object) e.toJson()).toList(), prevHashOfFirst);
    }

    /** Mesma verificação, a partir da árvore JSON genérica (List de Map). */
    public static ChainVerification verifySegmentJson(List<?> rawEntries, String prevHashOfFirst) {
        try {
            parseAndCheck(rawEntries, prevHashOfFirst);
            return ChainVerification.ok();
        } catch (ChainFormatException e) {
            return ChainVerification.fail(e.code(), e.getMessage());
        }
    }

    static List<ChainEntry> parseAndCheck(List<?> rawEntries, String prevHashOfFirst) {
        List<ChainEntry> out = new ArrayList<>(rawEntries.size());
        ChainEntry previous = null;
        for (Object raw : rawEntries) {
            ChainEntry e = ChainEntry.fromJson(raw);
            checkOne(e, previous, prevHashOfFirst);
            out.add(e);
            previous = e;
        }
        return out;
    }

    private static void checkOne(ChainEntry e, ChainEntry previous, String prevHashOfFirst) {
        // 1. Estrutura.
        if (e.seq() < 1 || !TYPES.contains(e.type()) || !HEX64.matcher(e.prevHash()).matches()
                || !HEX64.matcher(e.hash()).matches()) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE, "entrada seq " + e.seq() + " mal formada");
        }
        // 2. Contiguidade.
        if (previous != null && e.seq() != previous.seq() + 1) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_SEQ_GAP, "seq " + e.seq() + " depois de " + previous.seq());
        }
        // 3. Seq no payload.
        Object payloadSeq = e.payload().get("seq");
        if (!(payloadSeq instanceof Long ps) || ps != e.seq()) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_SEQ_MISMATCH,
                    "payload.seq " + payloadSeq + " != seq " + e.seq());
        }
        // 4. Encadeamento.
        String expectedPrev = previous == null ? prevHashOfFirst : previous.hash();
        if (!e.prevHash().equals(expectedPrev)) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_LINK_BROKEN, "prevHash da seq " + e.seq() + " não encadeia");
        }
        // 5. Hash.
        if (!ChainEntry.computeHash(e.prevHash(), e.type(), e.payload()).equals(e.hash())) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_HASH_MISMATCH, "hash da seq " + e.seq() + " não confere");
        }
    }
}
