package io.github.natanmacielc.dice.core;

/** Nonce sequencial por mesa: inteiro de 0 a 2^53 - 1, para caber exato num Number do JavaScript. */
public final class Nonce {

    public static final long MAX = (1L << 53) - 1;

    private Nonce() {
    }

    public static long check(long nonce) {
        if (nonce < 0 || nonce > MAX) {
            throw new DiceException(ErrorCode.NONCE_OUT_OF_RANGE, "nonce fora de [0, 2^53 - 1]: " + nonce);
        }
        return nonce;
    }
}
