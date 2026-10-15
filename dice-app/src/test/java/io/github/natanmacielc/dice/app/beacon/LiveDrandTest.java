package io.github.natanmacielc.dice.app.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Bate nos endpoints reais do drand. Fora do CI e do build padrão:
 * ./mvnw -pl dice-app -am test -Plive
 */
@QuarkusTest
@Tag("live")
class LiveDrandTest {

    @Inject
    BeaconClient client;

    @Test
    void latestAndHistoricalRoundFromRealNetwork() {
        Beacon latest = client.latest();
        long expected = DrandQuicknet.roundAt(Instant.now());
        System.out.printf("live latest=%d esperado=%d randomness=%s%n", latest.round(), expected, latest.randomness());
        assertTrue(Math.abs(latest.round() - expected) <= 1);

        Beacon historical = client.round(1_000_000).orElseThrow();
        assertEquals("b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3", historical.randomness());

        assertTrue(client.round(latest.round() + 100).isEmpty());
    }
}
