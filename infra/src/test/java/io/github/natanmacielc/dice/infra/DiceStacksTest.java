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
    static Path outdir;

    static App app(Map<String, Object> extra) {
        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "account", "111111111111",
                "hostedZoneId", "Z000EXAMPLE",
                "zoneName", "nimbos.solutions",
                "domainName", "dices.nimbos.solutions",
                "githubRepo", "natanmacielc/dices",
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
                        "BillingMode", "PAY_PER_REQUEST",
                        "DeletionProtectionEnabled", true,
                        "PointInTimeRecoverySpecification", Map.of("PointInTimeRecoveryEnabled", true))));
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
                        "DICE_PUBLIC_BASE_URL", "https://dices.nimbos.solutions",
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
        String roleId = functionRoleLogicalId();
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

    private static String functionRoleLogicalId() {
        Map<String, Map<String, Object>> fns = dev.findResources("AWS::Lambda::Function", Map.of(
                "Properties", Map.of("Handler", "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")));
        assertEquals(1, fns.size());
        Map<?, ?> role = (Map<?, ?>) ((Map<?, ?>) fns.values().iterator().next().get("Properties")).get("Role");
        return ((List<?>) role.get("Fn::GetAtt")).get(0).toString();
    }

    // ---------- API ----------

    @Test
    void apiHasOnlyExplicitRoutes() {
        Map<String, Map<String, Object>> routes = dev.findResources("AWS::ApiGatewayV2::Route");
        List<String> keys = routes.values().stream()
                .map(r -> ((Map<?, ?>) r.get("Properties")).get("RouteKey").toString()).sorted().toList();
        assertEquals(List.of("GET /r/{rollId}", "GET /rolls/{rollId}", "GET /rolls/{rollId}/proof",
                "GET /sessions/{sessionId}/log", "POST /sessions", "POST /sessions/{sessionId}/rolls"), keys);
    }

    @Test
    void stageThrottlesByDefaultAndPerRoute() {
        dev.hasResourceProperties("AWS::ApiGatewayV2::Stage", Map.of(
                "StageName", "$default",
                "AutoDeploy", true,
                "DefaultRouteSettings", Map.of("ThrottlingRateLimit", 20, "ThrottlingBurstLimit", 40),
                "RouteSettings", Map.of(
                        DiceDevStack.ROUTE_CREATE_SESSION, Map.of("ThrottlingRateLimit", 1, "ThrottlingBurstLimit", 5),
                        DiceDevStack.ROUTE_CREATE_ROLL, Map.of("ThrottlingRateLimit", 5, "ThrottlingBurstLimit", 10))));
    }

    // ---------- CloudFront e DNS ----------

    @Test
    void distributionServesTheDomainOverModernTls() {
        dev.hasResourceProperties("AWS::CloudFront::Distribution", Map.of("DistributionConfig", Match.objectLike(Map.of(
                "Aliases", List.of("dices.nimbos.solutions"),
                "DefaultRootObject", "index.html",
                "PriceClass", "PriceClass_All",
                "ViewerCertificate", Match.objectLike(Map.of(
                        "MinimumProtocolVersion", "TLSv1.2_2021", "SslSupportMethod", "sni-only")),
                "DefaultCacheBehavior", Match.objectLike(Map.of("ViewerProtocolPolicy", "redirect-to-https")),
                "CacheBehaviors", List.of(
                        Match.objectLike(Map.of("PathPattern", "/og/*", "ViewerProtocolPolicy", "redirect-to-https")),
                        Match.objectLike(Map.of("PathPattern", "/r/*", "ViewerProtocolPolicy", "redirect-to-https",
                                "AllowedMethods", List.of("GET", "HEAD"))),
                        Match.objectLike(Map.of("PathPattern", "/rolls/*", "ViewerProtocolPolicy", "redirect-to-https",
                                "AllowedMethods", List.of("GET", "HEAD"))),
                        Match.objectLike(Map.of("PathPattern", "/sessions*", "ViewerProtocolPolicy", "redirect-to-https",
                                "AllowedMethods", Match.arrayWith(List.of("POST")))))))));
    }

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
        dev.hasResourceProperties("AWS::Route53::RecordSet", Map.of("Name", "dices.nimbos.solutions.", "Type", "A",
                "HostedZoneId", "Z000EXAMPLE", "AliasTarget", Match.objectLike(toCdn)));
        dev.hasResourceProperties("AWS::Route53::RecordSet", Map.of("Name", "dices.nimbos.solutions.", "Type", "AAAA",
                "HostedZoneId", "Z000EXAMPLE", "AliasTarget", Match.objectLike(toCdn)));
    }

    private static String cdnLogicalId() {
        return dev.findResources("AWS::CloudFront::Distribution").keySet().iterator().next();
    }

    @Test
    void certificateIsDnsValidatedInUsEast1() {
        cert.hasResourceProperties("AWS::CertificateManager::Certificate", Map.of(
                "DomainName", "dices.nimbos.solutions",
                "ValidationMethod", "DNS",
                "DomainValidationOptions", List.of(Map.of("DomainName", "dices.nimbos.solutions",
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
                                "token.actions.githubusercontent.com:sub", "repo:natanmacielc/dices:environment:dev")),
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
    }
}
