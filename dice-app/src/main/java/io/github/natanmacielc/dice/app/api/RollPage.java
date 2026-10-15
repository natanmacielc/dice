package io.github.natanmacielc.dice.app.api;

/**
 * Dados da página /r/{rollId} (templates/r.html). Tudo é escapado pelo Qute. Os campos player* ficam
 * nulos nos rolls da chain-v1.
 */
public record RollPage(String rollId, String sessionId, String status, String statusLabel, String title,
                       String description, String url, String image, String expression, String totalText,
                       String diceText, String clientSeed, long nonce, long targetRound, String requestedAt,
                       String playerName, String playerFingerprint, String playerIdentityKey,
                       String playerDeviceKey) {
}
