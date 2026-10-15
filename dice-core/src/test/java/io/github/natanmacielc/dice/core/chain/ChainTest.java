package io.github.natanmacielc.dice.core.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChainTest {

    static final String ZEROS = ChainEntry.GENESIS_PREV_HASH;

    static List<ChainEntry> sampleChain() {
        List<ChainEntry> out = new ArrayList<>();
        ChainEntry e1 = ChainEntry.create(1, EntryType.REQUEST, ZEROS, Payloads.request(1, "sess", "roll-a", "seed", "1d20",
                1, 1_000_000, Instant.parse("2023-09-27T08:29:20.500Z")));
        out.add(e1);
        ChainEntry e2 = ChainEntry.create(2, EntryType.REQUEST, e1.hash(), Payloads.request(2, "sess", "roll-b", "seed", "2d6",
                2, 1_000_001, Instant.parse("2023-09-27T08:29:21.000Z")));
        out.add(e2);
        return out;
    }

    @Test
    void hashIsSha256OfPrevHashTypeAndCanonicalPayload() throws Exception {
        ChainEntry e = sampleChain().get(0);
        String preimage = ZEROS + "REQUEST" + CanonicalJson.write(e.payload());
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(preimage.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, e.hash());
        assertTrue(CanonicalJson.write(e.payload()).startsWith("{\"algorithm\":\"dice-v1\",\"clientSeed\":\"seed\""));
    }

    @Test
    void requestedAtAlwaysHasMilliseconds() {
        assertEquals("2023-09-27T08:29:21.000Z", Payloads.formatInstant(Instant.parse("2023-09-27T08:29:21Z")));
        assertEquals("2023-09-27T08:29:21.123Z", Payloads.formatInstant(Instant.parse("2023-09-27T08:29:21.123987Z")));
    }

    @Test
    void validSegmentPasses() {
        assertTrue(ChainVerifier.verifySegment(sampleChain(), ZEROS).valid());
    }

    @Test
    void detectsEachFaultInOrder() {
        List<ChainEntry> c = sampleChain();
        ChainEntry e1 = c.get(0);
        ChainEntry e2 = c.get(1);

        assertCode(ChainErrorCode.CHAIN_LINK_BROKEN, List.of(e1, e2), "f".repeat(64));
        assertCode(ChainErrorCode.CHAIN_SEQ_GAP,
                List.of(e1, new ChainEntry(3, e2.type(), e2.prevHash(), e2.payload(), e2.hash())), ZEROS);

        Map<String, Object> wrongSeq = new HashMap<>(e2.payload());
        wrongSeq.put("seq", 7L);
        assertCode(ChainErrorCode.CHAIN_SEQ_MISMATCH,
                List.of(e1, ChainEntry.create(2, EntryType.REQUEST, e1.hash(), wrongSeq)), ZEROS);

        Map<String, Object> tampered = new HashMap<>(e1.payload());
        tampered.put("clientSeed", "outra");
        assertCode(ChainErrorCode.CHAIN_HASH_MISMATCH,
                List.of(new ChainEntry(1, "REQUEST", ZEROS, tampered, e1.hash()), e2), ZEROS);

        assertCode(ChainErrorCode.CHAIN_STRUCTURE,
                List.of(new ChainEntry(1, "EXPIRE", ZEROS, e1.payload(), e1.hash())), ZEROS);
    }

    @Test
    void parsesGenericJsonTree() {
        ChainEntry e = sampleChain().get(0);
        Map<String, Object> json = e.toJson();
        assertEquals(e, ChainEntry.fromJson(json));
    }

    private static void assertCode(ChainErrorCode code, List<ChainEntry> entries, String prev) {
        ChainVerification v = ChainVerifier.verifySegment(entries, prev);
        assertEquals(code, v.code(), v.message());
    }
}
