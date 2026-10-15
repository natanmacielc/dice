package io.github.natanmacielc.dice.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneAttributes;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.wafv2.CfnWebACL;
import software.constructs.Construct;

/**
 * O que a borda precisa em us-east-1, antes da distribuição: certificado (validado por DNS), WAF do
 * CloudFront e o orçamento mensal (aviso por e-mail).
 */
public class DiceCertStack extends Stack {

    /**
     * Limite por IP em 5 minutos, em qualquer caminho (páginas, scripts, API). Não há limite só para /api/*:
     * filtrar por caminho exige byte match, que o plano Free não aceita. O custo da API fica com o kill switch.
     */
    static final int RATE_LIMIT_ALL = 1000;
    static final int RATE_WINDOW_SECONDS = 300;
    /** Orçamento mensal em dólares; o esperado é zero. */
    static final int BUDGET_USD = 1;

    private final Certificate certificate;
    private final CfnWebACL webAcl;

    public DiceCertStack(Construct scope, String id, StackProps props, DiceConfig cfg) {
        super(scope, id, props);
        this.certificate = Certificate.Builder.create(this, "Certificate")
                .domainName(cfg.domainName())
                .validation(CertificateValidation.fromDns(zone(this, cfg)))
                .build();
        this.webAcl = webAcl();
        cfg.alertEmail().ifPresent(this::budget);
    }

    public ICertificate certificate() {
        return certificate;
    }

    public String webAclArn() {
        return webAcl.getAttrArn();
    }

    /** Zona importada por ID (sem lookup na conta durante o synth). */
    static IHostedZone zone(Construct scope, DiceConfig cfg) {
        return HostedZone.fromHostedZoneAttributes(scope, "Zone", HostedZoneAttributes.builder()
                .hostedZoneId(cfg.hostedZoneId().orElseThrow())
                .zoneName(cfg.zoneName())
                .build());
    }

    /**
     * Duas das cinco regras do plano Free do CloudFront. Pedido bloqueado não conta na franquia do plano;
     * o bloqueio responde 403 sem JSON (a web mostra "espere alguns minutos").
     */
    private CfnWebACL webAcl() {
        List<CfnWebACL.RuleProperty> rules = new ArrayList<>();
        rules.add(rateRule("rate-per-ip", 0, RATE_LIMIT_ALL));
        rules.add(CfnWebACL.RuleProperty.builder()
                .name("ip-reputation")
                .priority(1)
                .statement(CfnWebACL.StatementProperty.builder()
                        .managedRuleGroupStatement(CfnWebACL.ManagedRuleGroupStatementProperty.builder()
                                .vendorName("AWS").name("AWSManagedRulesAmazonIpReputationList").build())
                        .build())
                .overrideAction(CfnWebACL.OverrideActionProperty.builder().none(Map.of()).build())
                .visibilityConfig(visibility("ip-reputation"))
                .build());
        return CfnWebACL.Builder.create(this, "WebAcl")
                .name("dice-web")
                .scope("CLOUDFRONT")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build()).build())
                .visibilityConfig(visibility("dice-web"))
                .rules(rules)
                .build();
    }

    private static CfnWebACL.RuleProperty rateRule(String name, int priority, int limit) {
        CfnWebACL.RateBasedStatementProperty rate = CfnWebACL.RateBasedStatementProperty.builder()
                .limit(limit)
                .aggregateKeyType("IP")
                .evaluationWindowSec(RATE_WINDOW_SECONDS)
                .build();
        return CfnWebACL.RuleProperty.builder()
                .name(name)
                .priority(priority)
                .statement(CfnWebACL.StatementProperty.builder().rateBasedStatement(rate).build())
                // Bloqueio padrão (403): resposta customizada (429) não existe no plano Free e o impede.
                .action(CfnWebACL.RuleActionProperty.builder()
                        .block(CfnWebACL.BlockActionProperty.builder().build())
                        .build())
                .visibilityConfig(visibility(name))
                .build();
    }

    private static CfnWebACL.VisibilityConfigProperty visibility(String metric) {
        return CfnWebACL.VisibilityConfigProperty.builder()
                .cloudWatchMetricsEnabled(true)
                .sampledRequestsEnabled(true)
                .metricName(metric)
                .build();
    }

    /** Aviso por e-mail no gasto real e no previsto acima do orçamento (os dois primeiros orçamentos são grátis). */
    private void budget(String email) {
        List<CfnBudget.NotificationWithSubscribersProperty> notifications = new ArrayList<>();
        for (String type : List.of("ACTUAL", "FORECASTED")) {
            notifications.add(CfnBudget.NotificationWithSubscribersProperty.builder()
                    .notification(CfnBudget.NotificationProperty.builder()
                            .notificationType(type)
                            .comparisonOperator("GREATER_THAN")
                            .threshold(100)
                            .thresholdType("PERCENTAGE")
                            .build())
                    .subscribers(List.of(CfnBudget.SubscriberProperty.builder()
                            .subscriptionType("EMAIL").address(email).build()))
                    .build());
        }
        CfnBudget.Builder.create(this, "MonthlyBudget")
                .budget(CfnBudget.BudgetDataProperty.builder()
                        .budgetName("dice-mensal")
                        .budgetType("COST")
                        .timeUnit("MONTHLY")
                        .budgetLimit(CfnBudget.SpendProperty.builder().amount(BUDGET_USD).unit("USD").build())
                        .build())
                .notificationsWithSubscribers(notifications)
                .build();
    }
}
