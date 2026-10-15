package io.github.natanmacielc.dice.app.pairing;

/** Erro do pareamento com status HTTP e código ({@code PAIRING_*}). */
public class PairingException extends RuntimeException {

    private final int status;
    private final String code;

    public PairingException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
