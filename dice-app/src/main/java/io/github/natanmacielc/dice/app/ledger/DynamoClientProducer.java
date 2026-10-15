package io.github.natanmacielc.dice.app.ledger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@ApplicationScoped
public class DynamoClientProducer {

    @Produces
    @ApplicationScoped
    DynamoDbClient dynamoDbClient(DynamoConfig config) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        config.endpointOverride().ifPresent(uri -> builder
                .endpointOverride(uri)
                .region(Region.US_EAST_1)
                // DynamoDB Local aceita qualquer credencial; não é segredo.
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"))));
        return builder.build();
    }

    void close(@Disposes DynamoDbClient client) {
        client.close();
    }
}
