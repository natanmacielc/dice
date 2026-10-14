package io.github.natanmacielc.dice.core;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expressão NdX+M já validada. {@link #canonical()} é a forma gravada em recibos e na cadeia. */
public record DiceExpression(int count, int sides, int modifier) {

    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 20;
    public static final int MAX_ABS_MODIFIER = 100;
    public static final Set<Integer> SIDES = Set.of(4, 6, 8, 10, 12, 20, 100);

    // Só ASCII. Espaços e tabs nas pontas e em volta do sinal. Sem zeros à esquerda.
    private static final Pattern SYNTAX = Pattern.compile(
            "[ \\t]*(0|[1-9][0-9]?)?[dD](0|[1-9][0-9]{0,2})(?:[ \\t]*([+-])[ \\t]*(0|[1-9][0-9]{0,2}))?[ \\t]*");

    public DiceExpression {
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new DiceException(ErrorCode.DICE_COUNT_OUT_OF_RANGE, "N deve estar entre 1 e 20: " + count);
        }
        if (!SIDES.contains(sides)) {
            throw new DiceException(ErrorCode.UNSUPPORTED_DIE, "dado não suportado: d" + sides);
        }
        if (Math.abs(modifier) > MAX_ABS_MODIFIER) {
            throw new DiceException(ErrorCode.MODIFIER_OUT_OF_RANGE, "|M| deve ser <= 100: " + modifier);
        }
    }

    public static DiceExpression parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = SYNTAX.matcher(text);
        if (!m.matches()) {
            throw new DiceException(ErrorCode.INVALID_EXPRESSION, "expressão inválida: " + text);
        }
        int count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = m.group(3) == null ? 0 : Integer.parseInt(m.group(4));
        if ("-".equals(m.group(3))) {
            modifier = -modifier;
        }
        return new DiceExpression(count, sides, modifier);
    }

    public String canonical() {
        String base = count + "d" + sides;
        if (modifier > 0) {
            return base + "+" + modifier;
        }
        if (modifier < 0) {
            return base + "-" + (-modifier);
        }
        return base;
    }
}
