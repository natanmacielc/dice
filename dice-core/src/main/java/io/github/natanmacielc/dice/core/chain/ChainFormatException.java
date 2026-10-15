package io.github.natanmacielc.dice.core.chain;

/** Falha de verificação com código; uso interno dos verificadores. */
final class ChainFormatException extends RuntimeException {

    private final ChainErrorCode code;

    ChainFormatException(ChainErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    ChainErrorCode code() {
        return code;
    }
}
