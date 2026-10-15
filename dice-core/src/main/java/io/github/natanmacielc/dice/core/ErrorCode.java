package io.github.natanmacielc.dice.core;

/** Códigos de erro da v1. Os verificadores JS e Python usam os mesmos nomes (ver spec/ALGORITHM.md). */
public enum ErrorCode {
    RANDOMNESS_FORMAT,
    SIGNATURE_FORMAT,
    SEED_INVALID_UTF8,
    SEED_EMPTY,
    SEED_TOO_LONG,
    SEED_CONTROL_CHAR,
    NONCE_OUT_OF_RANGE,
    INVALID_EXPRESSION,
    DICE_COUNT_OUT_OF_RANGE,
    UNSUPPORTED_DIE,
    MODIFIER_OUT_OF_RANGE
}
