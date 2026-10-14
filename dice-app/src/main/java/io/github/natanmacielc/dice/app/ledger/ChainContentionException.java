package io.github.natanmacielc.dice.app.ledger;

/** A gravação não conseguiu a vez na cadeia da mesa; o cliente pode tentar de novo. */
public class ChainContentionException extends RuntimeException {

    public ChainContentionException(String message) {
        super(message);
    }
}
