package io.github.natanmacielc.dice.app.ledger;

import io.github.natanmacielc.dice.app.beacon.Beacon;
import io.github.natanmacielc.dice.app.beacon.BeaconClient;
import io.github.natanmacielc.dice.app.beacon.BeaconUnavailableException;
import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.RollResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.util.Optional;
import org.jboss.logging.Logger;

/** Resolução sob demanda: chamada em toda leitura de um roll pendente. */
@ApplicationScoped
public class ResolutionService {

    private static final Logger LOG = Logger.getLogger(ResolutionService.class);

    private final BeaconClient beacon;
    private final LedgerRepository ledger;
    private final Clock clock;

    public ResolutionService(BeaconClient beacon, LedgerRepository ledger, Clock clock) {
        this.beacon = beacon;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Resolve se o round alvo já foi publicado; senão, ou com o drand fora, devolve o roll como está. */
    public RollRecord resolveIfReady(RollRecord roll) {
        if (roll.resolved() || clock.instant().isBefore(Quicknet.timeOf(roll.targetRound()))) {
            return roll;
        }
        Optional<Beacon> target;
        try {
            // Invariante 1: exatamente o round alvo, nunca o latest.
            target = beacon.round(roll.targetRound());
        } catch (BeaconUnavailableException e) {
            LOG.warnf("roll %s continua pendente: %s", roll.rollId(), e.getMessage());
            return roll;
        }
        if (target.isEmpty()) {
            return roll;
        }
        Beacon b = target.get();
        if (b.round() != roll.targetRound()) {
            throw new IllegalStateException("beacon devolveu round " + b.round() + " para o alvo " + roll.targetRound());
        }
        RollResult result = DiceRoller.roll(b.randomness(), roll.clientSeed(), roll.nonce(), roll.expression());
        return ledger.appendResolution(roll, b, result);
    }
}
