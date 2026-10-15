package io.github.natanmacielc.dice.infra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Annotations;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.cloudfront.AllowedMethods;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.IOrigin;
import software.amazon.awscdk.services.cloudfront.CachePolicy;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.HeadersFrameOption;
import software.amazon.awscdk.services.cloudfront.HeadersReferrerPolicy;
import software.amazon.awscdk.services.cloudfront.HttpVersion;
import software.amazon.awscdk.services.cloudfront.OriginRequestPolicy;
import software.amazon.awscdk.services.cloudfront.PriceClass;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersContentSecurityPolicy;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersContentTypeOptions;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersFrameOptions;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersPolicy;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersReferrerPolicy;
import software.amazon.awscdk.services.cloudfront.ResponseHeadersStrictTransportSecurity;
import software.amazon.awscdk.services.cloudfront.ResponseSecurityHeadersBehavior;
import software.amazon.awscdk.services.cloudfront.SecurityPolicyProtocol;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.FunctionUrlOrigin;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.IMetric;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.Permission;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.SnapStartConf;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.route53.ARecord;
import software.amazon.awscdk.services.route53.AaaaRecord;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.targets.CloudFrontTarget;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.sns.subscriptions.LambdaSubscription;
import software.constructs.Construct;

/**
 * Ambiente dev em sa-east-1: CloudFront (com o WAF da DiceCertStack), Lambda (SnapStart) por Function URL
 * com OAC, DynamoDB provisionado e S3. Tudo dentro das cotas gratuitas; o kill switch desliga o app
 * quando o uso foge muito do esperado.
 */
public class DiceDevStack extends Stack {

    /** Mesma CSP do app (application.properties); o CloudFront garante o header em todas as respostas. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self' https://api.drand.sh https://api2.drand.sh https://api3.drand.sh "
            + "https://drand.cloudflare.com; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    /** Ações DynamoDB que o app usa (TransactWriteItems é autorizado pelas ações de cada item). */
    static final List<String> TABLE_ACTIONS = List.of("dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem",
            "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:ConditionCheckItem");

    /** Arquivos de web/ que não vão para o site (testes e documentação). */
    static final List<String> WEB_EXCLUDES = List.of("**/*.test.mjs", "**/README.md");

    /** Desliga o app: concorrência reservada 0. Religar é manual (infra/README.md, "Derrubar e religar"). */
    static final String KILL_SWITCH_CODE = """
            import os
            import boto3


            def handler(event, context):
                boto3.client("lambda").put_function_concurrency(
                    FunctionName=os.environ["APP_FUNCTION"], ReservedConcurrentExecutions=0)
                print("app desligado: concorrência reservada = 0")
            """;

    private final Distribution distribution;

