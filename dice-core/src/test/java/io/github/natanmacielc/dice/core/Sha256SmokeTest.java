package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Teste de fumaça do scaffold: garante que JUnit roda e que SHA-256 está disponível. */
class Sha256SmokeTest {

    @Test
    void sha256OfAbcMatchesFips180Vector() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HexFormat.of().formatHex(digest));
    }
}
