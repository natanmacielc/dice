package io.github.natanmacielc.dice.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Seed escolhida pelo jogador antes do roll. Os bytes UTF-8 são usados exatamente como recebidos,
 * sem normalização Unicode.
 */
public final class ClientSeed {

    public static final int MAX_BYTES = 64;

    private final String value;
    private final byte[] utf8;

    private ClientSeed(String value, byte[] utf8) {
        this.value = value;
        this.utf8 = utf8;
    }

    /** Valida na ordem: UTF-8 bem formado, vazio, tamanho em bytes, caracteres de controle. */
    public static ClientSeed of(String value) {
        Objects.requireNonNull(value, "value");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new DiceException(ErrorCode.SEED_INVALID_UTF8, "surrogate sem par na posição " + i);
            }
        }
        if (value.isEmpty()) {
            throw new DiceException(ErrorCode.SEED_EMPTY, "clientSeed vazia");
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_BYTES) {
            throw new DiceException(ErrorCode.SEED_TOO_LONG, utf8.length + " bytes (máximo " + MAX_BYTES + ")");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new DiceException(ErrorCode.SEED_CONTROL_CHAR, "caractere de controle na posição " + i);
            }
        }
        return new ClientSeed(value, utf8);
    }

    public String value() {
        return value;
    }

    public byte[] utf8() {
        return utf8.clone();
    }

    byte[] utf8Unsafe() {
        return utf8;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClientSeed other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "ClientSeed[" + value + "]";
    }
}
