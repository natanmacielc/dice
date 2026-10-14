package io.github.natanmacielc.dice.app.ledger;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

/** Troca o cliente drand real pelo FakeBeaconClient. */
public class LedgerProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(FakeBeaconClient.class);
    }
}
