package io.github.natanmacielc.dice.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ThrottlingException;

class ErrorMappersTest {

    @Test
    void dynamoThrottlingBecomesRetryable503() {
        // O DynamoDB Local não simula o teto de capacidade; o mapeamento é testado direto.
        for (RuntimeException e : List.of(ProvisionedThroughputExceededException.builder().message("cheio").build(),
                RequestLimitExceededException.builder().message("cheio").build(),
                ThrottlingException.builder().message("cheio").build())) {
            Response r = new ErrorMappers().throttled(e);
            assertEquals(503, r.getStatus());
            assertEquals("2", r.getHeaderString("Retry-After"));
            assertEquals("SERVER_BUSY", ((ErrorResponse) r.getEntity()).error());
        }
    }
}
