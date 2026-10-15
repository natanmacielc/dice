package io.github.natanmacielc.dice.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** Campo randomness do drand: exatamente 64 caracteres hex minúsculos. */
public record Randomness(String hex) {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public Randomness {
        Objects.requireNonNull(hex, "hex");
        if (!HEX64.matcher(hex).matches()) {
            throw new DiceException(ErrorCode.RANDOMNESS_FORMAT, "esperado 64 caracteres hex minúsculos");
        }
    }

    public static Randomness of(String hex) {
        return new Randomness(hex);
    }
}
