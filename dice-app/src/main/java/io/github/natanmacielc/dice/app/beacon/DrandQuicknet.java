package io.github.natanmacielc.dice.app.beacon;

import java.time.Duration;
import java.time.Instant;

/** Parâmetros fixos da rede drand quicknet. Mudar qualquer um deles é mudar o algoritmo. */
public final class DrandQuicknet {

    public static final String CHAIN_HASH = "52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971";
    public static final long GENESIS_TIME = 1692803367L;
    public static final Duration PERIOD = Duration.ofSeconds(3);

    private DrandQuicknet() {
    }

    /** Round publicado mais recente segundo o relógio: floor((t - genesis) / período) + 1. */
    public static long roundAt(Instant t) {
        long elapsed = t.getEpochSecond() - GENESIS_TIME;
        if (elapsed < 0) {
            return 0;
        }
        return elapsed / PERIOD.getSeconds() + 1;
    }

    /** Instante em que o round é publicado: genesis + (round - 1) * período. */
    public static Instant timeOf(long round) {
        if (round < 1) {
            throw new IllegalArgumentException("round deve ser >= 1: " + round);
        }
        return Instant.ofEpochSecond(GENESIS_TIME + (round - 1) * PERIOD.getSeconds());
    }
}
