package io.github.natanmacielc.dice.app.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DrandQuicknetTest {

    @Test
    void roundOneIsPublishedAtGenesis() {
        assertEquals(0, DrandQuicknet.roundAt(Instant.ofEpochSecond(DrandQuicknet.GENESIS_TIME - 1)));
        assertEquals(1, DrandQuicknet.roundAt(Instant.ofEpochSecond(DrandQuicknet.GENESIS_TIME)));
        assertEquals(1, DrandQuicknet.roundAt(Instant.ofEpochSecond(DrandQuicknet.GENESIS_TIME + 2)));
        assertEquals(2, DrandQuicknet.roundAt(Instant.ofEpochSecond(DrandQuicknet.GENESIS_TIME + 3)));
        assertEquals(Instant.ofEpochSecond(DrandQuicknet.GENESIS_TIME), DrandQuicknet.timeOf(1));
    }

    @Test
    void roundAndTimeAreInverse() {
        for (long round : new long[] {1, 2, 1_000_000, 33_071_427}) {
            assertEquals(round, DrandQuicknet.roundAt(DrandQuicknet.timeOf(round)));
            assertEquals(round - 1, DrandQuicknet.roundAt(DrandQuicknet.timeOf(round).minusSeconds(1)));
        }
    }

    @Test
    void matchesObservedLatest() {
        // Em 2026-10-14, epoch 1792017647, drand.cloudflare.com devolveu latest = 33071427.
        assertEquals(33_071_427, DrandQuicknet.roundAt(Instant.ofEpochSecond(1_792_017_647L)));
    }

    @Test
    void timeOfRejectsRoundBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> DrandQuicknet.timeOf(0));
    }
}
