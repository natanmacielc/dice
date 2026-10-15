package io.github.natanmacielc.dice.app.ledger;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * DynamoDB Local (tabela única PK/SK) e s3mock (bucket das imagens OG) em containers, como o CDK
 * criará na Fase 5.
 */
public class LocalAwsResource implements QuarkusTestResourceLifecycleManager {

    static final String TABLE = "dice";
    static final String BUCKET = "dice-og";
    static final StaticCredentialsProvider LOCAL = StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
    static String s3Endpoint;

    private GenericContainer<?> dynamo;
    private GenericContainer<?> s3;

    @Override
    public Map<String, String> start() {
        dynamo = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
                .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
                .withExposedPorts(8000);
        s3 = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3")).withExposedPorts(9090);
        dynamo.start();
        s3.start();
        String dynamoEndpoint = "http://" + dynamo.getHost() + ":" + dynamo.getMappedPort(8000);
        s3Endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(9090);
        try (DynamoDbClient client = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .endpointOverride(URI.create(dynamoEndpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(LOCAL)
                .build()) {
            client.createTable(r -> r.tableName(TABLE)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()));
        }
        try (S3Client client = s3Client()) {
            client.createBucket(r -> r.bucket(BUCKET));
        }
        return Map.of(
                "dice.dynamodb.endpoint-override", dynamoEndpoint,
                "dice.dynamodb.table", TABLE,
                "dice.s3.endpoint-override", s3Endpoint,
                "dice.s3.bucket", BUCKET,
                "dice.public-base-url", "https://dados.exemplo.dev");
    }

    static S3Client s3Client() {
        return S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .endpointOverride(URI.create(s3Endpoint))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(LOCAL)
                .build();
    }

    @Override
    public void stop() {
        if (dynamo != null) {
            dynamo.stop();
        }
        if (s3 != null) {
            s3.stop();
        }
    }
}
