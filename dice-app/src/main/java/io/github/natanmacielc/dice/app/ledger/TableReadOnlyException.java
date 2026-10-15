package io.github.natanmacielc.dice.app.ledger;

/** Mesa chain-v1: continua legível e verificável, mas não aceita rolls novos. */
public class TableReadOnlyException extends RuntimeException {

    public TableReadOnlyException(String message) {
        super(message);
    }
}
