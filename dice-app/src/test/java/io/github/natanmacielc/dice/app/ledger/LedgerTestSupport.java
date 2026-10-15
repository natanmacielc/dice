package io.github.natanmacielc.dice.app.ledger;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.github.natanmacielc.dice.core.chain.Player;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;

final class LedgerTestSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private LedgerTestSupport() {
    }

    static String newSession() {
        return given().contentType(ContentType.JSON).body("{\"name\":\"Mesa do Dragão\"}")
                .post("/api/sessions").then().statusCode(201).extract().path("sessionId");
    }

    /** Pedido assinado pela Ana de teste. */
    static Response requestRoll(String sessionId, String seed, String expression) {
        return requestRoll(sessionId, seed, expression, TestPlayers.ana(sessionId, seed, expression));
    }

    static Response requestRoll(String sessionId, String seed, String expression, Player player) {
        Map<String, Object> body = new HashMap<>(Map.of("clientSeed", seed, "expression", expression));
        if (player != null) {
            body.put("player", player.toPayload());
        }
        return given().contentType(ContentType.JSON).body(body).post("/api/sessions/" + sessionId + "/rolls");
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
