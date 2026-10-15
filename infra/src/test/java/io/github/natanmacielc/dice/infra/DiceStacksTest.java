package io.github.natanmacielc.dice.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class DiceStacksTest {

    static Path functionZip;
    static Path webDir;
    static Template dev;
    static Template cert;
    static Template oidc;
    static Template plan;
    static Path outdir;

    static App app(Map<String, Object> extra) {
        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "account", "111111111111",
                "hostedZoneId", "Z000EXAMPLE",
                "zoneName", "nimbos.solutions",
                "domainName", "dice.nimbos.solutions",
                "githubRepo", "natanmacielc/dice",
                "githubSubjectPrefix", "repo:natanmacielc@85594492/dice@1419905727",
                "githubEnvironment", "dev",
                "functionZip", functionZip.toString(),
                "webDir", webDir.toString()));
        ctx.put("@aws-cdk/core:defaultCrossStackReferences", "strong"); // igual ao cdk.json
        ctx.putAll(extra);
        return new App(AppProps.builder().context(ctx).build());
    }

    @BeforeAll
    static void synth() throws IOException {
        Path tmp = Files.createTempDirectory("dice-infra-test");
        functionZip = tmp.resolve("function.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(functionZip))) {
            zip.putNextEntry(new ZipEntry("placeholder.txt"));
            zip.write("teste".getBytes());
            zip.closeEntry();
        }
        webDir = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(webDir.resolve("index.html"), "<!doctype html>");
        Files.writeString(webDir.resolve("app.test.mjs"), "// teste");
        Files.writeString(webDir.resolve("README.md"), "# doc");
        App app = app(Map.of());
        DiceApp.Stacks stacks = DiceApp.build(app);
        outdir = Path.of(app.getOutdir());
        dev = Template.fromStack(stacks.dev());
        cert = Template.fromStack(stacks.cert());
        oidc = Template.fromStack(stacks.oidc());
        plan = Template.fromStack(stacks.plan());
    }

    // ---------- Dados ----------

    @Test
    void ledgerTableIsProtected() {
        dev.hasResource("AWS::DynamoDB::Table", Map.of(
                "DeletionPolicy", "Retain",
                "UpdateReplacePolicy", "Retain",
                "Properties", Map.of(
                        "KeySchema", List.of(Map.of("AttributeName", "PK", "KeyType", "HASH"),
                                Map.of("AttributeName", "SK", "KeyType", "RANGE")),
                        "BillingMode", Match.absent(), // ausente = PROVISIONED (o padrão do CloudFormation)
                        "DeletionProtectionEnabled", true,
                        "PointInTimeRecoverySpecification", Map.of("PointInTimeRecoveryEnabled", true))));
    }

    /** Teto rígido: a cota gratuita (25/25) e nenhum autoscaling que a ultrapasse. */
    @Test
    void ledgerCapacityIsCappedAtTheFreeTier() {
        dev.hasResourceProperties("AWS::DynamoDB::Table", Map.of(
                "ProvisionedThroughput", Map.of("ReadCapacityUnits", 25, "WriteCapacityUnits", 25)));
        dev.resourceCountIs("AWS::ApplicationAutoScaling::ScalableTarget", 0);
        Template smaller = Template.fromStack(DiceApp.build(app(Map.of("tableReadCapacity", "10",
                "tableWriteCapacity", "5"))).dev());
        smaller.hasResourceProperties("AWS::DynamoDB::Table", Map.of(
                "ProvisionedThroughput", Map.of("ReadCapacityUnits", 10, "WriteCapacityUnits", 5)));
    }

    @Test
    void pairingsExpireByTtl() {
        dev.hasResourceProperties("AWS::DynamoDB::Table", Map.of(
                "TimeToLiveSpecification", Map.of("AttributeName", "expiresAt", "Enabled", true)));
    }

    @Test
    void bucketsArePrivateEncryptedAndTlsOnly() {
        dev.resourceCountIs("AWS::S3::Bucket", 2);
        dev.allResourcesProperties("AWS::S3::Bucket", Map.of(
                "PublicAccessBlockConfiguration", Map.of("BlockPublicAcls", true, "BlockPublicPolicy", true,
                        "IgnorePublicAcls", true, "RestrictPublicBuckets", true),
                "BucketEncryption", Match.objectLike(Map.of("ServerSideEncryptionConfiguration",
                        List.of(Map.of("ServerSideEncryptionByDefault", Map.of("SSEAlgorithm", "AES256")))))));
        // Cada bucket: nega acesso sem TLS e só libera leitura ao CloudFront daquela distribuição.
        Map<String, Map<String, Object>> policies = dev.findResources("AWS::S3::BucketPolicy");
        assertEquals(2, policies.size());
        for (Map<String, Object> policy : policies.values()) {
            String json = policy.toString();
            assertTrue(json.contains("aws:SecureTransport"), json);
            assertTrue(json.contains("cloudfront.amazonaws.com"), json);
            assertTrue(json.contains("AWS:SourceArn"), json);
            assertFalse(json.contains("Principal={AWS=*}") && json.contains("Effect=Allow, Principal={AWS=*}"), json);
        }
    }

    // ---------- Lambda ----------

    @Test
    void functionUsesJava21SnapStartAndLiveAlias() {
        dev.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Runtime", "java21",
                "Architectures", List.of("x86_64"),
                "Handler", "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest",
                "MemorySize", 1024,
                "Timeout", 25,
                "SnapStart", Map.of("ApplyOn", "PublishedVersions"),
                "Environment", Map.of("Variables", Match.objectLike(Map.of(
                        "DICE_PUBLIC_BASE_URL", "https://dice.nimbos.solutions",
                        "DICE_DYNAMODB_TABLE", Match.anyValue(),
                        "DICE_S3_BUCKET", Match.anyValue())))));
        dev.hasResourceProperties("AWS::Lambda::Alias", Map.of("Name", "live"));
        dev.hasResourceProperties("AWS::Logs::LogGroup", Map.of("RetentionInDays", 30));
    }

    @Test
    void reservedConcurrencyIsOptional() {
        dev.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest",
                "ReservedConcurrentExecutions", Match.absent()));
        Template limited = Template.fromStack(DiceApp.build(app(Map.of("reservedConcurrency", "5"))).dev());
        limited.hasResourceProperties("AWS::Lambda::Function", Map.of("ReservedConcurrentExecutions", 5));
    }

    /** IAM mínimo: as policies da role da função só têm as ações listadas, nos recursos certos. */
    @Test
    void functionRoleHasLeastPrivilege() {
        List<Map<String, Object>> statements = statementsOf(roleOf(appLogicalId()));
        assertEquals(2, statements.size(), statements.toString());
        for (Map<String, Object> st : statements) {
            assertEquals("Allow", st.get("Effect"));
            assertFalse(st.get("Action").toString().contains("*"), "sem curinga em ações: " + st);
            assertFalse(st.get("Resource").toString().equals("*"), "sem recurso *: " + st);
        }
        Map<String, Object> dynamo = statements.stream().filter(s -> s.get("Action").toString().contains("dynamodb")).findFirst().orElseThrow();
        assertEquals(DiceDevStack.TABLE_ACTIONS, dynamo.get("Action"));
        assertTrue(dynamo.get("Resource").toString().contains("Ledger"), dynamo.toString());
        Map<String, Object> s3 = statements.stream().filter(s -> s.get("Action").toString().contains("s3")).findFirst().orElseThrow();
        assertEquals("s3:PutObject", s3.get("Action"));
        assertTrue(s3.get("Resource").toString().contains("OgBucket") && s3.get("Resource").toString().contains("/og/*"), s3.toString());
    }

    // ---------- Origem ----------

    @Test
    void noApiGatewayAndTheFunctionUrlRequiresIam() {
        for (String type : List.of("AWS::ApiGatewayV2::Api", "AWS::ApiGatewayV2::Stage", "AWS::ApiGatewayV2::Route",
                "AWS::ApiGateway::RestApi")) {
            dev.resourceCountIs(type, 0);
        }
        dev.resourceCountIs("AWS::Lambda::Url", 1);
        dev.hasResourceProperties("AWS::Lambda::Url", Map.of("AuthType", "AWS_IAM", "Qualifier", "live"));
        dev.hasResourceProperties("AWS::CloudFront::OriginAccessControl", Map.of("OriginAccessControlConfig",
                Match.objectLike(Map.of("OriginAccessControlOriginType", "lambda", "SigningBehavior", "always"))));
    }

    /** Só esta distribuição chama a URL: InvokeFunctionUrl e InvokeFunction (só via URL), com SourceArn. */
    @Test
    void onlyThisDistributionMayInvokeTheFunctionUrl() {
        for (Map<String, Object> grant : List.of(
                Map.<String, Object>of("Action", "lambda:InvokeFunctionUrl", "Principal", "cloudfront.amazonaws.com"),
                Map.<String, Object>of("Action", "lambda:InvokeFunction", "Principal", "cloudfront.amazonaws.com",
                        "InvokedViaFunctionUrl", true))) {
            Map<String, Map<String, Object>> found = dev.findResources("AWS::Lambda::Permission",
                    Map.of("Properties", grant));
            assertEquals(1, found.size(), grant.toString());
            String sourceArn = ((Map<?, ?>) found.values().iterator().next().get("Properties")).get("SourceArn").toString();
            assertTrue(sourceArn.contains(":distribution/") && sourceArn.contains("{Ref=" + cdnLogicalId() + "}"), sourceArn);
        }
        // Nenhuma permissão pública (Principal "*") em nenhuma função.
        assertFalse(dev.findResources("AWS::Lambda::Permission").toString().contains("Principal=*"));
    }

    // ---------- Kill switch ----------

    @Test
    void usageAlarmsTurnTheAppOffThroughTheKillSwitch() {
        String killTopic = logicalId("AWS::SNS::Topic", "KillTopic");
        for (String metric : List.of("Invocations", "Duration")) {
            dev.hasResourceProperties("AWS::CloudWatch::Alarm", Map.of(
                    "MetricName", metric,
                    "Namespace", "AWS/Lambda",
                    "Period", 3600,
                    "Statistic", "Sum",
                    "EvaluationPeriods", 1,
                    "ComparisonOperator", "GreaterThanThreshold",
                    "Threshold", metric.equals("Invocations") ? 10_000 : 1_200_000,
                    "TreatMissingData", "notBreaching",
                    "AlarmActions", List.of(Map.of("Ref", killTopic))));
        }
        dev.hasResourceProperties("AWS::SNS::Subscription", Map.of("Protocol", "lambda",
                "TopicArn", Map.of("Ref", killTopic),
                "Endpoint", Map.of("Fn::GetAtt", List.of(logicalId("AWS::Lambda::Function", "KillSwitch"), "Arn"))));
        // Throttle do DynamoDB só avisa.
        String noticeTopic = logicalId("AWS::SNS::Topic", "NoticeTopic");
        for (String metric : List.of("ReadThrottleEvents", "WriteThrottleEvents")) {
            dev.hasResourceProperties("AWS::CloudWatch::Alarm", Map.of("MetricName", metric,
                    "AlarmActions", List.of(Map.of("Ref", noticeTopic))));
        }
    }

    @Test
    void killSwitchCanOnlyZeroTheAppConcurrency() {
        dev.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Runtime", "python3.13",
                "Code", Map.of("ZipFile", DiceDevStack.KILL_SWITCH_CODE),
                "Environment", Map.of("Variables", Map.of("APP_FUNCTION", Map.of("Ref", appLogicalId())))));
        String role = roleOf(logicalId("AWS::Lambda::Function", "KillSwitch"));
        List<Map<String, Object>> statements = statementsOf(role);
        assertEquals(1, statements.size(), statements.toString());
        assertEquals("lambda:PutFunctionConcurrency", statements.get(0).get("Action"));
        assertEquals(Map.of("Fn::GetAtt", List.of(appLogicalId(), "Arn")), statements.get(0).get("Resource"));
    }

    @Test
    void killSwitchThresholdsComeFromContext() {
        Template t = Template.fromStack(DiceApp.build(app(Map.of("killSwitchInvocationsPerHour", "500",
                "killSwitchDurationMsPerHour", "60000"))).dev());
        t.hasResourceProperties("AWS::CloudWatch::Alarm", Map.of("MetricName", "Invocations", "Threshold", 500));
        t.hasResourceProperties("AWS::CloudWatch::Alarm", Map.of("MetricName", "Duration", "Threshold", 60_000));
    }

    @Test
    void alertEmailSubscribesBothTopicsAndCreatesTheBudget() {
        dev.resourcePropertiesCountIs("AWS::SNS::Subscription", Map.of("Protocol", "email"), 0);
        cert.resourceCountIs("AWS::Budgets::Budget", 0);
        DiceApp.Stacks withEmail = DiceApp.build(app(Map.of("alertEmail", "alertas@example.com")));
        Template d = Template.fromStack(withEmail.dev());
        d.resourcePropertiesCountIs("AWS::SNS::Subscription",
                Map.of("Protocol", "email", "Endpoint", "alertas@example.com"), 2);
        Template c = Template.fromStack(withEmail.cert());
        c.hasResourceProperties("AWS::Budgets::Budget", Map.of(
                "Budget", Map.of("BudgetName", "dice-mensal", "BudgetType", "COST", "TimeUnit", "MONTHLY",
                        "BudgetLimit", Map.of("Amount", 1, "Unit", "USD")),
                "NotificationsWithSubscribers", List.of(
                        budgetNotice("ACTUAL"), budgetNotice("FORECASTED"))));
    }

    private static Map<String, Object> budgetNotice(String type) {
        return Map.of("Notification", Map.of("NotificationType", type, "ComparisonOperator", "GREATER_THAN",
                        "Threshold", 100, "ThresholdType", "PERCENTAGE"),
                "Subscribers", List.of(Map.of("SubscriptionType", "EMAIL", "Address", "alertas@example.com")));
    }

    private static String logicalId(String type, String prefix) {
        List<String> ids = dev.findResources(type).keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        assertEquals(1, ids.size(), type + " " + prefix + ": " + ids);
        return ids.get(0);
    }

    private static String appLogicalId() {
        return dev.findResources("AWS::Lambda::Function", Map.of("Properties", Map.of(
                "Handler", "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest"))).keySet().iterator().next();
    }

    private static String roleOf(String functionLogicalId) {
        Map<?, ?> props = (Map<?, ?>) dev.findResources("AWS::Lambda::Function").get(functionLogicalId).get("Properties");
        return ((List<?>) ((Map<?, ?>) props.get("Role")).get("Fn::GetAtt")).get(0).toString();
    }

    private static List<Map<String, Object>> statementsOf(String roleId) {
        List<Map<String, Object>> statements = new ArrayList<>();
        for (Map<String, Object> policy : dev.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> props = (Map<?, ?>) policy.get("Properties");
            if (props.get("Roles").toString().contains(roleId)) {
                for (Object s : (List<?>) ((Map<?, ?>) props.get("PolicyDocument")).get("Statement")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> st = (Map<String, Object>) s;
                    statements.add(st);
                }
            }
        }
        return statements;
    }

    // ---------- CloudFront e DNS ----------

    @Test
    void distributionServesTheDomainOverModernTls() {
        dev.hasResourceProperties("AWS::CloudFront::Distribution", Map.of("DistributionConfig", Match.objectLike(Map.of(
                "Aliases", List.of("dice.nimbos.solutions"),
                "DefaultRootObject", "index.html",
                "PriceClass", "PriceClass_All",
                "ViewerCertificate", Match.objectLike(Map.of(
                        "MinimumProtocolVersion", "TLSv1.2_2021", "SslSupportMethod", "sni-only")),
                "DefaultCacheBehavior", Match.objectLike(Map.of("ViewerProtocolPolicy", "redirect-to-https")),
                // O plano Free aceita até 5 behaviors (contando o default).
                "CacheBehaviors", List.of(
                        Match.objectLike(Map.of("PathPattern", "/og/*", "ViewerProtocolPolicy", "redirect-to-https")),
                        Match.objectLike(Map.of("PathPattern", "/r/*", "ViewerProtocolPolicy", "redirect-to-https",
                                "AllowedMethods", List.of("GET", "HEAD"))),
                        Match.objectLike(Map.of("PathPattern", "/api/*", "ViewerProtocolPolicy", "redirect-to-https",
                                "AllowedMethods", Match.arrayWith(List.of("POST")))))))));
    }

    @Test
    void appBehaviorsGoToTheFunctionUrlOrigin() {
        Map<?, ?> config = (Map<?, ?>) ((Map<?, ?>) dev.findResources("AWS::CloudFront::Distribution").values()
                .iterator().next().get("Properties")).get("DistributionConfig");
        String lambdaOrigin = ((List<?>) config.get("Origins")).stream().map(o -> (Map<?, ?>) o)
                .filter(o -> o.toString().contains("FunctionUrl")).map(o -> o.get("Id").toString())
                .findFirst().orElseThrow();
        List<String> toLambda = ((List<?>) config.get("CacheBehaviors")).stream().map(b -> (Map<?, ?>) b)
                .filter(b -> b.get("TargetOriginId").equals(lambdaOrigin)).map(b -> b.get("PathPattern").toString())
                .toList();
        assertEquals(List.of("/r/*", "/api/*"), toLambda);
    }

    // ---------- WAF e plano ----------

    @Test
    void wafLimitsEachIpAndBlocksWith429() {
        cert.hasResourceProperties("AWS::WAFv2::WebACL", Map.of(
                "Scope", "CLOUDFRONT",
                "DefaultAction", Map.of("Allow", Map.of()),
                "Rules", Match.arrayWith(List.of(
                        Match.objectLike(Map.of("Name", "rate-per-ip", "Statement", Map.of("RateBasedStatement",
                                        Map.of("Limit", DiceCertStack.RATE_LIMIT_ALL, "AggregateKeyType", "IP",
                                                "EvaluationWindowSec", 300)),
                                "Action", Map.of("Block", Map.of("CustomResponse", Map.of("ResponseCode", 429))))),
                        Match.objectLike(Map.of("Name", "api-rate-per-ip", "Statement", Map.of("RateBasedStatement",
                                Match.objectLike(Map.of("Limit", DiceCertStack.RATE_LIMIT_API, "AggregateKeyType", "IP",
                                        "ScopeDownStatement", Map.of("ByteMatchStatement", Match.objectLike(Map.of(
                                                "PositionalConstraint", "STARTS_WITH", "SearchString", "/api/")))))))),
                        Match.objectLike(Map.of("Name", "ip-reputation"))))));
        // O plano Free permite no máximo 5 regras.
        Map<?, ?> props = (Map<?, ?>) cert.findResources("AWS::WAFv2::WebACL").values().iterator().next().get("Properties");
        assertTrue(((List<?>) props.get("Rules")).size() <= 5);
        // A distribuição usa o WAF.
        dev.hasResourceProperties("AWS::CloudFront::Distribution", Map.of("DistributionConfig",
                Match.objectLike(Map.of("WebACLId", Match.anyValue()))));
    }

    @Test
    void distributionIsOnTheFreeFlatRatePlan() {
        plan.hasResourceProperties("AWS::PricingPlanManager::Subscription", Map.of(
                "PlanFamily", "CloudFront",
                "PlanTier", "FREE",
                "ResourceArns", List.of(Match.anyValue(), Match.anyValue())));
        assertTrue(plan.toJSON().toString().contains(":distribution/"));
        assertTrue(plan.toJSON().toString().contains("WebAclArn"));
    }

    // ---------- CloudFront e DNS (cont.) ----------


    @Test
    void apiBehaviorsAreNotCachedAndForwardViewerHeadersExceptHost() {
        // Managed-CachingDisabled e Managed-AllViewerExceptHostHeader.
        String json = dev.toJSON().toString();
        assertTrue(json.contains("4135ea2d-6df8-44a3-9df3-4b5a84be39ad"), "CachingDisabled");
        assertTrue(json.contains("b689b0a8-53d0-40ab-baf2-68738e2966ac"), "AllViewerExceptHostHeader");
    }

    @Test
    void securityHeadersOnEveryResponse() {
        dev.hasResourceProperties("AWS::CloudFront::ResponseHeadersPolicy", Map.of("ResponseHeadersPolicyConfig",
                Match.objectLike(Map.of("SecurityHeadersConfig", Map.of(
                        "ContentSecurityPolicy", Map.of("ContentSecurityPolicy", DiceDevStack.CSP, "Override", true),
                        "StrictTransportSecurity", Map.of("AccessControlMaxAgeSec", 31536000,
                                "IncludeSubdomains", false, "Override", true),
                        "ContentTypeOptions", Map.of("Override", true),
                        "FrameOptions", Map.of("FrameOption", "DENY", "Override", true),
                        "ReferrerPolicy", Map.of("ReferrerPolicy", "strict-origin-when-cross-origin", "Override", true))))));
    }

    @Test
    void dnsAliasesPointToCloudFront() {
        Map<String, Object> toCdn = Map.of("DNSName", Map.of("Fn::GetAtt", List.of(cdnLogicalId(), "DomainName")));
        dev.hasResourceProperties("AWS::Route53::RecordSet", Map.of("Name", "dice.nimbos.solutions.", "Type", "A",
                "HostedZoneId", "Z000EXAMPLE", "AliasTarget", Match.objectLike(toCdn)));
        dev.hasResourceProperties("AWS::Route53::RecordSet", Map.of("Name", "dice.nimbos.solutions.", "Type", "AAAA",
                "HostedZoneId", "Z000EXAMPLE", "AliasTarget", Match.objectLike(toCdn)));
    }

    private static String cdnLogicalId() {
        return dev.findResources("AWS::CloudFront::Distribution").keySet().iterator().next();
    }

    @Test
    void certificateIsDnsValidatedInUsEast1() {
        cert.hasResourceProperties("AWS::CertificateManager::Certificate", Map.of(
                "DomainName", "dice.nimbos.solutions",
                "ValidationMethod", "DNS",
                "DomainValidationOptions", List.of(Map.of("DomainName", "dice.nimbos.solutions",
                        "HostedZoneId", "Z000EXAMPLE"))));
    }

    @Test
    void webDeploymentExcludesTestsAndReadmes() throws IOException {
        dev.resourceCountIs("Custom::CDKBucketDeployment", 1);
        // O asset empacotado pelo CDK tem o index.html, mas não os testes nem o README.
        List<Path> staged;
        try (var files = Files.walk(outdir)) {
            staged = files.filter(f -> f.getFileName().toString().equals("index.html")).toList();
        }
        assertFalse(staged.isEmpty(), "asset da web não encontrado em " + outdir);
        for (Path index : staged) {
            assertFalse(Files.exists(index.resolveSibling("app.test.mjs")), index.toString());
            assertFalse(Files.exists(index.resolveSibling("README.md")), index.toString());
        }
    }

    // ---------- OIDC ----------

    @Test
    void githubRoleTrustsOnlyTheDevEnvironmentOfTheRepo() {
        oidc.hasResourceProperties("AWS::IAM::Role", Map.of(
                "RoleName", "dice-github-deploy",
                "MaxSessionDuration", 3600,
                "AssumeRolePolicyDocument", Map.of("Statement", List.of(Map.of(
                        "Action", "sts:AssumeRoleWithWebIdentity",
                        "Effect", "Allow",
                        "Condition", Map.of("StringEquals", Map.of(
                                "token.actions.githubusercontent.com:aud", "sts.amazonaws.com",
                                "token.actions.githubusercontent.com:sub",
                                "repo:natanmacielc@85594492/dice@1419905727:environment:dev")),
                        "Principal", Map.of("Federated", Match.anyValue()))), "Version", "2012-10-17")));
    }

    @Test
    void githubRoleCanOnlyAssumeCdkBootstrapRoles() {
        oidc.hasResourceProperties("AWS::IAM::Policy", Map.of("PolicyDocument", Map.of("Statement", List.of(Map.of(
                "Action", "sts:AssumeRole",
                "Effect", "Allow",
                "Resource", "arn:aws:iam::111111111111:role/cdk-hnb659fds-*")), "Version", "2012-10-17")));
        oidc.resourceCountIs("Custom::AWSCDKOpenIdConnectProvider", 1);
    }

    @Test
    void existingOidcProviderIsImportedNotCreated() {
        Template t = Template.fromStack(DiceApp.build(app(Map.of("existingOidcProviderArn",
                "arn:aws:iam::111111111111:oidc-provider/token.actions.githubusercontent.com"))).oidc());
        t.resourceCountIs("Custom::AWSCDKOpenIdConnectProvider", 0);
    }

    @Test
    void withoutHostedZoneOnlyTheOidcStackIsSynthesized() {
        Map<String, Object> ctx = new java.util.HashMap<>(Map.of("hostedZoneId", ""));
        DiceApp.Stacks stacks = DiceApp.build(app(ctx));
        assertNull(stacks.dev());
        assertNull(stacks.cert());
        assertNull(stacks.plan());
    }
}
