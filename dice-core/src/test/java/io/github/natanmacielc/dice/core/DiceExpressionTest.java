package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DiceExpressionTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "d20;        1; 20;    0; 1d20",
        "1D20;       1; 20;    0; 1d20",
        "'  2d6 + 3 ';2; 6;    3; 2d6+3",
        "2d6-1;      2;  6;   -1; 2d6-1",
        "2d6+0;      2;  6;    0; 2d6",
        "2d6-0;      2;  6;    0; 2d6",
        "20d100+100; 20; 100; 100; 20d100+100",
        "1d4-100;    1;  4; -100; 1d4-100",
        "'\t3d8\t';  3;  8;    0; 3d8",
    })
    void parsesAndCanonicalizes(String text, int count, int sides, int modifier, String canonical) {
        DiceExpression e = DiceExpression.parse(text);
        assertEquals(count, e.count());
        assertEquals(sides, e.sides());
        assertEquals(modifier, e.modifier());
        assertEquals(canonical, e.canonical());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "d", "2d", "01d6", "1d06", "2d6+01", "2d6--1", "2d6+", "2 d6",
        "1d1000", "100d6", "2d6+1000", "１d6", "2d6\n", "2d6+3+1", "2x6"})
    void rejectsSyntax(String text) {
        assertCode(ErrorCode.INVALID_EXPRESSION, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0d6", "21d6", "99d20"})
    void rejectsDiceCount(String text) {
        assertCode(ErrorCode.DICE_COUNT_OUT_OF_RANGE, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1d0", "1d1", "1d7", "1d3", "2d16", "1d999"})
    void rejectsUnsupportedDie(String text) {
        assertCode(ErrorCode.UNSUPPORTED_DIE, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2d6+101", "2d6-101", "1d20+999"})
    void rejectsModifier(String text) {
        assertCode(ErrorCode.MODIFIER_OUT_OF_RANGE, text);
    }

    private static void assertCode(ErrorCode code, String text) {
        DiceException ex = assertThrows(DiceException.class, () -> DiceExpression.parse(text));
        assertEquals(code, ex.code());
    }
}
