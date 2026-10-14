package io.github.natanmacielc.dice.app.beacon;

/** Nenhum endpoint do drand devolveu uma resposta válida. */
public class BeaconUnavailableException extends RuntimeException {

    public BeaconUnavailableException(String message) {
        super(message);
    }
}
