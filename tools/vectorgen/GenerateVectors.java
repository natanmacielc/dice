import io.github.natanmacielc.dice.core.BeaconCheck;
import io.github.natanmacielc.dice.core.DiceException;
import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Rejection;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.RollResult;
import io.github.natanmacielc.dice.core.chain.CanonicalJson;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.ChainVerification;
import io.github.natanmacielc.dice.core.chain.ChainVerifier;
import io.github.natanmacielc.dice.core.chain.EntryType;
import io.github.natanmacielc.dice.core.chain.Payloads;
import io.github.natanmacielc.dice.core.chain.Player;
import io.github.natanmacielc.dice.core.chain.ProofVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera spec/test-vectors.json a partir de entradas curadas, com as saídas calculadas pelo dice-core.
 * O arquivo sai só em ASCII (não-ASCII vira \\uXXXX), para não depender da codificação de quem lê.
 *
 * Uso: ./mvnw -q -pl dice-core compile
 *      java -cp dice-core/target/classes tools/vectorgen/GenerateVectors.java spec/test-vectors.json
 */
public class GenerateVectors {

    // Rounds reais do drand quicknet (chain 52db9ba7...e971).
    static final String R1 = "1466a6cd24e327188770752f6134001c64d6efcc590ccc26b721611ad96f165a";
    static final String S1 = "b55e7cb2d5c613ee0b2e28d6750aabbb78c39dcc96bd9d38c2c2e12198df95571de8e8e402a0cc48871c7089a2b3af4b";
    static final String R1M = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3";
    static final String S1M = "83ad29e4c409f9470fc2ef02f90214df49e02b441a1a241a82d622d9f608ef98fd8b11a029f1bee9d9e83b45088abe72";
    static final String R12M = "47175ae9652cb6704c2b509e5d2aa03b609e67039251ff1e162282136c515bf0";
    static final String S12M = "b0a199d51b7c29117d2155c4fc2322dbacd9d1e1f11da5b5c0e4e19947e4aaf634970e85eceef450681d27baa3a03f20";
    // Sintéticos.
    static final String ZEROS = "0".repeat(64);
    static final String FS = "f".repeat(64);

    static final long NONCE_MAX = 9007199254740991L;

    // Encontradas por FindRejections.java (randomness R1M, nonce 1, 20dX).
    static final String REJ_D100 = "rej-d100-1887002";
    static final String REJ_D20 = "rej-d20-4550617";
    static final String REJ_D10 = "rej-d10-37378448";
    static final String REJ_D12 = "rej-d12-44219600";
    static final String REJ_D6 = "rej-d6-84230335";

    static final List<Map<String, Object>> beacon = new ArrayList<>();
    static final List<Map<String, Object>> rolls = new ArrayList<>();
    static final List<Map<String, Object>> errors = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        beacon("quicknet-round-1", 1, S1, R1);
        beacon("quicknet-round-1000000", 1_000_000, S1M, R1M);
        beacon("quicknet-round-12345678", 12_345_678, S12M, R12M);
        beacon("tampered-signature", 1_000_000, "93" + S1M.substring(2), R1M);
        beacon("randomness-from-other-round", 1, S1, R1M);

        roll("d4-basic", "um d4", R1M, "mesa-do-dragao", 1, "1d4");
        roll("d6-basic", "um d6", R1M, "mesa-do-dragao", 2, "1d6");
        roll("d8-basic", "um d8", R1M, "mesa-do-dragao", 3, "1d8");
        roll("d10-basic", "um d10", R1M, "mesa-do-dragao", 4, "1d10");
        roll("d12-basic", "um d12", R1M, "mesa-do-dragao", 5, "1d12");
        roll("d20-basic", "um d20", R1M, "mesa-do-dragao", 6, "1d20");
        roll("d100-basic", "um d100", R1M, "mesa-do-dragao", 7, "1d100");
        roll("block-boundary-20d6", "20 dados cruzam os blocos c=0, 1 e 2", R1, "fronteira", 0, "20d6");
        roll("block-boundary-20d100", "20 dados cruzam os blocos c=0, 1 e 2", R12M, "fronteira", 0, "20d100");
        roll("max-dice-max-modifier", "20d20+100", R1M, "limites", 8, "20d20+100");
        roll("modifier-plus-100", "M = +100", R1M, "mod", 10, "3d8+100");
        roll("modifier-minus-100", "M = -100, total negativo", R1M, "mod", 11, "1d4-100");
        roll("modifier-minus-zero", "-0 vira forma sem modificador", R1M, "mod", 12, "2d6-0");
        roll("modifier-plus-zero", "+0 vira forma sem modificador", R1M, "mod", 12, "2d6+0");
        roll("seed-1-byte", "seed de 1 byte", R1M, "x", 20, "2d20");
        roll("seed-64-bytes-ascii", "seed de 64 bytes ASCII (limite)", R1M, "0123456789abcdef".repeat(4), 21, "2d20");
        roll("seed-64-bytes-emoji", "16 emojis de 4 bytes = 64 bytes", R1M, "😀".repeat(16), 22, "2d20");
        roll("seed-accents", "acentos em UTF-8", R1M, "Ação crítica! ñ ü", 23, "2d20");
        roll("seed-nfc", "é pré-composto (U+00E9)", R1M, "café", 24, "1d100");
        roll("seed-nfd", "e + acento combinante (U+0065 U+0301): sem normalização, resultado difere de seed-nfc",
                R1M, "café", 24, "1d100");
        roll("seed-with-pipe", "'|' na seed não gera ambiguidade", R1M, "a|b|1", 25, "2d20");
        roll("seed-with-spaces", "espaços nas pontas são preservados", R1M, " espaço ", 26, "2d20");
        roll("nonce-zero", "nonce 0", R1M, "nonce", 0, "3d6");
        roll("nonce-max", "nonce 2^53 - 1", R1M, "nonce", NONCE_MAX, "3d6");
        roll("real-round-1", "randomness do round 1", R1, "mesa", 1, "4d6");
        roll("real-round-12345678", "randomness do round 12345678", R12M, "mesa", 1, "4d6");
        roll("synthetic-zeros", "randomness só com zeros", ZEROS, "sintético", 1, "5d12");
        roll("synthetic-ff", "randomness só com f", FS, "sintético", 1, "5d12");
        roll("lenient-no-count", "'d20' = 1d20", R1M, "leniente", 30, "d20");
        roll("lenient-uppercase", "'D' maiúsculo", R1M, "leniente", 30, "1D20");
        roll("lenient-spaces", "espaços nas pontas e em volta do sinal", R1M, "leniente", 31, " 2d6 + 3 ");
        roll("lenient-tabs", "tabs", R1M, "leniente", 32, "\t4d10\t-\t2");
        roll("lenient-minus", "modificador negativo", R1M, "leniente", 33, "2d6-1");
        roll("rejection-d100", "rejeição real em d100 (limite 2^32 - 96)", R1M, REJ_D100, 1, "20d100");
        roll("rejection-d20", "rejeição real em d20 (limite 2^32 - 16)", R1M, REJ_D20, 1, "20d20");
        roll("rejection-d10", "rejeição real em d10 (limite 2^32 - 6)", R1M, REJ_D10, 1, "20d10");
        roll("rejection-d12", "rejeição real em d12 (limite 2^32 - 4)", R1M, REJ_D12, 1, "20d12");
        roll("rejection-d6", "rejeição real em d6 (limite 2^32 - 4)", R1M, REJ_D6, 1, "20d6");

