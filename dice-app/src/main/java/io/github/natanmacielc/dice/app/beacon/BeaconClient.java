package io.github.natanmacielc.dice.app.beacon;

import java.util.Optional;

public interface BeaconClient {

    /** Round mais recente, já validado pelo guarda de relógio. Usado só para fixar o round alvo. */
    Beacon latest();

    /** Exatamente o round pedido; vazio se ele ainda não foi publicado. */
    Optional<Beacon> round(long round);
}
