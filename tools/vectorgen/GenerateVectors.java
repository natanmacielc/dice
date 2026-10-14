import io.github.natanmacielc.dice.core.BeaconCheck;
import io.github.natanmacielc.dice.core.DiceException;
import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Rejection;
import io.github.natanmacielc.dice.core.RollResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        root.put("drand", Map.of("network", "quicknet",
                "chainHash", "52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971"));
        root.put("beaconVectors", beacon);
        root.put("rollVectors", rolls);
        root.put("errorVectors", errors);
        Files.writeString(Path.of(args[0]), toJson(root, "") + "\n", StandardCharsets.US_ASCII);
        System.out.printf("beacon=%d roll=%d error=%d%n", beacon.size(), rolls.size(), errors.size());
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
