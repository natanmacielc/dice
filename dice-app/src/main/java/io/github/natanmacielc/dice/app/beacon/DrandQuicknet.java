package io.github.natanmacielc.dice.app.beacon;

import io.github.natanmacielc.dice.core.Quicknet;
import java.time.Duration;
import java.time.Instant;

/** Parâmetros da rede drand quicknet; a fonte da verdade é {@link Quicknet} no dice-core. */
public final class DrandQuicknet {

    public static final String CHAIN_HASH = Quicknet.CHAIN_HASH;
    public static final long GENESIS_TIME = Quicknet.GENESIS_TIME;
    public static final Duration PERIOD = Duration.ofSeconds(Quicknet.PERIOD_SECONDS);

    private DrandQuicknet() {
    }

    public static long roundAt(Instant t) {
        return Quicknet.roundAt(t);
    }

    public static Instant timeOf(long round) {
        return Quicknet.timeOf(round);
    }
}
