package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Qui-quadrado com 1.000.000 de amostras por dado. As entradas são fixas (randomness real do round
 * 1000000 do quicknet, nonce de 0 a 49.999, 20 dados por roll), então o teste é determinístico.
 * Limites críticos para p = 0,001.
 */
class UniformityTest {

    private static final String R = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3";
    private static final int ROLLS = 50_000;
    private static final int DICE_PER_ROLL = 20;

    @ParameterizedTest(name = "d{0}")
    @CsvSource({"6, 20.515", "20, 43.820", "100, 148.230"})
    void chiSquareBelowCriticalValue(int sides, double critical) {
        ClientSeed seed = ClientSeed.of("uniformity-d" + sides);
        Randomness randomness = Randomness.of(R);
        DiceExpression expr = DiceExpression.parse(DICE_PER_ROLL + "d" + sides);
        long[] counts = new long[sides + 1];
        long samples = 0;
        for (int nonce = 0; nonce < ROLLS; nonce++) {
            for (int face : DiceRoller.roll(randomness, seed, nonce, expr).dice()) {
                counts[face]++;
                samples++;
            }
        }
        assertEquals(1_000_000, samples);
        double expected = (double) samples / sides;
        double chi2 = 0;
        for (int face = 1; face <= sides; face++) {
            double d = counts[face] - expected;
            chi2 += d * d / expected;
        }
        System.out.printf("d%d: chi2 = %.3f (df = %d, crítico p=0,001 = %.3f)%n", sides, chi2, sides - 1, critical);
        assertTrue(chi2 < critical, "chi2 = " + chi2);
    }
}
