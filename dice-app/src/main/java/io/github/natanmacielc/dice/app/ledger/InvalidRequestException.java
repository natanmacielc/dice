package io.github.natanmacielc.dice.app.ledger;

public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
