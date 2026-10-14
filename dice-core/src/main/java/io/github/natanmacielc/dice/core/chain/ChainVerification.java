package io.github.natanmacielc.dice.core.chain;

/** Resultado de verificar um segmento do log ou uma prova. */
public record ChainVerification(boolean valid, ChainErrorCode code, String message) {

    static ChainVerification ok() {
        return new ChainVerification(true, null, null);
    }

    static ChainVerification fail(ChainErrorCode code, String message) {
        return new ChainVerification(false, code, message);
    }
}
