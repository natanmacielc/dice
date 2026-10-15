package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.natanmacielc.dice.core.chain.CanonicalJson;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.ChainVerification;
import io.github.natanmacielc.dice.core.chain.ChainVerifier;
import io.github.natanmacielc.dice.core.chain.Player;
import io.github.natanmacielc.dice.core.chain.ProofVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Roda spec/test-vectors.json, os mesmos vetores dos verificadores JS e Python. */
class TestVectorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode root;

    @BeforeAll
    static void load() throws IOException {
        root = MAPPER.readTree(Path.of(System.getProperty("vectors.path")).toFile());
    }

    @Test
    void headerAndMinimumCount() {
        assertEquals("dice-v1", root.get("algorithm").asText());
        int total = root.get("beaconVectors").size() + root.get("rollVectors").size() + root.get("errorVectors").size();
        assertTrue(total >= 30, "vetores: " + total);
    }

    @TestFactory
    Stream<DynamicTest> beaconVectors() {
        return stream("beaconVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () ->
                assertEquals(v.get("valid").asBoolean(),
                        BeaconCheck.randomnessMatchesSignature(v.get("signature").asText(), v.get("randomness").asText()))));
    }

    @TestFactory
    Stream<DynamicTest> rollVectors() {
        return stream("rollVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            assertEquals(v.get("clientSeedUtf8Hex").asText(),
                    HexFormat.of().formatHex(v.get("clientSeed").asText().getBytes(StandardCharsets.UTF_8)));
            RollResult r = DiceRoller.roll(v.get("randomness").asText(), v.get("clientSeed").asText(),
                    v.get("nonce").asLong(), v.get("expression").asText());
            JsonNode exp = v.get("expected");
            assertEquals(exp.get("expression").asText(), r.expression());
            assertEquals(ints(exp.get("dice")), r.dice());
            assertEquals(exp.get("modifier").asInt(), r.modifier());
            assertEquals(exp.get("total").asInt(), r.total());
            assertEquals(exp.get("wordsConsumed").asLong(), r.wordsConsumed());
            List<Rejection> rejections = new ArrayList<>();
            exp.get("rejections").forEach(j -> rejections.add(new Rejection(j.get("wordIndex").asLong(), j.get("value").asLong())));
            assertEquals(rejections, r.rejections());
            assertTrue(RollVerifier.verify(v.get("randomness").asText(), v.get("clientSeed").asText(),
                    v.get("nonce").asLong(), v.get("expression").asText(), r.dice(), r.total()).valid());
        }));
    }

    @TestFactory
    Stream<DynamicTest> errorVectors() {
        return stream("errorVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            JsonNode nonce = v.get("nonce");
            DiceException ex = assertThrows(DiceException.class, () -> DiceRoller.roll(v.get("randomness").asText(),
                    v.get("clientSeed").asText(), nonce.asLong(), v.get("expression").asText()));
            assertEquals(v.get("error").asText(), ex.code().name());
        }));
    }

    @TestFactory
    Stream<DynamicTest> canonicalJsonVectors() {
        return stream("canonicalJsonVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () ->
                assertEquals(v.get("canonical").asText(), CanonicalJson.write(tree(v.get("value"))))));
    }

    @TestFactory
    Stream<DynamicTest> logVectors() {
        return stream("logVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            ChainVerification result = ChainVerifier.verifySegmentJson((List<?>) tree(v.get("entries")),
                    v.get("prevHashOfFirst").asText());
            assertOutcome(v, result);
        }));
    }

    @TestFactory
    Stream<DynamicTest> proofVectors() {
        return stream("proofVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> proof = (Map<String, Object>) tree(v.get("proof"));
            assertOutcome(v, ProofVerifier.verify(proof));
        }));
    }

    @TestFactory
    Stream<DynamicTest> playerSignatureVectors() {
        return stream("playerSignatureVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            byte[] message = HexFormat.of().parseHex(v.get("messageHex").asText());
            JsonNode req = v.get("request");
            if (req != null) {
                byte[] built = Player.message(req.get("sessionId").asText(), req.get("clientSeed").asText(),
                        req.get("expression").asText(), req.get("identityKey").asText(), req.get("name").asText(),
                        req.get("requestId").asText());
                assertEquals(v.get("message").asText(), new String(built, StandardCharsets.UTF_8));
                assertEquals(v.get("messageHex").asText(), HexFormat.of().formatHex(built));
            }
            assertEquals(v.get("valid").asBoolean(),
                    Player.verify(v.get("publicKey").asText(), message, v.get("signature").asText()));
        }));
    }

    @TestFactory
    Stream<DynamicTest> linkSignatureVectors() {
        return stream("linkSignatureVectors").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            byte[] built = Player.linkMessage(v.get("link").get("identityKey").asText(),
                    v.get("link").get("deviceKey").asText());
            assertEquals(v.get("message").asText(), new String(built, StandardCharsets.UTF_8));
            assertEquals(v.get("messageHex").asText(), HexFormat.of().formatHex(built));
            assertEquals(v.get("valid").asBoolean(),
                    Player.verify(v.get("issuerKey").asText(), built, v.get("signature").asText()));
        }));
    }

    @TestFactory
    Stream<DynamicTest> logVectorsV2() {
        return stream("logVectorsV2").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            ChainVerification result = ChainVerifier.verifySegmentJson((List<?>) tree(v.get("entries")),
                    v.get("prevHashOfFirst").asText(), ChainFormat.CHAIN_V2);
            assertOutcome(v, result);
        }));
    }

    @TestFactory
    Stream<DynamicTest> proofVectorsV2() {
        return stream("proofVectorsV2").map(v -> DynamicTest.dynamicTest(v.get("id").asText(), () -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> proof = (Map<String, Object>) tree(v.get("proof"));
            assertOutcome(v, ProofVerifier.verify(proof));
        }));
    }

    private static void assertOutcome(JsonNode v, ChainVerification result) {
        assertEquals(v.get("valid").asBoolean(), result.valid(), result.message());
        if (!result.valid()) {
            assertEquals(v.get("error").asText(), result.code().name(), result.message());
        }
    }

    private static Object tree(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    private static Stream<JsonNode> stream(String field) {
        return StreamSupport.stream(root.get(field).spliterator(), false);
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }
}
