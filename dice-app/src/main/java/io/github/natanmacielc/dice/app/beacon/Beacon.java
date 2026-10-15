package io.github.natanmacielc.dice.app.beacon;

/** Um round do drand já validado (randomness = SHA-256(signature)). */
public record Beacon(long round, String randomness, String signature) {
}
