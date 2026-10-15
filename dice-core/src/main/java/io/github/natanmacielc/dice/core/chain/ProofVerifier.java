package io.github.natanmacielc.dice.core.chain;

import io.github.natanmacielc.dice.core.BeaconCheck;
import io.github.natanmacielc.dice.core.DiceException;
import io.github.natanmacielc.dice.core.DiceExpression;
import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.RollResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Verificação da prova de um roll (spec/ALGORITHM.md, seções 6.5 e 7.4). Recebe a árvore JSON genérica
 * (Map, List, String, Integer/Long), para o núcleo não depender de um parser JSON.
 */
public final class ProofVerifier {

    private static final List<String> ROLL_SUMMARY_FROM_REQUEST =
            List.of("rollId", "sessionId", "nonce", "clientSeed", "expression", "targetRound", "requestedAt");
    private static final List<String> ROLL_SUMMARY_FROM_RESULT = List.of("dice", "modifier", "total");
    private static final List<String> BEACON_SUMMARY = List.of("round", "randomness", "signature");

    private ProofVerifier() {
    }

    public static ChainVerification verify(Map<String, Object> proof) {
        try {
            check(proof);
            return ChainVerification.ok();
        } catch (ChainFormatException e) {
            return ChainVerification.fail(e.code(), e.getMessage());
        }
    }

    private static void check(Map<String, Object> proof) {
        // 1. Cabeçalho.
        Object drand = proof.get("drand");
        ChainFormat format = ChainFormat.fromId(proof.get("chainFormat"));
        if (!DiceRoller.ALGORITHM.equals(proof.get("algorithm")) || format == null
                || !(drand instanceof Map<?, ?> d) || !Quicknet.NETWORK.equals(d.get("network"))
                || !Quicknet.CHAIN_HASH.equals(d.get("chainHash"))
                || !(proof.get("entries") instanceof List<?> rawEntries) || rawEntries.isEmpty()) {
            throw structure("cabeçalho da prova inválido");
        }

        // 2. Segmento, usando o prevHash da própria primeira entrada.
        Object firstRaw = rawEntries.get(0);
        Object firstPrev = firstRaw instanceof Map<?, ?> fm ? fm.get("prevHash") : null;
        List<ChainEntry> entries = ChainVerifier.parseAndCheck(rawEntries,
                firstPrev instanceof String s ? s : ChainEntry.GENESIS_PREV_HASH, format);

        // 3. Forma da prova.
        ChainEntry request = entries.get(0);
        ChainEntry result = entries.get(entries.size() - 1);
        if (!"REQUEST".equals(request.type()) || !"RESULT".equals(result.type())) {
            throw structure("a prova deve começar no REQUEST e terminar no RESULT");
        }
        String rollId = str(request.payload(), "rollId");
        String sessionId = str(request.payload(), "sessionId");
        ChainEntry round = null;
        for (int i = 0; i < entries.size(); i++) {
            ChainEntry e = entries.get(i);
            if (!sessionId.equals(str(e.payload(), "sessionId"))) {
                throw structure("entrada seq " + e.seq() + " de outra mesa");
            }
            if (!rollId.equals(str(e.payload(), "rollId"))) {
                continue;
            }
            boolean first = i == 0;
            boolean last = i == entries.size() - 1;
            switch (e.type()) {
                case "REQUEST" -> {
                    if (!first) {
                        throw structure("REQUEST repetido do roll");
                    }
                }
                case "RESULT" -> {
                    if (!last) {
                        throw structure("RESULT do roll fora do fim da prova");
                    }
                }
                default -> {
                    if (round != null) {
                        throw structure("mais de um ROUND do roll");
                    }
                    round = e;
                }
            }
        }
        if (round == null) {
            throw structure("prova sem ROUND do roll");
        }
        if (!rollId.equals(str(result.payload(), "rollId"))) {
            throw structure("RESULT final é de outro roll");
        }
        Map<String, Object> req = request.payload();
        if (!DiceRoller.ALGORITHM.equals(str(req, "algorithm")) || !Quicknet.CHAIN_HASH.equals(str(req, "drandChainHash"))) {
            throw structure("algorithm ou drandChainHash inesperado no REQUEST");
        }
        String clientSeed = str(req, "clientSeed");
        String expression = str(req, "expression");
        long nonce = num(req, "nonce");
        long targetRound = num(req, "targetRound");
        String requestedAtText = str(req, "requestedAt");
        Instant requestedAt;
        String canonical;
        try {
            requestedAt = Payloads.parseInstant(requestedAtText);
            canonical = DiceExpression.parse(expression).canonical(); // DiceException também é IllegalArgumentException
        } catch (IllegalArgumentException e) {
            throw structure(e.getMessage());
        }
        if (!canonical.equals(expression)) {
            throw structure("expressão do REQUEST não está na forma canônica");
        }
        Map<String, Object> rnd = round.payload();
        long roundNumber = num(rnd, "round");
        String randomness = str(rnd, "randomness");
        String signature = str(rnd, "signature");
        Map<String, Object> res = result.payload();
        List<Long> dice = longs(res, "dice");
        long modifier = num(res, "modifier");
        long total = num(res, "total");

        // 4. Round alvo (invariante 1).
        if (roundNumber != targetRound) {
            throw new ChainFormatException(ChainErrorCode.PROOF_WRONG_ROUND,
                    "ROUND " + roundNumber + " != targetRound " + targetRound);
        }
        // 5. Beacon (invariante 6).
        try {
            if (!BeaconCheck.randomnessMatchesSignature(signature, randomness)) {
                throw new ChainFormatException(ChainErrorCode.PROOF_BEACON_INVALID, "randomness != SHA-256(signature)");
            }
        } catch (DiceException e) {
            throw new ChainFormatException(ChainErrorCode.PROOF_BEACON_INVALID, e.getMessage());
        }
        // 6. O round alvo ainda não existia no pedido.
        if (targetRound < 1 || !requestedAt.isBefore(Quicknet.timeOf(targetRound))) {
            throw new ChainFormatException(ChainErrorCode.PROOF_TARGET_NOT_FUTURE,
                    "requestedAt " + requestedAt + " não é anterior ao round " + targetRound);
        }
        // 7. Resultado recalculado.
        RollResult actual;
        try {
            actual = DiceRoller.roll(randomness, clientSeed, nonce, expression);
        } catch (DiceException e) {
            throw structure("entrada do REQUEST inválida: " + e.getMessage());
        }
        List<Long> actualDice = actual.dice().stream().map(Integer::longValue).toList();
        if (!actualDice.equals(dice) || actual.modifier() != modifier || actual.total() != total) {
            throw new ChainFormatException(ChainErrorCode.PROOF_RESULT_MISMATCH,
                    "RESULT " + dice + " total " + total + " != recalculado " + actualDice + " total " + actual.total());
        }
        // 8. Resumos.
        Object beacon = proof.get("beacon");
        Object roll = proof.get("roll");
        if (!(beacon instanceof Map<?, ?> b) || !(roll instanceof Map<?, ?> r)) {
            throw summary("resumos beacon/roll ausentes");
        }
        compare(b, rnd, BEACON_SUMMARY);
        compare(r, req, ROLL_SUMMARY_FROM_REQUEST);
        compare(r, res, ROLL_SUMMARY_FROM_RESULT);
        if (format == ChainFormat.CHAIN_V2) {
            compare(r, req, List.of("player"));
        }
    }

