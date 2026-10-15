package io.github.natanmacielc.dice.infra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Annotations;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegration;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.CfnStage;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpMethod;
import software.amazon.awscdk.services.apigatewayv2.HttpStage;
import software.amazon.awscdk.services.apigatewayv2.ThrottleSettings;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.cloudfront.AllowedMethods;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.CachePolicy;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.HeadersFrameOption;
import software.amazon.awscdk.services.cloudfront.HeadersReferrerPolicy;
import software.amazon.awscdk.services.cloudfront.HttpVersion;
import software.amazon.awscdk.services.cloudfront.OriginProtocolPolicy;
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
import software.amazon.awscdk.services.cloudfront.origins.HttpOrigin;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
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
import software.constructs.Construct;

/** Ambiente dev em sa-east-1: CloudFront, API Gateway HTTP API, Lambda (SnapStart), DynamoDB e S3. */
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

    static final String ROUTE_CREATE_SESSION = "POST /sessions";
    static final String ROUTE_CREATE_ROLL = "POST /sessions/{sessionId}/rolls";
    static final String ROUTE_CREATE_PAIRING = "POST /pairings";

    public DiceDevStack(Construct scope, String id, StackProps props, DiceConfig cfg, ICertificate certificate) {
        super(scope, id, props);

        // ---------- Dados ----------
        Table table = Table.Builder.create(this, "Ledger")
                .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
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

        // ---------- API (rotas explícitas, com throttling) ----------
        HttpApi api = HttpApi.Builder.create(this, "Api").createDefaultStage(false).build();
        HttpStage stage = HttpStage.Builder.create(this, "DefaultStage")
                .httpApi(api)
                .stageName("$default")
                .autoDeploy(true)
                .throttle(ThrottleSettings.builder().rateLimit(20).burstLimit(40).build())
                .build();
        CfnStage cfnStage = (CfnStage) stage.getNode().getDefaultChild();
        Map<String, Object> routeSettings = new LinkedHashMap<>();
        routeSettings.put(ROUTE_CREATE_SESSION, throttle(1, 5));
        routeSettings.put(ROUTE_CREATE_ROLL, throttle(5, 10));
        routeSettings.put(ROUTE_CREATE_PAIRING, throttle(1, 5));
        cfnStage.addPropertyOverride("RouteSettings", routeSettings);

        HttpLambdaIntegration integration = new HttpLambdaIntegration("AppIntegration", live);
        route(api, integration, "/sessions", HttpMethod.POST);
        route(api, integration, "/sessions/{sessionId}/rolls", HttpMethod.POST);
        route(api, integration, "/sessions/{sessionId}/log", HttpMethod.GET);
        route(api, integration, "/rolls/{rollId}", HttpMethod.GET);
        route(api, integration, "/rolls/{rollId}/proof", HttpMethod.GET);
        route(api, integration, "/r/{rollId}", HttpMethod.GET);
        route(api, integration, "/pairings", HttpMethod.POST);
        route(api, integration, "/pairings/{pairingId}", HttpMethod.GET);
        route(api, integration, "/pairings/{pairingId}/device", HttpMethod.POST);
        route(api, integration, "/pairings/{pairingId}/identity", HttpMethod.POST);

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

        String apiDomain = Fn.select(2, Fn.split("/", api.getApiEndpoint()));
        HttpOrigin apiOrigin = HttpOrigin.Builder.create(apiDomain).protocolPolicy(OriginProtocolPolicy.HTTPS_ONLY).build();
        BehaviorOptions apiRead = apiBehavior(apiOrigin, headers, AllowedMethods.ALLOW_GET_HEAD);
        BehaviorOptions apiWrite = apiBehavior(apiOrigin, headers, AllowedMethods.ALLOW_ALL);

        // Ordem fixa (LinkedHashMap): Map.of muda a ordem entre execuções e geraria diffs à toa no template.
        Map<String, BehaviorOptions> behaviors = new LinkedHashMap<>();
        behaviors.put("/og/*", BehaviorOptions.builder()
                .origin(S3BucketOrigin.withOriginAccessControl(og))
                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                .cachePolicy(CachePolicy.CACHING_OPTIMIZED)
                .responseHeadersPolicy(headers)
                .build());
        behaviors.put("/r/*", apiRead);
        behaviors.put("/rolls/*", apiRead);
        behaviors.put("/sessions*", apiWrite);
        behaviors.put("/pairings*", apiWrite);

        Distribution distribution = Distribution.Builder.create(this, "Cdn")
                .domainNames(List.of(cfg.domainName()))
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
        CfnOutput.Builder.create(this, "ApiEndpoint").value(api.getApiEndpoint()).build();
    }

    private static Map<String, Object> throttle(int rate, int burst) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ThrottlingRateLimit", rate);
        m.put("ThrottlingBurstLimit", burst);
        return m;
    }

    private Bucket privateBucket(String id) {
        return Bucket.Builder.create(this, id)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
    }

    private static void route(HttpApi api, HttpLambdaIntegration integration, String path, HttpMethod method) {
        api.addRoutes(AddRoutesOptions.builder().path(path).methods(List.of(method)).integration(integration).build());
    }

    private static BehaviorOptions apiBehavior(HttpOrigin origin, ResponseHeadersPolicy headers, AllowedMethods methods) {
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
