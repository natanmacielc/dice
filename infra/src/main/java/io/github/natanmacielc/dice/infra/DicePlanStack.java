package io.github.natanmacielc.dice.infra;

import java.util.List;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.pricingplanmanager.CfnSubscription;
import software.constructs.Construct;

/**
 * Plano flat-rate Free do CloudFront (us-east-1, depois da distribuição): CDN, WAF e DDoS por US$ 0,
 * sem cobrança de excedente. O plano Free é ativado na hora; os pagos ficariam esperando aprovação manual.
 */
public class DicePlanStack extends Stack {

    public DicePlanStack(Construct scope, String id, StackProps props, String distributionArn, String webAclArn) {
        super(scope, id, props);
        CfnSubscription.Builder.create(this, "CloudFrontFreePlan")
                .planFamily("CloudFront")
                .planTier("FREE")
                .usageLevel("DEFAULT")
                .resourceArns(List.of(distributionArn, webAclArn))
                .build();
    }
}
