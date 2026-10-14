package io.github.natanmacielc.dice.core;

/** Resultado de uma verificação: válido, ou inválido com o motivo. */
public record VerificationResult(boolean valid, String reason) {

    static VerificationResult ok() {
        return new VerificationResult(true, null);
    }

    static VerificationResult fail(String reason) {
        return new VerificationResult(false, reason);
    }
}
