package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InputValidationTest {

    @Test
    void acceptsLowercaseHexRandomness() {
        assertDoesNotThrow(() -> Randomness.of("b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "B22AAD4794F7451896F7A371AA46106FD84D919F3F569ACD5B2FDDF1D1440AF3",
        "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af",
        "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af30",
        "g22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3",
    })
    void rejectsMalformedRandomness(String hex) {
        DiceException ex = assertThrows(DiceException.class, () -> Randomness.of(hex));
        assertEquals(ErrorCode.RANDOMNESS_FORMAT, ex.code());
    }

    @Test
    void seedKeepsExactUtf8Bytes() {
        String decomposed = "é"; // não normaliza: 'e' + acento combinante
        assertArrayEquals(decomposed.getBytes(StandardCharsets.UTF_8), ClientSeed.of(decomposed).utf8());
        assertArrayEquals("a|b".getBytes(StandardCharsets.UTF_8), ClientSeed.of("a|b").utf8());
    }

    @Test
    void seedLengthIsCountedInBytes() {
        assertDoesNotThrow(() -> ClientSeed.of("x".repeat(64)));
        assertDoesNotThrow(() -> ClientSeed.of("😀".repeat(16))); // 16 emojis x 4 bytes
        assertSeedCode(ErrorCode.SEED_TOO_LONG, "x".repeat(65));
        assertSeedCode(ErrorCode.SEED_TOO_LONG, "é".repeat(33)); // 66 bytes
    }

    @Test
    void seedRejectsEmptyControlAndMalformed() {
        assertSeedCode(ErrorCode.SEED_EMPTY, "");
        assertSeedCode(ErrorCode.SEED_CONTROL_CHAR, "a\nb");
        assertSeedCode(ErrorCode.SEED_CONTROL_CHAR, "\u0000");
        assertSeedCode(ErrorCode.SEED_CONTROL_CHAR, "a\u007f");
        assertSeedCode(ErrorCode.SEED_INVALID_UTF8, "a\uD800");
        assertSeedCode(ErrorCode.SEED_INVALID_UTF8, "\uDC00a");
        assertSeedCode(ErrorCode.SEED_INVALID_UTF8, "\uDE00\uD83D");
    }

    @Test
    void nonceRange() {
        assertDoesNotThrow(() -> Nonce.check(0));
        assertDoesNotThrow(() -> Nonce.check(Nonce.MAX));
        assertEquals(9007199254740991L, Nonce.MAX);
        assertEquals(ErrorCode.NONCE_OUT_OF_RANGE,
                assertThrows(DiceException.class, () -> Nonce.check(-1)).code());
        assertEquals(ErrorCode.NONCE_OUT_OF_RANGE,
                assertThrows(DiceException.class, () -> Nonce.check(Nonce.MAX + 1)).code());
    }

    @Test
    void validationOrderIsRandomnessSeedNonceExpression() {
        assertEquals(ErrorCode.RANDOMNESS_FORMAT,
                assertThrows(DiceException.class, () -> DiceRoller.roll("bad", "", -1, "0d0")).code());
        String r = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3";
        assertEquals(ErrorCode.SEED_EMPTY,
                assertThrows(DiceException.class, () -> DiceRoller.roll(r, "", -1, "0d0")).code());
        assertEquals(ErrorCode.NONCE_OUT_OF_RANGE,
                assertThrows(DiceException.class, () -> DiceRoller.roll(r, "s", -1, "0d0")).code());
        assertEquals(ErrorCode.DICE_COUNT_OUT_OF_RANGE,
                assertThrows(DiceException.class, () -> DiceRoller.roll(r, "s", 0, "0d0")).code());
    }

    private static void assertSeedCode(ErrorCode code, String seed) {
        assertEquals(code, assertThrows(DiceException.class, () -> ClientSeed.of(seed)).code());
    }
}
