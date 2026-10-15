package io.github.natanmacielc.dice.core;

import java.util.ArrayList;
import java.util.List;

/** Algoritmo dice-v1. Ver spec/ALGORITHM.md. */
public final class DiceRoller {

    public static final String ALGORITHM = "dice-v1";

    private static final long TWO_32 = 1L << 32;

    private DiceRoller() {
    }

    /** Valida na ordem da spec (randomness, clientSeed, nonce, expressão) e rola. */
    public static RollResult roll(String randomnessHex, String clientSeed, long nonce, String expression) {
        Randomness randomness = Randomness.of(randomnessHex);
        ClientSeed seed = ClientSeed.of(clientSeed);
        Nonce.check(nonce);
        DiceExpression expr = DiceExpression.parse(expression);
        return roll(randomness, seed, nonce, expr);
    }

    public static RollResult roll(Randomness randomness, ClientSeed seed, long nonce, DiceExpression expression) {
        return roll(new Sha256WordStream(randomness, seed, nonce), expression);
    }

    static RollResult roll(WordSource words, DiceExpression expression) {
        long limit = limit(expression.sides());
        List<Integer> dice = new ArrayList<>(expression.count());
        List<Rejection> rejections = new ArrayList<>();
        long index = 0;
        int sum = 0;
        while (dice.size() < expression.count()) {
            long v = words.next();
            if (v >= limit) {
                rejections.add(new Rejection(index, v));
            } else {
                int face = (int) (v % expression.sides()) + 1;
                dice.add(face);
                sum += face;
            }
            index++;
        }
        return new RollResult(expression.canonical(), dice, expression.modifier(),
                sum + expression.modifier(), index, rejections);
    }

    /** limite = 2^32 - (2^32 mod lados): o maior múltiplo de lados que cabe em 32 bits. */
    static long limit(int sides) {
        return TWO_32 - (TWO_32 % sides);
    }
}
