package io.github.natanmacielc.dice.app.ledger;

/** player ausente, mal formado (PLAYER_INVALID) ou com assinatura que não confere (PLAYER_SIGNATURE_INVALID). */
public class InvalidPlayerException extends RuntimeException {

    private final String code;

    public InvalidPlayerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
