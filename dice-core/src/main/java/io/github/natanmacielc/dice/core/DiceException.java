package io.github.natanmacielc.dice.core;

/** Entrada inválida. O {@link ErrorCode} é parte do contrato da v1. */
public final class DiceException extends IllegalArgumentException {

    private final ErrorCode code;

    public DiceException(ErrorCode code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
