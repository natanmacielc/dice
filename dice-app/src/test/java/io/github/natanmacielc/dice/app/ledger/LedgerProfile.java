package io.github.natanmacielc.dice.app.ledger;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

/** Troca o cliente drand real pelo FakeBeaconClient e o S3 pelo FlakyImageStore (que delega ao s3mock). */
public class LedgerProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(FakeBeaconClient.class, FlakyImageStore.class);
    }
}
