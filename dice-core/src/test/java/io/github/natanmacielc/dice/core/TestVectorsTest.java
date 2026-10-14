package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Roda spec/test-vectors.json, os mesmos vetores dos verificadores JS e Python. */
class TestVectorsTest {

    private static JsonNode root;

    @BeforeAll
    static void load() throws IOException {
        root = new ObjectMapper().readTree(Path.of(System.getProperty("vectors.path")).toFile());
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

    private static Stream<JsonNode> stream(String field) {
        return StreamSupport.stream(root.get(field).spliterator(), false);
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }
}