    public DiceDevStack(Construct scope, String id, StackProps props, DiceConfig cfg, ICertificate certificate,
                        String webAclArn) {
        super(scope, id, props);

        // ---------- Dados ----------
        Table table = Table.Builder.create(this, "Ledger")
                .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
                // Provisionado sem autoscaling: dentro da cota gratuita e com teto. Estourou, o DynamoDB recusa
                // (o app responde 503 SERVER_BUSY) em vez de cobrar.
                .billingMode(BillingMode.PROVISIONED)
                .readCapacity(cfg.tableReadCapacity())
                .writeCapacity(cfg.tableWriteCapacity())
                // Só os pareamentos de aparelhos (PAIRING#) têm expiresAt; a cadeia nunca expira.
                .timeToLiveAttribute("expiresAt")
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true).build())
                .deletionProtection(true)
                .removalPolicy(RemovalPolicy.RETAIN) // o livro-razão nunca é apagado por deploy
                .build();

        Bucket web = privateBucket("WebBucket");
        Bucket og = privateBucket("OgBucket");

        // ---------- Lambda ----------
        LogGroup logs = LogGroup.Builder.create(this, "FunctionLogs")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DICE_DYNAMODB_TABLE", table.getTableName());
        env.put("DICE_S3_BUCKET", og.getBucketName());
        env.put("DICE_PUBLIC_BASE_URL", cfg.publicBaseUrl());
        env.put("JAVA_TOOL_OPTIONS", "-XX:+TieredCompilation -XX:TieredStopAtLevel=1");
        Function.Builder fnBuilder = Function.Builder.create(this, "App")
                .runtime(Runtime.JAVA_21)
                .architecture(Architecture.X86_64) // a renderização das imagens OG foi validada na imagem x86_64
                .handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
                .code(Code.fromAsset(cfg.functionZip()))
                .memorySize(1024)
                .timeout(Duration.seconds(25)) // failover do drand no pior caso ~20 s; HTTP API corta em 30 s
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .environment(env)
                .logGroup(logs);
        cfg.reservedConcurrency().ifPresent(fnBuilder::reservedConcurrentExecutions);
        Function fn = fnBuilder.build();
        Alias live = Alias.Builder.create(this, "Live").aliasName("live").version(fn.getCurrentVersion()).build();
        Annotations.of(fn).acknowledgeWarning("@aws-cdk/aws-lambda:snapStartRequirePublish",
                "o alias live aponta sempre para uma versão publicada (currentVersion)");

        fn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(TABLE_ACTIONS)
                .resources(List.of(table.getTableArn()))
                .build());
        fn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("s3:PutObject"))
                .resources(List.of(og.arnForObjects("og/*")))
                .build());

        // ---------- Origem fechada: só o CloudFront (OAC) chama a Function URL ----------
        FunctionUrl url = live.addFunctionUrl(FunctionUrlOptions.builder().authType(FunctionUrlAuthType.AWS_IAM).build());

        killSwitch(cfg, fn, table);

        // ---------- CloudFront ----------
        ResponseHeadersPolicy headers = ResponseHeadersPolicy.Builder.create(this, "SecurityHeaders")
                .securityHeadersBehavior(ResponseSecurityHeadersBehavior.builder()
                        .contentSecurityPolicy(ResponseHeadersContentSecurityPolicy.builder()
                                .contentSecurityPolicy(CSP).override(true).build())
                        .strictTransportSecurity(ResponseHeadersStrictTransportSecurity.builder()
                                .accessControlMaxAge(Duration.days(365)).includeSubdomains(false).override(true).build())
                        .contentTypeOptions(ResponseHeadersContentTypeOptions.builder().override(true).build())
                        .frameOptions(ResponseHeadersFrameOptions.builder()
                                .frameOption(HeadersFrameOption.DENY).override(true).build())
                        .referrerPolicy(ResponseHeadersReferrerPolicy.builder()
                                .referrerPolicy(HeadersReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN).override(true).build())
                        .build())
                .build();

        IOrigin appOrigin = FunctionUrlOrigin.withOriginAccessControl(url);

        // Ordem fixa (LinkedHashMap): Map.of muda a ordem entre execuções e geraria diffs à toa no template.
        // O plano Free do CloudFront aceita até 5 behaviors: default (site), /og/*, /r/* e /api/*.
        Map<String, BehaviorOptions> behaviors = new LinkedHashMap<>();
        behaviors.put("/og/*", BehaviorOptions.builder()
                .origin(S3BucketOrigin.withOriginAccessControl(og))
                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                .cachePolicy(CachePolicy.CACHING_OPTIMIZED)
                .responseHeadersPolicy(headers)
                .build());
        behaviors.put("/r/*", appBehavior(appOrigin, headers, AllowedMethods.ALLOW_GET_HEAD));
        behaviors.put("/api/*", appBehavior(appOrigin, headers, AllowedMethods.ALLOW_ALL));

        this.distribution = Distribution.Builder.create(this, "Cdn")
                .domainNames(List.of(cfg.domainName()))
                .webAclId(webAclArn)
                .certificate(certificate)
                .minimumProtocolVersion(SecurityPolicyProtocol.TLS_V1_2_2021)
                .httpVersion(HttpVersion.HTTP2_AND_3)
                .priceClass(PriceClass.PRICE_CLASS_ALL) // inclui os edges da América do Sul
                .defaultRootObject("index.html")
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(S3BucketOrigin.withOriginAccessControl(web))
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .cachePolicy(CachePolicy.CACHING_OPTIMIZED)
                        .responseHeadersPolicy(headers)
                        .build())
                .additionalBehaviors(behaviors)
                .build();

        // O CDK concede lambda:InvokeFunctionUrl ao CloudFront; Function URLs novas (desde out/2025) exigem
        // também lambda:InvokeFunction, restrito a chamadas que chegam pela URL.
        live.addPermission("CloudFrontInvokeViaUrl", Permission.builder()
                .principal(new ServicePrincipal("cloudfront.amazonaws.com"))
                .action("lambda:InvokeFunction")
                .invokedViaFunctionUrl(true)
                .sourceArn(distribution.getDistributionArn())
                .build());

        IHostedZone zone = DiceCertStack.zone(this, cfg);
        ARecord.Builder.create(this, "AliasA").zone(zone).recordName(cfg.recordName())
                .target(RecordTarget.fromAlias(new CloudFrontTarget(distribution))).build();
        AaaaRecord.Builder.create(this, "AliasAaaa").zone(zone).recordName(cfg.recordName())
                .target(RecordTarget.fromAlias(new CloudFrontTarget(distribution))).build();

        BucketDeployment.Builder.create(this, "WebDeployment")
                .sources(List.of(Source.asset(cfg.webDir(), AssetOptions.builder()
                        .exclude(WEB_EXCLUDES)
                        .build())))
                .destinationBucket(web)
                .distribution(distribution)
                .distributionPaths(List.of("/*"))
                .build();

        CfnOutput.Builder.create(this, "Url").value(cfg.publicBaseUrl()).build();
        // A URL direta da função responde 403 sem a assinatura do CloudFront (o smoke confere).
        CfnOutput.Builder.create(this, "FunctionUrl").value(url.getUrl()).build();
    }

    public String distributionArn() {
        return distribution.getDistributionArn();
    }

    /**
     * Uso muito acima do esperado numa hora (invocações ou tempo de execução) desliga o app: o pior caso
     * vira site fora do ar, não conta alta. Throttle do DynamoDB só avisa (o teto dele já é rígido).
     */
    private void killSwitch(DiceConfig cfg, Function app, Table table) {
        Topic kill = Topic.Builder.create(this, "KillTopic").displayName("Dado: app desligado").build();
        Topic notice = Topic.Builder.create(this, "NoticeTopic").displayName("Dado: aviso").build();
        cfg.alertEmail().ifPresent(email -> {
            kill.addSubscription(new EmailSubscription(email));
            notice.addSubscription(new EmailSubscription(email));
        });

        LogGroup logs = LogGroup.Builder.create(this, "KillSwitchLogs")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        Function killer = Function.Builder.create(this, "KillSwitch")
                .runtime(Runtime.PYTHON_3_13)
                .handler("index.handler")
                .code(Code.fromInline(KILL_SWITCH_CODE))
                .timeout(Duration.seconds(30))
                .environment(Map.of("APP_FUNCTION", app.getFunctionName()))
                .logGroup(logs)
                .build();
        killer.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("lambda:PutFunctionConcurrency"))
                .resources(List.of(app.getFunctionArn()))
                .build());
        kill.addSubscription(new LambdaSubscription(killer));

        MetricOptions hourlySum = MetricOptions.builder().period(Duration.hours(1)).statistic("Sum").build();
        alarm("InvocationsAlarm", app.metricInvocations(hourlySum), cfg.killSwitchInvocationsPerHour(), kill);
        alarm("DurationAlarm", app.metricDuration(hourlySum), cfg.killSwitchDurationMsPerHour(), kill);
        MetricOptions fiveMinSum = MetricOptions.builder().period(Duration.minutes(5)).statistic("Sum").build();
        alarm("ReadThrottleAlarm", table.metric("ReadThrottleEvents", fiveMinSum), 0, notice);
        alarm("WriteThrottleAlarm", table.metric("WriteThrottleEvents", fiveMinSum), 0, notice);
    }

    private void alarm(String id, IMetric metric, int threshold, Topic topic) {
        Alarm alarm = Alarm.Builder.create(this, id)
                .metric(metric)
                .threshold(threshold)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        alarm.addAlarmAction(new SnsAction(topic));
    }

    private Bucket privateBucket(String id) {
        return Bucket.Builder.create(this, id)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
    }

    private static BehaviorOptions appBehavior(IOrigin origin, ResponseHeadersPolicy headers, AllowedMethods methods) {
        return BehaviorOptions.builder()
                .origin(origin)
                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                .allowedMethods(methods)
                .cachePolicy(CachePolicy.CACHING_DISABLED)
                .originRequestPolicy(OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER)
                .responseHeadersPolicy(headers)
                .build();
    }
}