        error("randomness-uppercase", R1M.toUpperCase(), "s", 1, "1d20", "RANDOMNESS_FORMAT");
        error("randomness-63-chars", R1M.substring(1), "s", 1, "1d20", "RANDOMNESS_FORMAT");
        error("randomness-65-chars", R1M + "0", "s", 1, "1d20", "RANDOMNESS_FORMAT");
        error("randomness-non-hex", "g" + R1M.substring(1), "s", 1, "1d20", "RANDOMNESS_FORMAT");
        error("randomness-empty", "", "s", 1, "1d20", "RANDOMNESS_FORMAT");
        error("seed-empty", R1M, "", 1, "1d20", "SEED_EMPTY");
        error("seed-65-bytes", R1M, "x".repeat(65), 1, "1d20", "SEED_TOO_LONG");
        error("seed-66-bytes-multibyte", R1M, "é".repeat(33), 1, "1d20", "SEED_TOO_LONG");
        error("seed-newline", R1M, "a\nb", 1, "1d20", "SEED_CONTROL_CHAR");
        error("seed-nul", R1M, "\u0000", 1, "1d20", "SEED_CONTROL_CHAR");
        error("seed-tab", R1M, "a\tb", 1, "1d20", "SEED_CONTROL_CHAR");
        error("seed-del", R1M, "a\u007f", 1, "1d20", "SEED_CONTROL_CHAR");
        error("seed-lone-high-surrogate", R1M, "a\uD800", 1, "1d20", "SEED_INVALID_UTF8");
        error("seed-lone-low-surrogate", R1M, "\uDC00a", 1, "1d20", "SEED_INVALID_UTF8");
        error("nonce-negative", R1M, "s", -1, "1d20", "NONCE_OUT_OF_RANGE");
        error("nonce-2-pow-53", R1M, "s", NONCE_MAX + 1, "1d20", "NONCE_OUT_OF_RANGE");
        error("expr-empty", R1M, "s", 1, "", "INVALID_EXPRESSION");
        error("expr-garbage", R1M, "s", 1, "abc", "INVALID_EXPRESSION");
        error("expr-leading-zero", R1M, "s", 1, "01d6", "INVALID_EXPRESSION");
        error("expr-double-sign", R1M, "s", 1, "2d6--1", "INVALID_EXPRESSION");
        error("expr-unicode-digit", R1M, "s", 1, "１d6", "INVALID_EXPRESSION");
        error("expr-space-inside-term", R1M, "s", 1, "2 d6", "INVALID_EXPRESSION");
        error("expr-trailing-newline", R1M, "s", 1, "2d6\n", "INVALID_EXPRESSION");
        error("expr-three-digit-count", R1M, "s", 1, "100d6", "INVALID_EXPRESSION");
        error("expr-zero-dice", R1M, "s", 1, "0d6", "DICE_COUNT_OUT_OF_RANGE");
        error("expr-21-dice", R1M, "s", 1, "21d6", "DICE_COUNT_OUT_OF_RANGE");
        error("expr-d7", R1M, "s", 1, "1d7", "UNSUPPORTED_DIE");
        error("expr-d0", R1M, "s", 1, "1d0", "UNSUPPORTED_DIE");
        error("expr-modifier-101", R1M, "s", 1, "2d6+101", "MODIFIER_OUT_OF_RANGE");
        error("expr-modifier-minus-101", R1M, "s", 1, "2d6-101", "MODIFIER_OUT_OF_RANGE");
        error("order-randomness-first", "bad", "", -1, "0d0", "RANDOMNESS_FORMAT");
        error("order-seed-before-nonce", R1M, "", -1, "0d0", "SEED_EMPTY");
        error("order-nonce-before-expression", R1M, "s", -1, "0d0", "NONCE_OUT_OF_RANGE");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("algorithm", DiceRoller.ALGORITHM);
        root.put("comment", "Gerado por tools/vectorgen/GenerateVectors.java. Ver spec/ALGORITHM.md.");
        Map<String, Object> drand = new LinkedHashMap<>(); // ordem fixa: o arquivo precisa sair byte a byte igual
        drand.put("chainHash", Quicknet.CHAIN_HASH);
        drand.put("network", Quicknet.NETWORK);
        root.put("drand", drand);
        root.put("beaconVectors", beacon);
        root.put("rollVectors", rolls);
        root.put("errorVectors", errors);
        ChainVectors.generate(root);
        ChainV2Vectors.generate(root);
        Files.writeString(Path.of(args[0]), toJson(root, "") + "\n", StandardCharsets.US_ASCII);
        System.out.printf("beacon=%d roll=%d error=%d%n", beacon.size(), rolls.size(), errors.size());
    }

    /** Vetores da chain-v1 (spec/ALGORITHM.md, seção 6). Cada saída esperada é conferida pelo dice-core. */
    static final class ChainVectors {

        static final String R1M1 = "9f45f439afd81e9846b3b4dc5e3e6051922c73c8459d18e9d507b52ddbd884ff";
        static final String S1M1 = "a5bd91e5e2d8c0bf51bffdfad87eef34348fd9c0b2df2bee39db90bdef7e1399b1a77bb2fe98b24d84c0936a306c4218";
        static final String R1M2 = "018e0e0c9e0d7906762eca633fab3ec5e97ee4cb8949e4eb9ee589160b49263d";
        static final String S1M2 = "a96e2a020098645aa4f912dcca317a67e98c39909fe1a037798fb503f04272b8153bab438c7e8d298593af1bdf29e5c5";

        static final String SESSION = "kq1t9m3x7c2a8z5r";
        static final String OTHER_SESSION = "w4n8p2d6f0h3j7m1";
        static final String ROLL_A = "a1b2c3d4e5f6g7h8";
        static final String ROLL_B = "b9c8d7e6f5g4h3j2";
        static final String ROLL_C = "c0d1e2f3g4h5j6k7";

        static final List<Map<String, Object>> canonical = new ArrayList<>();
        static final List<Map<String, Object>> logs = new ArrayList<>();
        static final List<Map<String, Object>> proofs = new ArrayList<>();

        static void generate(Map<String, Object> root) {
            canonicalVectors();
            Chain session = sessionChain();
            logVectors(session);
            proofVectors(session);
            root.put("chainFormat", "chain-v1");
            root.put("canonicalJsonVectors", canonical);
            root.put("logVectors", logs);
            root.put("proofVectors", proofs);
            System.out.printf("canonical=%d log=%d proof=%d%n", canonical.size(), logs.size(), proofs.size());
        }

        // ---------- JSON canônico ----------

        static void canonicalVectors() {
            Map<String, Object> keys = new LinkedHashMap<>();
            keys.put("b", 1L);
            keys.put("a", 2L);
            keys.put("A", 3L);
            keys.put("aa", 4L);
            keys.put("_", 5L);
            canon("key-order", "chaves por unidade UTF-16: A < _ < a < aa < b", keys);
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("b", 1L);
            inner.put("a", 0L);
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("y", List.of(3L, inner));
            z.put("x", "v");
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("z", z);
            nested.put("a", List.of());
            canon("nested", "objetos e arrays aninhados; array vazio", nested);
            Map<String, Object> empties = new LinkedHashMap<>();
            empties.put("o", Map.of());
            empties.put("l", List.of());
            canon("empty-containers", "objeto e array vazios", empties);
            canon("string-escapes", "s\u00f3 estes caracteres s\u00e3o escapados",
                    Map.of("s", "\"\\\b\f\n\r\t\u0001\u001f"));
            canon("string-literals", "/, DEL e n\u00e3o-ASCII v\u00e3o literais",
                    Map.of("s", "/ \u007f \u00e9 \u00e7 \uD83D\uDE00 \u65e5\u672c"));
            canon("integers", "inteiros no limite de 2^53 - 1",
                    Map.of("n", List.of(0L, -1L, 42L, 9007199254740991L, -9007199254740991L)));
            canon("strings-in-array", "strings em array mant\u00eam a ordem",
                    Map.of("l", List.of("b", "a", "")));
            canon("request-payload", "payload real de um REQUEST",
                    Payloads.request(1, SESSION, ROLL_A, "mesa-do-drag\u00e3o", "2d6+3", 1, 1_000_000,
                            Quicknet.timeOf(1_000_000).minusMillis(4500)));
        }

        static void canon(String id, String description, Object value) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", id);
            v.put("description", description);
            v.put("value", value);
            v.put("canonical", CanonicalJson.write(value));
            canonical.add(v);
        }

        // ---------- Cadeia de uma mesa ----------

        static final class Chain {
            final String sessionId;
            final List<ChainEntry> entries = new ArrayList<>();

            Chain(String sessionId) {
                this.sessionId = sessionId;
            }

            String head() {
                return entries.isEmpty() ? ChainEntry.GENESIS_PREV_HASH : entries.get(entries.size() - 1).hash();
            }

            long next() {
                return entries.size() + 1;
            }

            void add(EntryType type, Map<String, Object> payload) {
                entries.add(ChainEntry.create(next(), type, head(), payload));
            }

            void request(String rollId, String seed, String expr, long nonce, long target, Instant at) {
                request(rollId, seed, expr, nonce, target, at, null);
            }

            void request(String rollId, String seed, String expr, long nonce, long target, Instant at, Player player) {
                add(EntryType.REQUEST, Payloads.request(next(), sessionId, rollId, seed, expr, nonce, target, at, player));
            }

            void round(String rollId, long round, String randomness, String signature) {
                add(EntryType.ROUND, Payloads.round(next(), sessionId, rollId, round, randomness, signature));
            }

            void result(String rollId, RollResult r) {
                add(EntryType.RESULT, Payloads.result(next(), sessionId, rollId, r.dice(), r.modifier(), r.total()));
            }
        }

        static Instant before(long round, long millis) {
            return Quicknet.timeOf(round).minusMillis(millis);
        }

        /** Mesa com 3 rolls: A e B intercalados, C adjacente. */
        static Chain sessionChain() {
            Chain c = new Chain(SESSION);
            c.request(ROLL_A, "mesa-do-drag\u00e3o", "2d6+3", 1, 1_000_000, before(1_000_000, 4500));
            c.request(ROLL_B, "Thorin \uD83E\uDE93", "1d20+5", 2, 1_000_001, before(1_000_001, 5250));
            c.round(ROLL_A, 1_000_000, R1M, S1M);
            c.result(ROLL_A, DiceRoller.roll(R1M, "mesa-do-drag\u00e3o", 1, "2d6+3"));
            c.round(ROLL_B, 1_000_001, R1M1, S1M1);
            c.result(ROLL_B, DiceRoller.roll(R1M1, "Thorin \uD83E\uDE93", 2, "1d20+5"));
            c.request(ROLL_C, "c|\"aspas\"", "4d6-1", 3, 1_000_002, before(1_000_002, 3001));
            c.round(ROLL_C, 1_000_002, R1M2, S1M2);
            c.result(ROLL_C, DiceRoller.roll(R1M2, "c|\"aspas\"", 3, "4d6-1"));
            return c;
        }

        static List<Object> json(List<ChainEntry> entries) {
            List<Object> out = new ArrayList<>();
            entries.forEach(e -> out.add(deepCopy(e.toJson())));
            return out;
        }

        @SuppressWarnings("unchecked")
        static Object deepCopy(Object o) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put((String) k, deepCopy(v)));
                return out;
            }
            if (o instanceof List<?> l) {
                List<Object> out = new ArrayList<>();
                l.forEach(x -> out.add(deepCopy(x)));
                return out;
            }
            return o;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> entryAt(List<Object> entries, int i) {
            return (Map<String, Object>) entries.get(i);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> payloadAt(List<Object> entries, int i) {
            return (Map<String, Object>) entryAt(entries, i).get("payload");
        }

        /** Recalcula o hash da entrada i a partir do JSON (para adulterações "bem hasheadas"). */
        static void rehash(List<Object> entries, int i) {
            ChainEntry e = ChainEntry.fromJson(entryAt(entries, i));
            entryAt(entries, i).put("hash", ChainEntry.computeHash(e.prevHash(), e.type(), e.payload()));
        }

        /** Recalcula prevHash e hash da entrada i em diante, para manter o encadeamento. */
        static void relink(List<Object> entries, int from) {
            for (int i = from; i < entries.size(); i++) {
                if (i > 0) {
                    entryAt(entries, i).put("prevHash", entryAt(entries, i - 1).get("hash"));
                }
                rehash(entries, i);
            }
        }

        // ---------- Log ----------

        static void logVectors(Chain c) {
            List<Object> full = json(c.entries);
            log("log-valid-full", "mesa inteira desde o genesis", ChainEntry.GENESIS_PREV_HASH, full, null);
            log("log-valid-middle", "segmento do meio com o hash anterior conhecido", c.entries.get(2).hash(),
                    json(c.entries.subList(3, 9)), null);
            log("log-wrong-genesis", "primeira entrada n\u00e3o aponta para 64 zeros", "f".repeat(64), full,
                    "CHAIN_LINK_BROKEN");

            List<Object> gap = json(c.entries);
            gap.remove(4);
            log("log-seq-gap", "entrada 5 removida", ChainEntry.GENESIS_PREV_HASH, gap, "CHAIN_SEQ_GAP");

            List<Object> reordered = json(c.entries);
            Object second = reordered.remove(1);
            reordered.add(2, second);
            log("log-reordered", "entradas 2 e 3 trocadas", ChainEntry.GENESIS_PREV_HASH, reordered, "CHAIN_SEQ_GAP");

            List<Object> fork = json(c.entries.subList(0, 3));
            fork.add(2, deepCopy(entryAt(fork, 1)));
            log("log-fork-duplicate-seq", "duas entradas com seq 2", ChainEntry.GENESIS_PREV_HASH, fork, "CHAIN_SEQ_GAP");

            List<Object> tampered = json(c.entries);
            payloadAt(tampered, 2).put("randomness", "0".repeat(64));
            log("log-hash-tampered", "randomness do ROUND trocado sem refazer o hash", ChainEntry.GENESIS_PREV_HASH,
                    tampered, "CHAIN_HASH_MISMATCH");

            List<Object> rewritten = json(c.entries);
            payloadAt(rewritten, 3).put("total", 99L);
            rehash(rewritten, 3);
            log("log-rewritten-entry", "RESULT reescrito com hash refeito: a entrada seguinte n\u00e3o encadeia",
                    ChainEntry.GENESIS_PREV_HASH, rewritten, "CHAIN_LINK_BROKEN");

            List<Object> seqMismatch = json(c.entries);
            payloadAt(seqMismatch, 1).put("seq", 20L);
            relink(seqMismatch, 1);
            log("log-payload-seq-mismatch", "payload.seq diferente de seq (hashes refeitos)", ChainEntry.GENESIS_PREV_HASH,
                    seqMismatch, "CHAIN_SEQ_MISMATCH");

            List<Object> unknownType = json(c.entries.subList(0, 2));
            entryAt(unknownType, 1).put("type", "EXPIRE");
            log("log-unknown-type", "tipo de entrada fora do vocabul\u00e1rio", ChainEntry.GENESIS_PREV_HASH, unknownType,
                    "CHAIN_STRUCTURE");

            List<Object> bool = json(c.entries.subList(0, 1));
            payloadAt(bool, 0).put("flag", Boolean.TRUE);
            log("log-boolean-in-payload", "booleano n\u00e3o \u00e9 permitido no payload", ChainEntry.GENESIS_PREV_HASH,
                    bool, "CHAIN_STRUCTURE");

            List<Object> flt = json(c.entries.subList(0, 1));
            payloadAt(flt, 0).put("nonce", 1.5);
            log("log-float-in-payload", "float n\u00e3o \u00e9 permitido no payload", ChainEntry.GENESIS_PREV_HASH,
                    flt, "CHAIN_STRUCTURE");

            List<Object> big = json(c.entries.subList(0, 1));
            payloadAt(big, 0).put("nonce", 9007199254740992L);
            log("log-integer-too-large", "inteiro acima de 2^53 - 1", ChainEntry.GENESIS_PREV_HASH, big,
                    "CHAIN_STRUCTURE");

            List<Object> upperHash = json(c.entries.subList(0, 1));
            entryAt(upperHash, 0).put("hash", ((String) entryAt(upperHash, 0).get("hash")).toUpperCase());
            log("log-uppercase-hash", "hash em hex mai\u00fasculo", ChainEntry.GENESIS_PREV_HASH, upperHash,
                    "CHAIN_STRUCTURE");
        }

        static void log(String id, String description, String prevHashOfFirst, List<Object> entries, String error) {
            ChainVerification v = ChainVerifier.verifySegmentJson(entries, prevHashOfFirst);
            check(id, v, error);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("description", description);
            out.put("prevHashOfFirst", prevHashOfFirst);
            out.put("entries", entries);
            out.put("valid", error == null);
            if (error != null) {
                out.put("error", error);
            }
            logs.add(out);
        }

        // ---------- Prova ----------

        static Map<String, Object> proof(List<Object> entries) {
            Map<String, Object> req = payloadAt(entries, 0);
            Map<String, Object> res = payloadAt(entries, entries.size() - 1);
            Map<String, Object> rnd = null;
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> e = entryAt(entries, i);
                if ("ROUND".equals(e.get("type")) && req.get("rollId").equals(payloadAt(entries, i).get("rollId"))) {
                    rnd = payloadAt(entries, i);
                }
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("algorithm", "dice-v1");
            p.put("chainFormat", "chain-v1");
            Map<String, Object> drand = new LinkedHashMap<>();
            drand.put("network", Quicknet.NETWORK);
            drand.put("chainHash", Quicknet.CHAIN_HASH);
            p.put("drand", drand);
            Map<String, Object> beacon = new LinkedHashMap<>();
            if (rnd != null) {
                beacon.put("round", rnd.get("round"));
                beacon.put("randomness", rnd.get("randomness"));
                beacon.put("signature", rnd.get("signature"));
            }
            p.put("beacon", beacon);
            Map<String, Object> roll = new LinkedHashMap<>();
            for (String f : List.of("rollId", "sessionId", "nonce", "clientSeed", "expression", "targetRound", "requestedAt")) {
                putIfPresent(roll, f, req.get(f));
            }
            for (String f : List.of("dice", "modifier", "total")) {
                putIfPresent(roll, f, res.get(f));
            }
            p.put("roll", roll);
            p.put("entries", entries);
            return p;
        }

        static void putIfPresent(Map<String, Object> m, String key, Object value) {
            if (value != null) {
                m.put(key, value);
            }
        }

        /** Roll isolado (REQUEST, ROUND, RESULT), coerente nos hashes, com partes ajustáveis. */
        static List<Object> single(String expr, long target, Instant at, long round, String randomness, String signature,
                                   List<Integer> forcedDice) {
            Chain c = new Chain(SESSION);
            c.request(ROLL_A, "seed", expr, 1, target, at);
            c.round(ROLL_A, round, randomness, signature);
            RollResult r = DiceRoller.roll(randomness, "seed", 1, expr.replace(" ", ""));
            if (forcedDice != null) {
                int total = forcedDice.stream().mapToInt(Integer::intValue).sum() + r.modifier();
                r = new RollResult(r.expression(), forcedDice, r.modifier(), total, r.wordsConsumed(), r.rejections());
            }
            c.result(ROLL_A, r);
            return json(c.entries);
        }

        static void proofVectors(Chain c) {
            proofVector("proof-valid-interleaved", "roll A com o REQUEST de B no meio", proof(json(c.entries.subList(0, 4))), null);
            proofVector("proof-valid-interleaved-b", "roll B com ROUND e RESULT de A no meio",
                    proof(json(c.entries.subList(1, 6))), null);
            proofVector("proof-valid-adjacent", "roll C com REQUEST, ROUND e RESULT seguidos",
                    proof(json(c.entries.subList(6, 9))), null);

            Instant okAt = before(1_000_000, 4500);
            proofVector("proof-wrong-round", "ROUND usa o round 1000001 em vez do alvo 1000000",
                    proof(single("2d6+3", 1_000_000, okAt, 1_000_001, R1M1, S1M1, null)), "PROOF_WRONG_ROUND");
            proofVector("proof-beacon-invalid", "assinatura de outro round",
                    proof(single("2d6+3", 1_000_000, okAt, 1_000_000, R1M, S1M1, null)), "PROOF_BEACON_INVALID");
            proofVector("proof-target-not-future", "pedido no instante exato do round alvo",
                    proof(single("2d6+3", 1_000_000, Quicknet.timeOf(1_000_000), 1_000_000, R1M, S1M, null)),
                    "PROOF_TARGET_NOT_FUTURE");
            proofVector("proof-target-in-past", "pedido 10 s depois do round alvo",
                    proof(single("2d6+3", 1_000_000, Quicknet.timeOf(1_000_000).plusSeconds(10), 1_000_000, R1M, S1M, null)),
                    "PROOF_TARGET_NOT_FUTURE");
            RollResult honest = DiceRoller.roll(R1M, "seed", 1, "2d6+3");
            List<Integer> forged = new ArrayList<>(honest.dice());
            forged.set(0, forged.get(0) % 6 + 1);
            proofVector("proof-result-mismatch", "RESULT com um dado trocado (hashes refeitos)",
                    proof(single("2d6+3", 1_000_000, okAt, 1_000_000, R1M, S1M, forged)), "PROOF_RESULT_MISMATCH");

            List<Object> nonCanonical = single("2d6+3", 1_000_000, okAt, 1_000_000, R1M, S1M, null);
            payloadAt(nonCanonical, 0).put("expression", "2D6 + 3");
            relink(nonCanonical, 0);
            proofVector("proof-noncanonical-expression", "express\u00e3o fora da forma can\u00f4nica no REQUEST",
                    proof(nonCanonical), "PROOF_STRUCTURE");

            List<Object> v2 = single("2d6+3", 1_000_000, okAt, 1_000_000, R1M, S1M, null);
            payloadAt(v2, 0).put("algorithm", "dice-v2");
            relink(v2, 0);
            proofVector("proof-wrong-algorithm", "REQUEST declara dice-v2", proof(v2), "PROOF_STRUCTURE");

            List<Object> noRound = single("2d6+3", 1_000_000, okAt, 1_000_000, R1M, S1M, null);
            noRound.remove(1);
            payloadAt(noRound, 1).put("seq", 2L);
            entryAt(noRound, 1).put("seq", 2L);
            relink(noRound, 1);
            proofVector("proof-missing-round", "sem entrada ROUND", proof(noRound), "PROOF_STRUCTURE");

            List<Object> otherSession = json(c.entries.subList(0, 4));
            payloadAt(otherSession, 1).put("sessionId", OTHER_SESSION);
            relink(otherSession, 1);
            proofVector("proof-other-session-entry", "entrada de outra mesa no meio", proof(otherSession), "PROOF_STRUCTURE");

            List<Object> truncated = json(c.entries.subList(0, 3));
            proofVector("proof-ends-before-result", "termina no ROUND", proof(truncated), "PROOF_STRUCTURE");

            Map<String, Object> badHeader = proof(json(c.entries.subList(6, 9)));
            ((Map<String, Object>) badHeader.get("drand")).put("chainHash", "0".repeat(64));
            proofVector("proof-wrong-drand-chain", "chain hash do drand diferente do quicknet", badHeader, "PROOF_STRUCTURE");

            Map<String, Object> badTotal = proof(json(c.entries.subList(6, 9)));
            ((Map<String, Object>) badTotal.get("roll")).put("total", 99L);
            proofVector("proof-summary-total", "resumo diz total 99", badTotal, "PROOF_SUMMARY_MISMATCH");

            Map<String, Object> badBeacon = proof(json(c.entries.subList(6, 9)));
            ((Map<String, Object>) badBeacon.get("beacon")).put("randomness", R1M);
            proofVector("proof-summary-randomness", "resumo do beacon com randomness de outro round", badBeacon,
                    "PROOF_SUMMARY_MISMATCH");

            List<Object> noRehash = json(c.entries.subList(6, 9));
            payloadAt(noRehash, 2).put("total", 24L);
            proofVector("proof-tampered-no-rehash", "total do RESULT alterado sem refazer o hash", proof(noRehash),
                    "CHAIN_HASH_MISMATCH");

            List<Object> gap = json(c.entries.subList(0, 4));
            gap.remove(1);
            proofVector("proof-missing-middle-entry", "entrada do meio removida", proof(gap), "CHAIN_SEQ_GAP");

            List<Object> flt = json(c.entries.subList(6, 9));
            payloadAt(flt, 0).put("nonce", 3.5);
            proofVector("proof-float-nonce", "nonce n\u00e3o inteiro", proof(flt), "CHAIN_STRUCTURE");
        }

        static void proofVector(String id, String description, Map<String, Object> proof, String error) {
            ChainVerification v = ProofVerifier.verify(proof);
            check(id, v, error);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("description", description);
            out.put("proof", proof);
            out.put("valid", error == null);
            if (error != null) {
                out.put("error", error);
            }
            proofs.add(out);
        }

        static void check(String id, ChainVerification v, String error) {
            String got = v.valid() ? null : v.code().name();
            if (!java.util.Objects.equals(got, error)) {
                throw new IllegalStateException(id + ": esperado " + error + ", veio " + got + " (" + v.message() + ")");
            }
        }
    }

    /** Vetores da chain-v2 (spec/ALGORITHM.md, seção 7): player assinado com Ed25519 no REQUEST. */
    static final class ChainV2Vectors {

        // Chaves determinísticas do RFC 8032 (seção 7.1): TEST 1, 2, 3 e 1024. Nunca chaves de jogador.
        static final String SK_ANA = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
        static final String PK_ANA = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
        static final String SK_THORIN = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
        static final String PK_THORIN = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
        static final String SK_PHONE = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7";
        static final String PK_PHONE = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
        static final String SK_TABLET = "f5e5767cf153319517630f226876b86c8160cc583bc013744c6bf255f5cc0ee5";
        static final String PK_TABLET = "278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e";
        static final String RFC_SIG_1 = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065"
                + "224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
        static final String RFC_SIG_2 = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";

        // O celular da Ana foi vinculado pela identidade; o tablet, pelo celular.
        static final Player.Link LINK_PHONE = Player.link(SK_ANA, PK_ANA, PK_PHONE);
        static final Player.Link LINK_TABLET = Player.link(SK_PHONE, PK_ANA, PK_TABLET);

        static final String SESSION = ChainVectors.SESSION;
        static final String ROLL_A = ChainVectors.ROLL_A;
        static final String ROLL_B = ChainVectors.ROLL_B;
        static final String ROLL_C = ChainVectors.ROLL_C;
        static final String ROLL_D = "d2e3f4g5h6j7k8m9";
        static final String ROLL_E = "e3f4g5h6j7k8m9n0";
        static final String REQ_A = "6a1f0c3e9b2d4a5f8e7c6b5a49382716";
        static final String REQ_B = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
        static final String REQ_C = "c3a5e7092b4d6f8a1c3e5a7b9d0f2a4c";
        static final String REQ_D = "9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b";
        static final String THORIN = "Thorin 🪓";

        static final List<Map<String, Object>> signatures = new ArrayList<>();
        static final List<Map<String, Object>> linkSignatures = new ArrayList<>();
        static final List<Map<String, Object>> logs = new ArrayList<>();
        static final List<Map<String, Object>> proofs = new ArrayList<>();

        static void generate(Map<String, Object> root) {
            signatureVectors();
            linkVectors();
            ChainVectors.Chain session = sessionChain();
            logVectors(session);
            proofVectors(session);
            root.put("playerSignatureVectors", signatures);
            root.put("linkSignatureVectors", linkSignatures);
            root.put("logVectorsV2", logs);
            root.put("proofVectorsV2", proofs);
            System.out.printf("playerSignature=%d linkSignature=%d logV2=%d proofV2=%d%n", signatures.size(),
                    linkSignatures.size(), logs.size(), proofs.size());
        }

        /** Ana no computador: o aparelho é a própria identidade. */
        static Player ana(String seed, String expr, String requestId) {
            return Player.sign(SK_ANA, PK_ANA, SESSION, seed, expr, "Ana", requestId);
        }

        static Player anaPhone(String seed, String expr, String requestId) {
            return Player.sign(SK_PHONE, PK_PHONE, PK_ANA, List.of(LINK_PHONE), SESSION, seed, expr, "Ana", requestId);
        }

        static Player anaTablet(String seed, String expr, String requestId) {
            return Player.sign(SK_TABLET, PK_TABLET, PK_ANA, List.of(LINK_PHONE, LINK_TABLET), SESSION, seed, expr,
                    "Ana", requestId);
        }

        static Player thorin(String seed, String expr, String requestId) {
            return Player.sign(SK_THORIN, PK_THORIN, SESSION, seed, expr, THORIN, requestId);
        }

        static String flipLastHex(String hex) {
            char last = hex.charAt(hex.length() - 1);
            return hex.substring(0, hex.length() - 1) + (last == '0' ? '1' : '0');
        }

        // ---------- Assinaturas ----------

        static void signatureVectors() {
            signature("rfc8032-test-1", "RFC 8032, TEST 1: mensagem vazia", PK_ANA, new byte[0], RFC_SIG_1, null, true);
            signature("rfc8032-test-2", "RFC 8032, TEST 2: mensagem 0x72", PK_THORIN, new byte[] {0x72}, RFC_SIG_2, null,
                    true);
            String seed = "mesa|\"dragão\"";
            Map<String, Object> req = request(SESSION, seed, "2d6+3", PK_THORIN, THORIN, REQ_B);
            Player p = thorin(seed, "2d6+3", REQ_B);
            byte[] message = Player.message(SESSION, seed, "2d6+3", PK_THORIN, THORIN, REQ_B);
            signature("request-valid", "pedido assinado; a mensagem é o JSON canônico do pedido", PK_THORIN,
                    message, p.signature(), req, true);
            signature("request-bit-flipped", "último hex da assinatura trocado", PK_THORIN, message,
                    flipLastHex(p.signature()), req, false);
            signature("request-other-key", "assinatura válida, mas conferida com a chave de outro jogador", PK_ANA,
                    message, p.signature(), req, false);
            Map<String, Object> phoneReq = request(SESSION, "s", "1d20", PK_ANA, "Ana", REQ_C);
            byte[] phoneMessage = Player.message(SESSION, "s", "1d20", PK_ANA, "Ana", REQ_C);
            signature("request-linked-device", "pedido do celular da Ana: assina o aparelho, a identidade entra na "
                    + "mensagem", PK_PHONE, phoneMessage, anaPhone("s", "1d20", REQ_C).signature(), phoneReq, true);
        }

        static Map<String, Object> request(String sessionId, String seed, String expr, String identityKey, String name,
                                           String requestId) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", sessionId);
            m.put("clientSeed", seed);
            m.put("expression", expr);
            m.put("identityKey", identityKey);
            m.put("name", name);
            m.put("requestId", requestId);
            return m;
        }

        static void signature(String id, String description, String publicKey, byte[] message, String sig,
                              Map<String, Object> request, boolean valid) {
            if (Player.verify(publicKey, message, sig) != valid) {
                throw new IllegalStateException(id + ": verificação diferente do esperado");
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", id);
            v.put("description", description);
            if (request != null) {
                v.put("request", request);
                v.put("message", new String(message, StandardCharsets.UTF_8));
            }
            v.put("messageHex", HexFormat.of().formatHex(message));
            v.put("publicKey", publicKey);
            v.put("signature", sig);
            v.put("valid", valid);
            signatures.add(v);
        }

        static void linkVectors() {
            link("link-identity-to-phone", "a identidade da Ana vincula o celular", PK_ANA, PK_ANA, PK_PHONE,
                    LINK_PHONE.signature(), true);
            link("link-phone-to-tablet", "o celular, já vinculado, vincula o tablet", PK_PHONE, PK_ANA, PK_TABLET,
                    LINK_TABLET.signature(), true);
            link("link-other-identity", "o mesmo vínculo conferido para outra identidade", PK_ANA, PK_THORIN,
                    PK_PHONE, LINK_PHONE.signature(), false);
        }

        static void link(String id, String description, String issuer, String identityKey, String deviceKey,
                         String sig, boolean valid) {
            byte[] message = Player.linkMessage(identityKey, deviceKey);
            if (Player.verify(issuer, message, sig) != valid) {
                throw new IllegalStateException(id + ": verificação diferente do esperado");
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", id);
            v.put("description", description);
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("identityKey", identityKey);
            l.put("deviceKey", deviceKey);
            v.put("link", l);
            v.put("message", new String(message, StandardCharsets.UTF_8));
            v.put("messageHex", HexFormat.of().formatHex(message));
            v.put("issuerKey", issuer);
            v.put("signature", sig);
            v.put("valid", valid);
            linkSignatures.add(v);
        }

        // ---------- Cadeia ----------

        /**
         * Mesma mesa da chain-v1: Ana rola A no computador, Thorin rola B, Ana rola C no celular (1 vínculo)
         * e D no tablet (2 vínculos). C e D usam o mesmo round alvo.
         */
        static ChainVectors.Chain sessionChain() {
            ChainVectors.Chain c = new ChainVectors.Chain(SESSION);
            c.request(ROLL_A, "mesa-do-dragão", "2d6+3", 1, 1_000_000, ChainVectors.before(1_000_000, 4500),
                    ana("mesa-do-dragão", "2d6+3", REQ_A));
            c.request(ROLL_B, THORIN, "1d20+5", 2, 1_000_001, ChainVectors.before(1_000_001, 5250),
                    thorin(THORIN, "1d20+5", REQ_B));
            c.round(ROLL_A, 1_000_000, R1M, S1M);
            c.result(ROLL_A, DiceRoller.roll(R1M, "mesa-do-dragão", 1, "2d6+3"));
            c.round(ROLL_B, 1_000_001, ChainVectors.R1M1, ChainVectors.S1M1);
            c.result(ROLL_B, DiceRoller.roll(ChainVectors.R1M1, THORIN, 2, "1d20+5"));
            c.request(ROLL_C, "c|\"aspas\"", "4d6-1", 3, 1_000_002, ChainVectors.before(1_000_002, 3001),
                    anaPhone("c|\"aspas\"", "4d6-1", REQ_C));
            c.round(ROLL_C, 1_000_002, ChainVectors.R1M2, ChainVectors.S1M2);
            c.result(ROLL_C, DiceRoller.roll(ChainVectors.R1M2, "c|\"aspas\"", 3, "4d6-1"));
            c.request(ROLL_D, "tablet", "1d100", 4, 1_000_003, ChainVectors.before(1_000_003, 3500),
                    anaTablet("tablet", "1d100", REQ_D));
            return c;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> playerAt(List<Object> entries, int i) {
            return (Map<String, Object>) ChainVectors.payloadAt(entries, i).get("player");
        }

        @SuppressWarnings("unchecked")
        static List<Object> linksAt(List<Object> entries, int i) {
            return (List<Object>) playerAt(entries, i).get("links");
        }

        /** Troca o player do REQUEST i e refaz os hashes dali em diante. */
        static List<Object> withPlayer(ChainVectors.Chain c, int i, Player player) {
            List<Object> entries = ChainVectors.json(c.entries);
            ChainVectors.payloadAt(entries, i).put("player", ChainVectors.deepCopy(player.toPayload()));
            ChainVectors.relink(entries, i);
            return entries;
        }

        static void logVectors(ChainVectors.Chain c) {
            String genesis = ChainEntry.GENESIS_PREV_HASH;
            List<Object> full = ChainVectors.json(c.entries);
            log("log-v2-valid-full", "mesa inteira: computador, outro jogador, celular e tablet vinculados", genesis,
                    full, null);
            log("log-v2-valid-middle", "segmento do meio com o hash anterior conhecido", c.entries.get(2).hash(),
                    ChainVectors.json(c.entries.subList(3, 10)), null);

            List<Object> flipped = ChainVectors.json(c.entries);
            playerAt(flipped, 0).put("signature", flipLastHex((String) playerAt(flipped, 0).get("signature")));
            ChainVectors.relink(flipped, 0);
            log("log-v2-signature-flipped", "assinatura do REQUEST com um hex trocado (hashes refeitos)", genesis, flipped,
                    "CHAIN_PLAYER_SIGNATURE");

            List<Object> renamed = ChainVectors.json(c.entries);
            playerAt(renamed, 0).put("name", "Thorin");
            ChainVectors.relink(renamed, 0);
            log("log-v2-name-swapped", "apelido trocado depois da assinatura", genesis, renamed, "CHAIN_PLAYER_SIGNATURE");

            List<Object> impersonated = ChainVectors.json(c.entries);
            playerAt(impersonated, 0).put("publicKey", PK_THORIN);
            playerAt(impersonated, 0).put("identityKey", PK_THORIN);
            ChainVectors.relink(impersonated, 0);
            log("log-v2-key-swapped", "roll de Ana atribuído à chave de Thorin", genesis, impersonated,
                    "CHAIN_PLAYER_SIGNATURE");

            List<Object> reseeded = ChainVectors.json(c.entries);
            ChainVectors.payloadAt(reseeded, 0).put("clientSeed", "outra-seed");
            ChainVectors.relink(reseeded, 0);
            log("log-v2-seed-rewritten", "servidor trocou a seed assinada", genesis, reseeded, "CHAIN_PLAYER_SIGNATURE");

            List<Object> linkFlipped = ChainVectors.json(c.entries);
            @SuppressWarnings("unchecked")
            Map<String, Object> link = (Map<String, Object>) linksAt(linkFlipped, 6).get(0);
            link.put("signature", flipLastHex((String) link.get("signature")));
            ChainVectors.relink(linkFlipped, 6);
            log("log-v2-link-flipped", "vínculo do celular com um hex trocado", genesis, linkFlipped,
                    "CHAIN_PLAYER_SIGNATURE");

            Player phone = anaPhone("c|\"aspas\"", "4d6-1", REQ_C);
            log("log-v2-link-other-identity", "vínculo da Ana apresentado como de outra identidade", genesis,
                    withPlayer(c, 6, new Player(phone.publicKey(), phone.name(), phone.requestId(), phone.signature(),
                            PK_THORIN, phone.links())), "CHAIN_PLAYER_SIGNATURE");
            log("log-v2-reattributed", "Thorin vincula o celular da Ana a si e toma o pedido: os vínculos fecham, "
                    + "mas a assinatura do pedido cobre a identidade", genesis,
                    withPlayer(c, 6, new Player(phone.publicKey(), phone.name(), phone.requestId(), phone.signature(),
                            PK_THORIN, List.of(Player.link(SK_THORIN, PK_THORIN, PK_PHONE)))), "CHAIN_PLAYER_SIGNATURE");
            Player tablet = anaTablet("tablet", "1d100", REQ_D);
            log("log-v2-links-short", "cadeia de vínculos que para no celular, sem chegar ao tablet", genesis,
                    withPlayer(c, 9, new Player(tablet.publicKey(), tablet.name(), tablet.requestId(), tablet.signature(),
                            PK_ANA, List.of(LINK_PHONE))), "CHAIN_PLAYER_SIGNATURE");
            Player desk = ana("mesa-do-dragão", "2d6+3", REQ_A);
            log("log-v2-identity-without-links", "identidade diferente da chave do aparelho e nenhum vínculo", genesis,
                    withPlayer(c, 0, new Player(desk.publicKey(), desk.name(), desk.requestId(), desk.signature(),
                            PK_THORIN, List.of())), "CHAIN_PLAYER_SIGNATURE");

            ChainVectors.Chain replay = sessionChain();
            replay.request(ROLL_E, "mesa-do-dragão", "2d6+3", 5, 1_000_005, ChainVectors.before(1_000_005, 4000),
                    ana("mesa-do-dragão", "2d6+3", REQ_A));
            log("log-v2-replay", "pedido assinado de Ana reaproveitado num roll novo", genesis,
                    ChainVectors.json(replay.entries), "CHAIN_REQUEST_REPLAY");

            List<Object> missing = ChainVectors.json(c.entries);
            ChainVectors.payloadAt(missing, 0).remove("player");
            ChainVectors.relink(missing, 0);
            log("log-v2-missing-player", "REQUEST sem player", genesis, missing, "CHAIN_STRUCTURE");

            List<Object> extra = ChainVectors.json(c.entries);
            playerAt(extra, 0).put("role", "mestre");
            ChainVectors.relink(extra, 0);
            log("log-v2-player-extra-field", "player com campo a mais", genesis, extra, "CHAIN_STRUCTURE");

            List<Object> noLinks = ChainVectors.json(c.entries);
            playerAt(noLinks, 0).remove("links");
            ChainVectors.relink(noLinks, 0);
            log("log-v2-player-without-links", "player sem o campo links", genesis, noLinks, "CHAIN_STRUCTURE");

            List<Object> linkExtra = ChainVectors.json(c.entries);
            @SuppressWarnings("unchecked")
            Map<String, Object> extraLink = (Map<String, Object>) linksAt(linkExtra, 6).get(0);
            extraLink.put("issuerKey", PK_ANA);
            ChainVectors.relink(linkExtra, 6);
            log("log-v2-link-extra-field", "vínculo com campo a mais", genesis, linkExtra, "CHAIN_STRUCTURE");

            List<Player.Link> nine = new ArrayList<>(java.util.Collections.nCopies(9, LINK_PHONE));
            log("log-v2-too-many-links", "nove vínculos (o máximo é oito)", genesis,
                    withPlayer(c, 6, new Player(phone.publicKey(), phone.name(), phone.requestId(), phone.signature(),
                            PK_ANA, nine)), "CHAIN_STRUCTURE");

            List<Object> upper = ChainVectors.json(c.entries);
            playerAt(upper, 0).put("publicKey", PK_ANA.toUpperCase());
            ChainVectors.relink(upper, 0);
            log("log-v2-uppercase-key", "chave pública em hex maiúsculo", genesis, upper, "CHAIN_STRUCTURE");

            ChainVectors.Chain longName = new ChainVectors.Chain(SESSION);
            longName.request(ROLL_A, "s", "1d20", 1, 1_000_000, ChainVectors.before(1_000_000, 4500),
                    Player.sign(SK_ANA, PK_ANA, SESSION, "s", "1d20", "a".repeat(33), REQ_A));
            log("log-v2-name-too-long", "apelido com 33 bytes, mesmo assinado", genesis,
                    ChainVectors.json(longName.entries), "CHAIN_STRUCTURE");
        }

        static void log(String id, String description, String prevHashOfFirst, List<Object> entries, String error) {
            ChainVerification v = ChainVerifier.verifySegmentJson(entries, prevHashOfFirst, ChainFormat.CHAIN_V2);
            ChainVectors.check(id, v, error);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("description", description);
            out.put("prevHashOfFirst", prevHashOfFirst);
            out.put("entries", entries);
            out.put("valid", error == null);
            if (error != null) {
                out.put("error", error);
            }
            logs.add(out);
        }

        // ---------- Prova ----------

        @SuppressWarnings("unchecked")
        static Map<String, Object> proof(List<Object> entries) {
            Map<String, Object> p = ChainVectors.proof(entries);
            p.put("chainFormat", "chain-v2");
            Object player = ChainVectors.payloadAt(entries, 0).get("player");
            if (player != null) {
                ((Map<String, Object>) p.get("roll")).put("player", ChainVectors.deepCopy(player));
            }
            return p;
        }

        /** Roll D (tablet, 2 vínculos) resolvido no round 1000003, que não temos: usa o 1000002 como alvo. */
        static List<Object> tabletRoll() {
            ChainVectors.Chain t = new ChainVectors.Chain(SESSION);
            t.request(ROLL_D, "tablet", "1d100", 1, 1_000_002, ChainVectors.before(1_000_002, 2500),
                    anaTablet("tablet", "1d100", REQ_D));
            t.round(ROLL_D, 1_000_002, ChainVectors.R1M2, ChainVectors.S1M2);
            t.result(ROLL_D, DiceRoller.roll(ChainVectors.R1M2, "tablet", 1, "1d100"));
            return ChainVectors.json(t.entries);
        }

        @SuppressWarnings("unchecked")
        static void proofVectors(ChainVectors.Chain c) {
            proofVector("proof-v2-valid-interleaved", "roll A de Ana com o REQUEST assinado de Thorin no meio",
                    proof(ChainVectors.json(c.entries.subList(0, 4))), null);
            proofVector("proof-v2-valid-linked-phone", "roll C do celular da Ana (1 vínculo)",
                    proof(ChainVectors.json(c.entries.subList(6, 9))), null);
            proofVector("proof-v2-valid-linked-tablet", "roll do tablet da Ana (2 vínculos)", proof(tabletRoll()),
                    null);

            Map<String, Object> renamed = proof(ChainVectors.json(c.entries.subList(6, 9)));
            ((Map<String, Object>) ((Map<String, Object>) renamed.get("roll")).get("player")).put("name", "Thorin");
            proofVector("proof-v2-summary-name", "resumo diz que o roll é de Thorin", renamed,
                    "PROOF_SUMMARY_MISMATCH");

            Map<String, Object> otherIdentity = proof(ChainVectors.json(c.entries.subList(6, 9)));
            ((Map<String, Object>) ((Map<String, Object>) otherIdentity.get("roll")).get("player"))
                    .put("identityKey", PK_THORIN);
            proofVector("proof-v2-summary-identity", "resumo atribui o roll a outra identidade", otherIdentity,
                    "PROOF_SUMMARY_MISMATCH");

            Map<String, Object> noPlayer = proof(ChainVectors.json(c.entries.subList(6, 9)));
            ((Map<String, Object>) noPlayer.get("roll")).remove("player");
            proofVector("proof-v2-summary-missing-player", "resumo sem player", noPlayer, "PROOF_SUMMARY_MISMATCH");

            List<Object> flipped = ChainVectors.json(c.entries.subList(6, 9));
            playerAt(flipped, 0).put("signature", flipLastHex((String) playerAt(flipped, 0).get("signature")));
            ChainVectors.relink(flipped, 0);
            proofVector("proof-v2-signature-flipped", "assinatura inválida no REQUEST (hashes refeitos)",
                    proof(flipped), "CHAIN_PLAYER_SIGNATURE");

            List<Object> otherSigner = ChainVectors.json(c.entries.subList(0, 4));
            playerAt(otherSigner, 1).put("name", "Ana");
            ChainVectors.relink(otherSigner, 1);
            proofVector("proof-v2-bad-signature-in-middle", "REQUEST de outro roll no meio com assinatura inválida",
                    proof(otherSigner), "CHAIN_PLAYER_SIGNATURE");

            Map<String, Object> v1Entries = proof(ChainVectors.json(ChainVectors.sessionChain().entries.subList(6, 9)));
            proofVector("proof-v2-without-player", "entradas da chain-v1 declaradas como chain-v2", v1Entries,
                    "CHAIN_STRUCTURE");

            Map<String, Object> declaredV1 = ChainVectors.proof(ChainVectors.json(c.entries.subList(6, 9)));
            proofVector("proof-v2-declared-v1", "entradas v2 numa prova chain-v1: a v1 ignora o player e não "
                    + "atesta quem rolou", declaredV1, null);

            Map<String, Object> unknown = proof(ChainVectors.json(c.entries.subList(6, 9)));
            unknown.put("chainFormat", "chain-v3");
            proofVector("proof-v2-unknown-format", "chainFormat desconhecido", unknown, "PROOF_STRUCTURE");
        }

        static void proofVector(String id, String description, Map<String, Object> proof, String error) {
            ChainVerification v = ProofVerifier.verify(proof);
            ChainVectors.check(id, v, error);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("description", description);
            out.put("proof", proof);
            out.put("valid", error == null);
            if (error != null) {
                out.put("error", error);
            }
            proofs.add(out);
        }
    }

    static void beacon(String id, long round, String signature, String randomness) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("round", round);
        v.put("signature", signature);
        v.put("randomness", randomness);
        v.put("valid", BeaconCheck.randomnessMatchesSignature(signature, randomness));
        beacon.add(v);
    }

    static void roll(String id, String description, String randomness, String seed, long nonce, String expression) {
        RollResult r = DiceRoller.roll(randomness, seed, nonce, expression);
        if (id.startsWith("rejection-") && r.rejections().isEmpty()) {
            throw new IllegalStateException(id + " sem rejeição");
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("description", description);
        v.put("randomness", randomness);
        v.put("clientSeed", seed);
        v.put("clientSeedUtf8Hex", HexFormat.of().formatHex(seed.getBytes(StandardCharsets.UTF_8)));
        v.put("nonce", nonce);
        v.put("expression", expression);
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("expression", r.expression());
        exp.put("dice", r.dice());
        exp.put("modifier", r.modifier());
        exp.put("total", r.total());
        exp.put("wordsConsumed", r.wordsConsumed());
        List<Object> rej = new ArrayList<>();
        for (Rejection j : r.rejections()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wordIndex", j.wordIndex());
            m.put("value", j.value());
            rej.add(m);
        }
        exp.put("rejections", rej);
        v.put("expected", exp);
        rolls.add(v);
    }

    static void error(String id, String randomness, String seed, long nonce, String expression, String code) {
        try {
            DiceRoller.roll(randomness, seed, nonce, expression);
            throw new IllegalStateException(id + " deveria falhar");
        } catch (DiceException e) {
            if (!e.code().name().equals(code)) {
                throw new IllegalStateException(id + ": esperado " + code + ", veio " + e.code());
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("randomness", randomness);
        v.put("clientSeed", seed);
        v.put("nonce", nonce);
        v.put("expression", expression);
        v.put("error", code);
        errors.add(v);
    }

    // JSON mínimo: objetos com quebra de linha, listas de escalares numa linha só.
    static String toJson(Object o, String indent) {
        if (o instanceof String s) {
            return quote(s);
        }
        if (o instanceof Number || o instanceof Boolean) {
            return o.toString();
        }
        String inner = indent + "  ";
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            List<String> parts = new ArrayList<>();
            m.forEach((k, v) -> parts.add(inner + quote((String) k) + ": " + toJson(v, inner)));
            return "{\n" + String.join(",\n", parts) + "\n" + indent + "}";
        }
        if (o instanceof List<?> l) {
            if (l.stream().allMatch(x -> x instanceof Number)) {
                return "[" + String.join(", ", l.stream().map(Object::toString).toList()) + "]";
            }
            if (l.isEmpty()) {
                return "[]";
            }
            List<String> parts = new ArrayList<>();
            l.forEach(x -> parts.add(inner + toJson(x, inner)));
            return "[\n" + String.join(",\n", parts) + "\n" + indent + "]";
        }
        throw new IllegalArgumentException(String.valueOf(o));
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
