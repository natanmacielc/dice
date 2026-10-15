package io.github.natanmacielc.dice.app.tables;

/** Mesa na lista de um jogador. {@code name} é a cópia do nome da mesa (pode ser nulo). */
public record TableRef(String sessionId, String name, String joinedAt, String lastActiveAt) {
}
