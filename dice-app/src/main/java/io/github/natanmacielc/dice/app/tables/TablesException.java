package io.github.natanmacielc.dice.app.tables;

/** Erro da lista de mesas com status HTTP e código (AUTH_INVALID, AUTH_EXPIRED, INVALID_REQUEST, NOT_FOUND). */
public class TablesException extends RuntimeException {

    private final int status;
    private final String code;

    public TablesException(int status, String code, String message) {
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
