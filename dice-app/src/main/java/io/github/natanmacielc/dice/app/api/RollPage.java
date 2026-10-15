package io.github.natanmacielc.dice.app.api;

/** Dados da página /r/{rollId} (templates/r.html). Tudo é escapado pelo Qute. */
public record RollPage(String rollId, String sessionId, String status, String statusLabel, String title,
                       String description, String url, String image, String expression, String totalText,
                       String diceText, String clientSeed, long nonce, long targetRound, String requestedAt) {
}
