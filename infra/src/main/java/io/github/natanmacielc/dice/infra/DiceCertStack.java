package io.github.natanmacielc.dice.infra;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneAttributes;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.constructs.Construct;

/** Certificado do CloudFront (precisa estar em us-east-1), validado por DNS no Route 53. */
public class DiceCertStack extends Stack {

    private final Certificate certificate;

    public DiceCertStack(Construct scope, String id, StackProps props, DiceConfig cfg) {
        super(scope, id, props);
        this.certificate = Certificate.Builder.create(this, "Certificate")
                .domainName(cfg.domainName())
                .validation(CertificateValidation.fromDns(zone(this, cfg)))
                .build();
    }

    public ICertificate certificate() {
        return certificate;
    }

    /** Zona importada por ID (sem lookup na conta durante o synth). */
    static IHostedZone zone(Construct scope, DiceConfig cfg) {
        return HostedZone.fromHostedZoneAttributes(scope, "Zone", HostedZoneAttributes.builder()
                .hostedZoneId(cfg.hostedZoneId().orElseThrow())
                .zoneName(cfg.zoneName())
                .build());
    }
}
