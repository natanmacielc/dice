package io.github.natanmacielc.dice.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * Stacks do ambiente dev. Deploy só pelo GitHub Actions (.github/workflows/deploy-dev.yml);
 * a DiceGithubOidcStack é aplicada uma vez, à mão, no bootstrap (infra/README.md).
 */
public final class DiceApp {

    private DiceApp() {
    }

    public static void main(String[] args) {
        App app = new App();
        build(app);
        app.synth();
    }

    record Stacks(DiceGithubOidcStack oidc, DiceCertStack cert, DiceDevStack dev) {
    }

    static Stacks build(App app) {
        DiceConfig cfg = DiceConfig.from(app.getNode());
        DiceGithubOidcStack oidc = new DiceGithubOidcStack(app, "DiceGithubOidcStack",
                StackProps.builder().env(env(cfg, DiceConfig.REGION)).build(), cfg);
        if (cfg.hostedZoneId().isEmpty()) {
            // Sem a zona só dá para sintetizar a stack do OIDC (bootstrap).
            System.err.println("hostedZoneId ausente: só a DiceGithubOidcStack foi sintetizada");
            return new Stacks(oidc, null, null);
        }
        DiceCertStack cert = new DiceCertStack(app, "DiceCertStack",
                StackProps.builder().env(env(cfg, DiceConfig.CERT_REGION)).crossRegionReferences(true).build(), cfg);
        DiceDevStack dev = new DiceDevStack(app, "DiceDevStack",
                StackProps.builder().env(env(cfg, DiceConfig.REGION)).crossRegionReferences(true).build(), cfg,
                cert.certificate());
        return new Stacks(oidc, cert, dev);
    }

    private static Environment env(DiceConfig cfg, String region) {
        return Environment.builder().account(cfg.account()).region(region).build();
    }
}
