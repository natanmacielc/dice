package io.github.natanmacielc.dice.app.pairing;

import io.github.natanmacielc.dice.core.chain.Player;
import java.util.List;

/**
 * Pareamento em andamento (spec/ALGORITHM.md, seção 7.7). Só dados públicos: o segredo dos MACs fica no
 * fragmento do link, que nunca chega ao servidor. {@code device} e {@code identity} ficam nulos até cada etapa.
 */
public record PairingRecord(String pairingId, long expiresAt, Device device, Identity identity) {

    public record Device(String deviceKey, String mac) {
    }

    public record Identity(String identityKey, List<Player.Link> links, String name, String mac) {
    }
}
