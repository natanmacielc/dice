package io.github.natanmacielc.dice.app.ledger;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;

final class LedgerTestSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private LedgerTestSupport() {
    }

    static String newSession() {
        return given().contentType(ContentType.JSON).body("{\"name\":\"Mesa do Dragão\"}")
                .post("/sessions").then().statusCode(201).extract().path("sessionId");
    }

    static Response requestRoll(String sessionId, String seed, String expression) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("clientSeed", seed, "expression", expression))
                .post("/sessions/" + sessionId + "/rolls");
    }

    static Map<String, Object> json(Response r) {
        try {
            return JSON.readValue(r.asString(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
