package io.github.natanmacielc.dice.app.og;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@ApplicationScoped
public class S3ImageStore implements ImageStore {

    private final S3Client s3;
    private final String bucket;

    public S3ImageStore(S3Config config) {
        S3ClientBuilder builder = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        config.endpointOverride().ifPresent(uri -> builder
                .endpointOverride(uri)
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                // S3 local aceita qualquer credencial; não é segredo.
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"))));
        this.s3 = builder.build();
        this.bucket = config.bucket();
    }

    @Override
    public void putPng(String key, byte[] png) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType("image/png")
                .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(png));
    }

    @PreDestroy
    void close() {
        s3.close();
    }
}
