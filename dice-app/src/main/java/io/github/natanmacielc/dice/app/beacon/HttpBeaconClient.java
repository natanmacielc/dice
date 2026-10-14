package io.github.natanmacielc.dice.app.beacon;

import io.github.natanmacielc.dice.core.BeaconCheck;
import io.github.natanmacielc.dice.core.DiceException;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Cliente do drand quicknet com failover entre endpoints. Toda resposta passa por
 * randomness = SHA-256(signature) (invariante 6); o latest passa também pelo guarda de relógio.
 */
@ApplicationScoped
public class HttpBeaconClient implements BeaconClient {

    private static final Logger LOG = Logger.getLogger(HttpBeaconClient.class);

    /** O latest aceito fica a no máximo 1 round do esperado pelo relógio. */
    static final long LATEST_TOLERANCE_ROUNDS = 1;

    private final Clock clock;
    private final List<Endpoint> endpoints;

    public HttpBeaconClient(DrandConfig config, Clock clock) {
        this.clock = clock;
        this.endpoints = config.endpoints().stream()
                .map(uri -> new Endpoint(uri, QuarkusRestClientBuilder.newBuilder()
                        .baseUri(uri)
                        .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .build(DrandApi.class)))
                .toList();
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("drand.endpoints vazio");
        }
    }

    @Override
    public Beacon latest() {
        List<String> failures = new ArrayList<>();
        for (Endpoint e : endpoints) {
            try {
                Beacon b = validate(e.api().latest(DrandQuicknet.CHAIN_HASH));
                long expected = DrandQuicknet.roundAt(clock.instant());
                if (Math.abs(b.round() - expected) > LATEST_TOLERANCE_ROUNDS) {
                    throw new InvalidBeacon("latest " + b.round() + " longe do esperado pelo relógio " + expected);
                }
                return b;
            } catch (RuntimeException ex) {
                failures.add(fail(e, "latest", ex));
            }
        }
        throw new BeaconUnavailableException("latest indisponível: " + failures);
    }

    @Override
    public Optional<Beacon> round(long round) {
        if (round < 1) {
            throw new IllegalArgumentException("round deve ser >= 1: " + round);
        }
        if (clock.instant().isBefore(DrandQuicknet.timeOf(round))) {
            return Optional.empty();
        }
        List<String> failures = new ArrayList<>();
        boolean notYetPublished = false;
        for (Endpoint e : endpoints) {
            try {
                Beacon b = validate(e.api().round(DrandQuicknet.CHAIN_HASH, round));
                if (b.round() != round) {
                    throw new InvalidBeacon("pedido round " + round + ", veio " + b.round());
                }
                return Optional.of(b);
            } catch (WebApplicationException ex) {
                // drand.sh responde 425 (Too Early) e a Cloudflare 404 para round ainda não publicado.
                int status = ex.getResponse().getStatus();
                notYetPublished |= status == 404 || status == 425;
                failures.add(fail(e, "round " + round, ex));
            } catch (RuntimeException ex) {
                failures.add(fail(e, "round " + round, ex));
            }
        }
        if (notYetPublished) {
            return Optional.empty();
        }
        throw new BeaconUnavailableException("round " + round + " indisponível: " + failures);
    }

    private static Beacon validate(DrandBeaconResponse r) {
        if (r == null || r.round() == null || r.randomness() == null || r.signature() == null) {
            throw new InvalidBeacon("resposta incompleta");
        }
        if (r.round() < 1) {
            throw new InvalidBeacon("round inválido: " + r.round());
        }
        try {
            if (!BeaconCheck.randomnessMatchesSignature(r.signature(), r.randomness())) {
                throw new InvalidBeacon("randomness != SHA-256(signature) no round " + r.round());
            }
        } catch (DiceException ex) {
            throw new InvalidBeacon(ex.getMessage());
        }
        return new Beacon(r.round(), r.randomness(), r.signature());
    }

    private static String fail(Endpoint e, String what, RuntimeException ex) {
        String reason = e.uri() + ": " + describe(ex);
        LOG.warnf("drand %s falhou em %s", what, reason);
        return reason;
    }

    private static String describe(RuntimeException ex) {
        if (ex instanceof WebApplicationException w) {
            int status = w.getResponse().getStatus();
            // Corpo que não desserializa chega como WebApplicationException com status 2xx.
            return status / 100 == 2 ? "corpo inválido (HTTP " + status + "): " + ex.getMessage() : "HTTP " + status;
        }
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return ex.getClass().getSimpleName() + (root != ex ? " (" + root.getClass().getSimpleName() + ")" : "")
                + ": " + ex.getMessage();
    }

    private record Endpoint(URI uri, DrandApi api) {
    }

    static final class InvalidBeacon extends RuntimeException {
        InvalidBeacon(String message) {
            super(message);
        }
    }
}
