package io.github.natanmacielc.dice.core;

import java.util.List;

/**
 * Recalcula um roll e compara com o resultado declarado. O formato do recibo e da prova ainda será
 * definido; por isso a entrada aqui são os campos soltos.
 */
public final class RollVerifier {

    private RollVerifier() {
    }

    public static VerificationResult verify(String randomnessHex, String clientSeed, long nonce, String expression,
                                            List<Integer> claimedDice, int claimedTotal) {
        RollResult actual;
        try {
            actual = DiceRoller.roll(randomnessHex, clientSeed, nonce, expression);
        } catch (DiceException e) {
            return VerificationResult.fail("entrada inválida: " + e.getMessage());
        }
        if (!actual.dice().equals(claimedDice)) {
            return VerificationResult.fail("dados declarados " + claimedDice + " != recalculados " + actual.dice());
        }
        if (actual.total() != claimedTotal) {
            return VerificationResult.fail("total declarado " + claimedTotal + " != recalculado " + actual.total());
        }
        return VerificationResult.ok();
    }

    /** Também confere randomness = SHA-256(signature) antes de recalcular. */
    public static VerificationResult verify(String signatureHex, String randomnessHex, String clientSeed, long nonce,
                                            String expression, List<Integer> claimedDice, int claimedTotal) {
        try {
            if (!BeaconCheck.randomnessMatchesSignature(signatureHex, randomnessHex)) {
                return VerificationResult.fail("randomness não é SHA-256(signature)");
            }
        } catch (DiceException e) {
            return VerificationResult.fail("beacon inválido: " + e.getMessage());
        }
        return verify(randomnessHex, clientSeed, nonce, expression, claimedDice, claimedTotal);
    }
}