    private static void compare(Map<?, ?> summary, Map<String, Object> source, List<String> fields) {
        for (String f : fields) {
            Object value;
            try {
                value = CanonicalJson.normalize(summary.get(f));
            } catch (IllegalArgumentException e) {
                throw summary("campo " + f + " do resumo inválido");
            }
            if (!Objects.equals(value, source.get(f))) {
                throw summary("campo " + f + " do resumo não bate com a cadeia");
            }
        }
    }

    private static String str(Map<String, Object> m, String key) {
        if (m.get(key) instanceof String s) {
            return s;
        }
        throw structure("campo " + key + " ausente ou não-string");
    }

    private static long num(Map<String, Object> m, String key) {
        if (m.get(key) instanceof Long n) {
            return n;
        }
        throw structure("campo " + key + " ausente ou não-inteiro");
    }

    private static List<Long> longs(Map<String, Object> m, String key) {
        if (m.get(key) instanceof List<?> l && l.stream().allMatch(x -> x instanceof Long)) {
            return l.stream().map(x -> (Long) x).toList();
        }
        throw structure("campo " + key + " ausente ou não é lista de inteiros");
    }

    private static ChainFormatException structure(String message) {
        return new ChainFormatException(ChainErrorCode.PROOF_STRUCTURE, message);
    }

    private static ChainFormatException summary(String message) {
        return new ChainFormatException(ChainErrorCode.PROOF_SUMMARY_MISMATCH, message);
    }
}
