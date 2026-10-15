package io.github.natanmacielc.dice.core.chain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verificação de um segmento do log (spec/ALGORITHM.md, seções 6.4 e 7.3). Cada entrada passa por
 * todas as checagens, na ordem da spec, antes da próxima; o primeiro erro vence.
 */
public final class ChainVerifier {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> TYPES = Set.of("REQUEST", "ROUND", "RESULT");

    private ChainVerifier() {
    }

    /** Segmento da chain-v1. */
    public static ChainVerification verifySegment(List<ChainEntry> entries, String prevHashOfFirst) {
        return verifySegment(entries, prevHashOfFirst, ChainFormat.CHAIN_V1);
    }

    public static ChainVerification verifySegment(List<ChainEntry> entries, String prevHashOfFirst, ChainFormat format) {
        return verifySegmentJson(entries.stream().map(e -> (Object) e.toJson()).toList(), prevHashOfFirst, format);
    }

    /** Segmento da chain-v1, a partir da árvore JSON genérica (List de Map). */
    public static ChainVerification verifySegmentJson(List<?> rawEntries, String prevHashOfFirst) {
        return verifySegmentJson(rawEntries, prevHashOfFirst, ChainFormat.CHAIN_V1);
    }

    public static ChainVerification verifySegmentJson(List<?> rawEntries, String prevHashOfFirst, ChainFormat format) {
        try {
            parseAndCheck(rawEntries, prevHashOfFirst, format);
            return ChainVerification.ok();
        } catch (ChainFormatException e) {
            return ChainVerification.fail(e.code(), e.getMessage());
        }
    }

    static List<ChainEntry> parseAndCheck(List<?> rawEntries, String prevHashOfFirst, ChainFormat format) {
        List<ChainEntry> out = new ArrayList<>(rawEntries.size());
        Set<String> requestIds = new HashSet<>();
        ChainEntry previous = null;
        for (Object raw : rawEntries) {
            ChainEntry e = ChainEntry.fromJson(raw);
            checkOne(e, previous, prevHashOfFirst);
            if (format == ChainFormat.CHAIN_V2 && "REQUEST".equals(e.type())) {
                checkPlayer(e, requestIds);
            }
            out.add(e);
            previous = e;
        }
        return out;
    }

    /**
     * chain-v2: 6. player bem formado; 7. vínculos da identidade até o aparelho; 8. assinatura do pedido;
     * 9. requestId inédito no segmento.
     */
    private static void checkPlayer(ChainEntry e, Set<String> requestIds) {
        Player player = Player.fromPayload(e.payload().get("player"));
        if (!(e.payload().get("sessionId") instanceof String sessionId)
                || !(e.payload().get("clientSeed") instanceof String clientSeed)
                || !(e.payload().get("expression") instanceof String expression)) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE,
                    "REQUEST seq " + e.seq() + " sem sessionId, clientSeed ou expression");
        }
        if (!player.linksValid()) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_PLAYER_SIGNATURE,
                    "vínculos do aparelho à identidade inválidos no REQUEST seq " + e.seq());
        }
        if (!player.signatureValid(sessionId, clientSeed, expression)) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_PLAYER_SIGNATURE,
                    "assinatura do jogador inválida no REQUEST seq " + e.seq());
        }
        if (!requestIds.add(player.requestId())) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_REQUEST_REPLAY,
                    "requestId repetido no REQUEST seq " + e.seq());
        }
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
