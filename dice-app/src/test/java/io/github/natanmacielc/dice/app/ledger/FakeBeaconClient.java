package io.github.natanmacielc.dice.app.ledger;

import io.github.natanmacielc.dice.app.beacon.Beacon;
import io.github.natanmacielc.dice.app.beacon.BeaconClient;
import io.github.natanmacielc.dice.app.beacon.BeaconUnavailableException;
import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.Quicknet;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Beacon falso guiado pelo MutableClock: um round existe quando o relógio passa do seu instante.
 * As assinaturas são sintéticas, mas randomness = SHA-256(signature) vale (invariante 6).
 * Singleton (sem proxy) para os testes lerem e mudarem os campos diretamente.
 */
@Alternative
@Singleton
public class FakeBeaconClient implements BeaconClient {

    private final MutableClock clock;
    volatile boolean down;
    final AtomicInteger latestCalls = new AtomicInteger();
    final List<Long> roundCalls = new CopyOnWriteArrayList<>();

    public FakeBeaconClient(MutableClock clock) {
        this.clock = clock;
    }

    void reset() {
        down = false;
        latestCalls.set(0);
        roundCalls.clear();
    }

    @Override
    public Beacon latest() {
        latestCalls.incrementAndGet();
        if (down) {
            throw new BeaconUnavailableException("fake fora do ar");
        }
        return beacon(Quicknet.roundAt(clock.instant()));
    }

    @Override
    public Optional<Beacon> round(long round) {
        roundCalls.add(round);
        if (down) {
            throw new BeaconUnavailableException("fake fora do ar");
        }
        if (clock.instant().isBefore(Quicknet.timeOf(round))) {
            return Optional.empty();
        }
        return Optional.of(beacon(round));
    }

    static Beacon beacon(long round) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(("sig-a|" + round).getBytes(StandardCharsets.UTF_8));
            byte[] b = sha.digest(("sig-b|" + round).getBytes(StandardCharsets.UTF_8));
            byte[] signature = new byte[48];
            System.arraycopy(a, 0, signature, 0, 32);
            System.arraycopy(b, 0, signature, 32, 16);
            String randomness = HexFormat.of().formatHex(sha.digest(Arrays.copyOf(signature, 48)));
            return new Beacon(round, randomness, HexFormat.of().formatHex(signature));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
