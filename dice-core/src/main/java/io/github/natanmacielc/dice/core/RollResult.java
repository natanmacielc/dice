package io.github.natanmacielc.dice.core;

import java.util.List;

/**
 * Resultado de um roll. {@code expression} está na forma canônica; {@code wordsConsumed} conta
 * também as palavras rejeitadas.
 */
public record RollResult(String expression, List<Integer> dice, int modifier, int total,
                         long wordsConsumed, List<Rejection> rejections) {

    public RollResult {
        dice = List.copyOf(dice);
        rejections = List.copyOf(rejections);
    }
}
